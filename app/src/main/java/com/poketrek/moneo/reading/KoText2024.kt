package com.poketrek.moneo.reading

import android.content.Context
import org.json.JSONObject

/**
 * Decoder for text in the 2024 Korean LeafGreen patch (port of
 * tools/moneo/ko_text.py). Gen 3's 1-byte charset for everything that isn't
 * hangul, plus 2-byte hangul syllables whose lead byte is 0x37..0x41.
 */
class KoText2024(private val codepoints: Map<Int, Char>) {

    /** Decode the 0xFF-terminated string at [off]. Page breaks become '\n'; control codes are dropped. */
    fun decode(bytes: ByteArray, off: Int = 0, maxLen: Int = 1200): String {
        val sb = StringBuilder()
        var i = off
        val end = minOf(bytes.size, off + maxLen)
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == EOS -> return sb.toString()
                b in HANGUL_LEAD_LO..HANGUL_LEAD_HI && i + 1 < end -> {
                    val cp = (b shl 8) or (bytes[i + 1].toInt() and 0xFF)
                    sb.append(codepoints[cp] ?: '□')
                    i += 2
                }
                b == NEWLINE || b == PROMPT_SCROLL || b == PROMPT_CLEAR -> { sb.append('\n'); i++ }
                b == PLACEHOLDER || b == EXTRA_SYMBOL -> i += 2
                b == EXT_CTRL -> {
                    val code = if (i + 1 < end) bytes[i + 1].toInt() and 0xFF else 0
                    i += 1 + (EXT_CTRL_LENGTHS.getOrNull(code) ?: 1)
                }
                else -> { ONE_BYTE[b]?.let { sb.append(it) }; i++ }
            }
        }
        return sb.toString()
    }

    companion object {
        private const val HANGUL_LEAD_LO = 0x37
        private const val HANGUL_LEAD_HI = 0x41
        private const val EOS = 0xFF
        private const val NEWLINE = 0xFE
        private const val PLACEHOLDER = 0xFD
        private const val EXT_CTRL = 0xFC
        private const val PROMPT_CLEAR = 0xFB
        private const val PROMPT_SCROLL = 0xFA
        private const val EXTRA_SYMBOL = 0xF9

        // pokefirered GetExtCtrlCodeLength: bytes after FC, including the code byte.
        private val EXT_CTRL_LENGTHS = intArrayOf(1, 2, 2, 2, 4, 2, 2, 1, 2, 1, 1, 3, 2, 2, 2, 1, 3, 2, 2, 2, 2, 1, 1, 1, 1)

        private val ONE_BYTE: Map<Int, Char> = buildMap {
            put(0x00, ' ')
            "!?.-·…“”‘’♂♀¥,×/".forEachIndexed { k, c -> put(0xAB + k, c) }
            put(0xF0, ':'); put(0x5B, '%'); put(0x5C, '('); put(0x5D, ')'); put(0x85, '<'); put(0x86, '>')
            for (d in 0..9) put(0xA1 + d, '0' + d)
            for (k in 0..25) { put(0xBB + k, 'A' + k); put(0xD5 + k, 'a' + k) }
        }

        fun parse(json: String): KoText2024 {
            val obj = JSONObject(json).getJSONObject("codepoints")
            val map = HashMap<Int, Char>(obj.length())
            for (k in obj.keys()) {
                val v = obj.getString(k)
                if (v.length == 1) map[k.toInt(16)] = v[0]
            }
            return KoText2024(map)
        }

        fun loadFromAssets(context: Context, path: String = "moneo/ko2024_codepoints.json"): KoText2024 =
            parse(context.assets.open(path).use { it.readBytes() }.toString(Charsets.UTF_8))
    }
}
