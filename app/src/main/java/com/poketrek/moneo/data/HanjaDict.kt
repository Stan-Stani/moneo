package com.poketrek.moneo.data

import android.content.Context
import org.json.JSONObject

/**
 * Hanja for Sino-Korean deck words, shipped as `assets/moneo/hanja.json`
 * (built by `tools/moneo/build_hanja.py`). Each word's hanja is aligned
 * syllable-for-syllable with its Korean spelling; native syllables stay
 * hangul (도망가다 → 逃亡가다).
 */
class HanjaDict(
    private val words: Map<String, String>,
    private val charMeanings: Map<Char, String>,
) {
    /** One syllable of a word: its hanja and meaning, or null for a native syllable. */
    data class Part(val syllable: Char, val hanja: Char?, val meaning: String?)

    /** Syllable-by-syllable breakdown of [korean], or null if it has no hanja. */
    fun breakdown(korean: String): List<Part>? {
        val hanja = words[korean] ?: return null
        if (hanja.length != korean.length) return null
        return korean.indices.map { i ->
            val c = hanja[i]
            if (c == korean[i]) Part(korean[i], null, null)
            else Part(korean[i], c, charMeanings[c])
        }
    }

    private val wordsByChar: Map<Char, List<String>> by lazy {
        val out = HashMap<Char, MutableList<String>>()
        for ((korean, hanja) in words) {
            for (c in hanja.toSet()) if (charMeanings.containsKey(c)) out.getOrPut(c) { mutableListOf() } += korean
        }
        out
    }

    /** Other words that share [hanja], e.g. 館 → 체육관, 박물관, 도서관. */
    fun wordsWith(hanja: Char, except: String? = null): List<String> =
        wordsByChar[hanja].orEmpty().filter { it != except }

    companion object {
        val EMPTY = HanjaDict(emptyMap(), emptyMap())

        fun parse(json: String): HanjaDict {
            val root = JSONObject(json)
            val wordsObj = root.getJSONObject("words")
            val words = HashMap<String, String>(wordsObj.length())
            for (k in wordsObj.keys()) {
                val h = wordsObj.optJSONObject(k)?.optString("hanja").orEmpty()
                if (h.isNotEmpty()) words[k] = h
            }
            val charsObj = root.getJSONObject("chars")
            val chars = HashMap<Char, String>(charsObj.length())
            for (k in charsObj.keys()) if (k.length == 1) chars[k[0]] = charsObj.optString(k)
            return HanjaDict(words, chars)
        }

        fun loadFromAssets(context: Context, path: String = "moneo/hanja.json"): HanjaDict {
            val json = context.assets.open(path).use { it.readBytes() }.toString(Charsets.UTF_8)
            return parse(json)
        }
    }
}
