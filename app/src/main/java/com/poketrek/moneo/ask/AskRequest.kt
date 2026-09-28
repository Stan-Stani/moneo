package com.poketrek.moneo.ask

import com.poketrek.moneo.reading.ReadingRow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One question to the LLM watching the ask folder (tools/ask_bridge), written
 * as `inbox/<id>.json` next to a screenshot `inbox/<id>.png`. The watcher
 * replies with `outbox/<id>.md`.
 *
 * [message] is the open message box's text as decoded from RAM (null when no
 * box is open; the screenshot is then all there is). [followUp] asks the
 * watcher to continue the previous conversation instead of starting fresh.
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
) {
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
