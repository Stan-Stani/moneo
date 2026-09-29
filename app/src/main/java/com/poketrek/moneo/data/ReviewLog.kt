package com.poketrek.moneo.data

import android.util.Log
import com.poketrek.moneo.srs.CardState
import com.poketrek.moneo.srs.Rating
import org.json.JSONObject
import java.io.File

/**
 * Every grade the player gives, appended to [file] as one JSON object per
 * line. Card state only keeps the outcome of the latest review, so this is
 * what tells "missed twice this week" or "first studied on Monday" apart
 * (see [StudyDigest]).
 */
class ReviewLog(private val file: File) {

    /** [before] is the card's state before this grade: NEW means first study. */
    data class Entry(val vocabId: String, val rating: Rating, val atMs: Long, val before: CardState)

    @Synchronized
    fun append(entry: Entry) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(format(entry) + "\n")
        }.onFailure { Log.w(TAG, "append failed", it) }
    }

    /** Entries at or after [sinceMs], oldest first; unreadable lines are skipped. */
    @Synchronized
    fun since(sinceMs: Long): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { parse(it) }.filter { it.atMs >= sinceMs }
    }

    /** Time of the oldest entry, or null when nothing has been logged yet. */
    @Synchronized
    fun firstMs(): Long? {
        if (!file.exists()) return null
        return file.bufferedReader().useLines { lines -> lines.firstNotNullOfOrNull { parse(it) }?.atMs }
    }

    companion object {
        private const val TAG = "ReviewLog"

        fun format(e: Entry): String = JSONObject()
            .put("id", e.vocabId).put("r", e.rating.name).put("t", e.atMs).put("b", e.before.name)
            .toString()

        fun parse(line: String): Entry? = runCatching {
            val o = JSONObject(line)
            Entry(o.getString("id"), Rating.valueOf(o.getString("r")), o.getLong("t"), CardState.valueOf(o.getString("b")))
        }.getOrNull()
    }
}
