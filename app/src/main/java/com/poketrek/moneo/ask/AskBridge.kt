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

/**
 * Asks an LLM about what's on screen through a shared folder: the app writes
 * `inbox/<id>.png` then `inbox/<id>.json` ([AskRequest]), and a watcher
 * (tools/ask_bridge/moneo-ask.sh, e.g. Claude Code in Termux) answers with
 * `outbox/<id>.md`. The folder is picked with the Storage Access Framework,
 * so it can live in Termux's home (Termux exposes it as a documents provider).
 */
class AskBridge(context: Context, private val prefs: MoneoPrefs) {

    /** A question and, once the watcher answers, its [reply] (or [error]). */
    data class Exchange(
        val id: String,
        val question: String,
        val message: String?,
        val reply: String? = null,
        val error: String? = null,
    ) {
        val pending get() = reply == null && error == null
    }

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _exchanges = MutableStateFlow<List<Exchange>>(emptyList())
    /** This session's questions, oldest first. */
    val exchanges: StateFlow<List<Exchange>> = _exchanges.asStateFlow()

    val configured: Boolean get() = prefs.askFolder.value != null

    /**
     * Writes the request and waits for the reply in the background. [build]
     * gets the new id and whether this continues the previous conversation.
     */
    fun ask(question: String, message: String?, screen: Bitmap?, build: (id: String, followUp: Boolean) -> AskRequest) {
        val folder = prefs.askFolder.value?.let(Uri::parse) ?: return
        val now = System.currentTimeMillis()
        val id = AskRequest.newId(now)
        val prev = _exchanges.value.lastOrNull()
        val request = build(id, AskRequest.isFollowUp(prev?.message, prev != null, message))
        _exchanges.update { it + Exchange(id, question, message) }
        scope.launch {
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
            val (reply, error) = result.fold(
                { it to (if (it == null) "No reply after ${TIMEOUT_MS / 1000}s — is moneo-ask.sh running?" else null) },
                { Log.w(TAG, "ask $id failed", it); null to (it.message ?: it.javaClass.simpleName) },
            )
            _exchanges.update { list -> list.map { if (it.id == id) it.copy(reply = reply, error = error) else it } }
        }
    }

    fun clear() = _exchanges.update { list -> list.filter { it.pending } }

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
    }
}
