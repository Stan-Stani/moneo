package com.poketrek.moneo.data

import android.content.Context
import org.json.JSONObject

/**
 * Loanwords in the deck, shipped as `assets/moneo/loanwords.json` (built by
 * `tools/moneo/build_loanwords.py`): which part of a word is borrowed and
 * from what, e.g. 시티 ← English "city", or just 펀치 ← "punch" in 불꽃펀치.
 */
class LoanwordDict(private val words: Map<String, Loan>) {

    data class Loan(val part: String, val source: String, val language: String)

    fun lookup(korean: String): Loan? = words[korean]

    companion object {
        val EMPTY = LoanwordDict(emptyMap())

        fun parse(json: String): LoanwordDict {
            val obj = JSONObject(json).getJSONObject("words")
            val out = HashMap<String, Loan>(obj.length())
            for (k in obj.keys()) {
                val o = obj.optJSONObject(k) ?: continue
                out[k] = Loan(o.getString("part"), o.getString("source"), o.optString("lang", "English"))
            }
            return LoanwordDict(out)
        }

        fun loadFromAssets(context: Context, path: String = "moneo/loanwords.json"): LoanwordDict =
            parse(context.assets.open(path).use { it.readBytes() }.toString(Charsets.UTF_8))
    }
}
