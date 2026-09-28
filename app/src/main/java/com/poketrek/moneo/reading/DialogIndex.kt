package com.poketrek.moneo.reading

import android.content.Context
import org.json.JSONObject

/**
 * Dialog lines of the 2024 KR ROM with the deck words each uses, shipped as
 * `assets/moneo/dialog_index.json` (tools/moneo/build_dialog_index.py). The
 * app can't tokenize Korean on device, so the live message box is matched
 * to a known line instead.
 */
class DialogIndex(private val lines: List<Line>) {

    /**
     * A dialog line. [segs] are its hangul runs between name placeholders and
     * runtime particles ({PLAYER}은(는) ...), which render differently per
     * save; [words] are deck words (VocabEntry.korean) in order of use.
     */
    data class Line(val id: Int, val segs: List<String>, val words: List<String>)

    /**
     * The line whose segments all appear, in order, in [message]'s hangul,
     * preferring the one covering the most of it. Null if no line covers at
     * least half the message (a menu, a name screen, an undecoded string).
     */
    fun match(message: String): Line? {
        val h = hangulOnly(message)
        if (h.length < 2) return null
        var best: Line? = null
        var bestScore = 0
        for (line in lines) {
            var from = 0
            var score = 0
            var ok = true
            for (seg in line.segs) {
                val at = h.indexOf(seg, from)
                if (at < 0) { ok = false; break }
                from = at + seg.length
                score += seg.length
            }
            if (ok && score > bestScore) { best = line; bestScore = score }
        }
        // A 2-syllable line (이상) is also a piece of countless names
        // (이상해씨), so short lines only match the whole message.
        return best?.takeIf { bestScore * 2 >= h.length && (bestScore >= 3 || bestScore == h.length) }
    }

    companion object {
        val EMPTY = DialogIndex(emptyList())

        fun hangulOnly(s: String): String = s.filter { it in '가'..'힣' }

        fun parse(json: String): DialogIndex {
            val arr = JSONObject(json).getJSONArray("lines")
            val out = ArrayList<Line>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val segs = o.getJSONArray("segs").let { a -> List(a.length()) { a.getString(it) } }
                val words = o.getJSONArray("words").let { a -> List(a.length()) { a.getString(it) } }
                out += Line(o.getInt("id"), segs, words)
            }
            return DialogIndex(out)
        }

        fun loadFromAssets(context: Context, path: String = "moneo/dialog_index.json"): DialogIndex =
            parse(context.assets.open(path).use { it.readBytes() }.toString(Charsets.UTF_8))
    }
}
