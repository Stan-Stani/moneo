package com.poketrek.moneo.ask

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.poketrek.moneo.data.MoneoPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Asks an LLM about what's on screen. With an endpoint set, calls it directly
 * ([ClaudeAsker] or [OpenAiAsker]). Otherwise goes through a shared folder: the app writes
 * `inbox/<id>.png` then `inbox/<id>.json` ([AskRequest]), and a watcher
 * (tools/ask_bridge/moneo-ask.sh, e.g. Claude Code in Termux) answers with
 * `outbox/<id>.md`. The folder is picked with the Storage Access Framework,
 * so it can live in Termux's home (Termux exposes it as a documents provider).
 */
class AskBridge(context: Context, private val prefs: MoneoPrefs) {

    /**
     * A question and, once answered, its [reply] (or [error]). While a direct
     * answer streams in, [partial] holds the text so far.
     */
    data class Exchange(
        val id: String,
        val question: String,
        val message: String?,
        val reply: String? = null,
        val error: String? = null,
        val partial: String? = null,
        /** Dictionary forms the answer flagged as worth studying ([AnswerWords]). */
        val words: List<String> = emptyList(),
    ) {
        val pending get() = reply == null && error == null
    }

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _exchanges = MutableStateFlow<List<Exchange>>(emptyList())
    /** This session's questions, oldest first. */
    val exchanges: StateFlow<List<Exchange>> = _exchanges.asStateFlow()

    val configured: Boolean get() = prefs.askEndpoint.value != null || prefs.askFolder.value != null

    private var asker: Asker? = null

    /** The direct-API asker for [endpoint], rebuilt when the settings change. */
    private fun askerFor(endpoint: AskEndpoint): Asker =
        asker?.takeIf { it.endpoint == endpoint } ?: Asker.create(endpoint).also { asker = it }

    /**
     * Writes the request and waits for the reply in the background. [build]
     * gets the new id and whether this continues the previous conversation.
     */
    fun ask(question: String, message: String?, screen: Bitmap?, build: (id: String, followUp: Boolean) -> AskRequest) {
        val endpoint = prefs.askEndpoint.value
        val folder = prefs.askFolder.value?.let(Uri::parse)
        if (endpoint == null && folder == null) return
        val now = System.currentTimeMillis()
        val id = AskRequest.newId(now)
        val prev = _exchanges.value.lastOrNull()
        val request = build(id, AskRequest.isFollowUp(prev?.message, prev != null, message))
        _exchanges.update { it + Exchange(id, question, message) }
        scope.launch {
            val (reply, error) = if (endpoint != null) {
                askDirect(endpoint, request, screen)
            } else {
                askThroughFolder(folder!!, id, request, screen)
            }
            val split = reply?.let(AnswerWords::split)
            _exchanges.update { list ->
                list.map { if (it.id == id) it.copy(reply = split?.text, error = error, words = split?.words.orEmpty()) else it }
            }
        }
    }

    /** One question at a time, so a follow-up sees the answer before it. */
    private val directLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun askDirect(endpoint: AskEndpoint, request: AskRequest, screen: Bitmap?): Pair<String?, String?> =
        directLock.withLock {
            var shownAt = 0L
            val onText = { text: String ->
                // A fast stream sends many tiny chunks; redraw at most ~12×/s.
                val now = System.currentTimeMillis()
                if (now - shownAt >= STREAM_REDRAW_MS) {
                    shownAt = now
                    val shown = AnswerWords.visible(text)
                    _exchanges.update { list -> list.map { if (it.id == request.id) it.copy(partial = shown) else it } }
                }
            }
            runCatching { askerFor(endpoint).ask(request, screen, onText) }.fold(
                { it to null },
                { Log.w(TAG, "ask ${request.id} failed", it); null to (it.message ?: it.javaClass.simpleName) },
            )
        }

    private suspend fun askThroughFolder(folder: Uri, id: String, request: AskRequest, screen: Bitmap?): Pair<String?, String?> {
        val result = runCatching {
            val root = rootDocument(folder)
            val inbox = childDir(folder, root, "inbox")
            val outbox = childDir(folder, root, "outbox")
            if (screen != null) {
                writeFile(inbox, "$id.png", "image/png") { out ->
                    screen.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            // The JSON goes last: the watcher treats it as "request complete".
            writeFile(inbox, "$id.json", "application/json") { out ->
                out.write(request.toJson().toString(1).toByteArray())
            }
            awaitReply(folder, outbox, "$id.md")
        }
        return result.fold(
            { it to (if (it == null) "No reply after ${TIMEOUT_MS / 1000}s — is moneo-ask.sh running?" else null) },
            { Log.w(TAG, "ask $id failed", it); null to (it.message ?: it.javaClass.simpleName) },
        )
    }

    fun clear() = _exchanges.update { list -> list.filter { it.pending } }

    /**
     * Writes [text] to [name] at the top of the ask folder, replacing it if it
     * exists. Blocking; call off the main thread. Throws when no folder is set.
     */
    fun writeTopLevel(name: String, text: String) {
        val tree = prefs.askFolder.value?.let(Uri::parse) ?: error("no ask folder set")
        val root = rootDocument(tree)
        val doc = findChild(tree, root, name)
            ?: DocumentsContract.createDocument(resolver, root, "text/markdown", name)
            ?: error("Can't create $name in the ask folder")
        resolver.openOutputStream(doc, "wt")?.use { it.write(text.toByteArray()) } ?: error("Can't write $name")
    }

    private suspend fun awaitReply(tree: Uri, outbox: Uri, name: String): String? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val doc = findChild(tree, outbox, name)
            if (doc != null) {
                val text = resolver.openInputStream(doc)?.use { it.readBytes().decodeToString() }
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                if (text != null) return text.trim()
            }
            delay(POLL_MS)
        }
        return null
    }

    private fun rootDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun childDir(tree: Uri, parent: Uri, name: String): Uri =
        findChild(tree, parent, name)
            ?: DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
            ?: error("Can't create $name/ in the ask folder")

    private fun findChild(tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        return null
    }

    private fun writeFile(dir: Uri, name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val doc = DocumentsContract.createDocument(resolver, dir, mime, name)
            ?: error("Can't create $name in the ask folder")
        resolver.openOutputStream(doc, "w")?.use(write) ?: error("Can't write $name")
    }

    companion object {
        private const val TAG = "AskBridge"
        private const val POLL_MS = 700L
        private const val TIMEOUT_MS = 240_000L
        private const val STREAM_REDRAW_MS = 80L
    }
}
