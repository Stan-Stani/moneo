package com.poketrek.moneo.reading

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Messages the reading helper saw on screen but couldn't match to a dialog
 * line, kept in [file] across sessions so misses from real play can be
 * reviewed (Settings share button, or tools/moneo/pull_unmatched_dialog.py).
 * One entry per distinct message, with how often it was seen and where.
 */
class UnmatchedDialogLog(private val file: File) {

    data class Entry(val message: String, val count: Int, val firstSeenMs: Long, val map: String?)

    private val entries = LinkedHashMap<String, Entry>()
    private val _count = MutableStateFlow(0)
    /** Number of distinct unmatched messages. */
    val count: StateFlow<Int> = _count.asStateFlow()

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONObject(file.readText()).getJSONArray("messages")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = Entry(o.getString("message"), o.optInt("count", 1), o.optLong("firstSeenMs"),
                        o.optString("map").takeIf { it.isNotEmpty() })
                    entries[e.message] = e
                }
            }
        }
        _count.value = entries.size
    }

    @Synchronized
    fun record(message: String, map: String?, nowMs: Long = System.currentTimeMillis()) {
        if (hangulCount(message) < MIN_HANGUL) return  // menus, names, "……"
        val prev = entries[message]
        entries[message] = prev?.copy(count = prev.count + 1) ?: Entry(message, 1, nowMs, map)
        _count.value = entries.size
        save()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _count.value = 0
        file.delete()
    }

    /** Plain-text report for sharing, most frequent first. */
    @Synchronized
    fun report(): String = buildString {
        append("PokeTrek unmatched dialogue (${entries.size})\n\n")
        for (e in entries.values.sortedByDescending { it.count }) {
            append("×${e.count}  [${e.map ?: "?"}]  ${e.message.replace('\n', ' ')}\n")
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (e in entries.values) {
            arr.put(JSONObject().put("message", e.message).put("count", e.count)
                .put("firstSeenMs", e.firstSeenMs).put("map", e.map ?: ""))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("messages", arr).toString(1))
        tmp.renameTo(file)
    }

    companion object {
        const val MIN_HANGUL = 4
        fun hangulCount(s: String) = s.count { it in '가'..'힣' }
    }
}
