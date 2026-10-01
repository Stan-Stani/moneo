package com.poketrek.moneo.ask

import com.poketrek.moneo.reading.ReadingRow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One question to the LLM watching the ask folder (tools/ask_bridge), written
 * as `inbox/<id>.json` next to a screenshot `inbox/<id>.png`. The watcher
 * replies with `outbox/<id>.md`.
 *
 * [message] is the line the question is about, decoded from RAM: the open
 * message box, or when none is open ([messageOnScreen] false) the last line
 * shown, [messageSecondsAgo] ago. Null when there's neither; the screenshot
 * is then all there is. [recentMessages] are the lines before it, oldest
 * first. [followUp] asks the watcher to continue the previous conversation
 * instead of starting fresh.
 */
data class AskRequest(
    val id: String,
    val question: String,
    val message: String?,
    val lineId: Int?,
    val words: List<ReadingRow>,
    val knownWords: List<String>,
    val location: String?,
    val rom: String?,
    val followUp: Boolean,
    val hasScreenshot: Boolean,
    val createdMs: Long,
    val messageOnScreen: Boolean = message != null,
    val messageSecondsAgo: Long? = null,
    val recentMessages: List<Recent> = emptyList(),
) {
    /** An earlier line, [secondsAgo] before the question. */
    data class Recent(val message: String, val secondsAgo: Long)

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("id", id)
        .put("question", question)
        .put("followUp", followUp)
        .put("createdMs", createdMs)
        .put("screenshot", if (hasScreenshot) "$id.png" else JSONObject.NULL)
        .put("rom", rom ?: JSONObject.NULL)
        .put("location", location ?: JSONObject.NULL)
        .put("message", message ?: JSONObject.NULL)
        .put("messageOnScreen", messageOnScreen)
        .put("messageSecondsAgo", messageSecondsAgo ?: JSONObject.NULL)
        .put("recentMessages", JSONArray().apply {
            for (r in recentMessages) put(JSONObject().put("message", r.message).put("secondsAgo", r.secondsAgo))
        })
        .put("lineId", lineId ?: JSONObject.NULL)
        .put("words", JSONArray().apply {
            for (w in words) put(JSONObject().put("korean", w.korean).put("gloss", w.gloss).put("known", w.known))
        })
        .put("knownWords", JSONArray(knownWords))

    companion object {
        /** Sortable, filename-safe id: `20260928-143005-123`. */
        fun newId(nowMs: Long): String =
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date(nowMs))

        /** Whether a question is about the same message as the previous one. */
        fun isFollowUp(previousMessage: String?, hadPrevious: Boolean, message: String?): Boolean =
            hadPrevious && previousMessage == message
    }
}
