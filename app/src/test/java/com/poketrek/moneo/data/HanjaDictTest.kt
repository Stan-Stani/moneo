package com.poketrek.moneo.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HanjaDictTest {

    private val dict = HanjaDict.parse(
        """{"words":{"체육관":{"hanja":"體育館"},"박물관":{"hanja":"博物館"},"도망가다":{"hanja":"逃亡가다"}},
            "chars":{"體":"body","育":"educate","館":"public building","博":"wide","物":"thing","逃":"flee","亡":"perish"}}"""
    )

    @Test fun breakdownAlignsSyllables() {
        val parts = dict.breakdown("체육관")!!
        assertEquals(listOf('體', '育', '館'), parts.map { it.hanja })
        assertEquals("public building", parts[2].meaning)
        assertEquals('관', parts[2].syllable)
    }

    @Test fun nativeSyllablesHaveNoHanja() {
        val parts = dict.breakdown("도망가다")!!
        assertEquals(listOf('逃', '亡', null, null), parts.map { it.hanja })
        assertEquals('가', parts[2].syllable)
    }

    @Test fun unknownWordHasNoBreakdown() {
        assertNull(dict.breakdown("가다"))
    }

    @Test fun wordsSharingACharacter() {
        assertEquals(listOf("박물관"), dict.wordsWith('館', except = "체육관"))
    }

    /** Every shipped word is syllable-aligned and every character has a meaning. */
    @Test fun shippedAssetIsConsistent() {
        val root = JSONObject(File("src/main/assets/moneo/hanja.json").readText())
        val words = root.getJSONObject("words")
        val chars = root.getJSONObject("chars")
        assertTrue(words.length() > 500)
        for (k in words.keys()) {
            val w = words.getJSONObject(k)
            val h = w.getString("hanja")
            assertEquals("length of $k/$h", k.length, h.length)
            assertTrue("$k has a hanja character", h.indices.any { h[it] != k[it] })
            h.indices.filter { h[it] != k[it] }.forEach { i ->
                assertTrue("meaning for ${h[i]} in $k", chars.optString(h[i].toString()).isNotBlank())
            }
            assertTrue("$k pickedBy", w.getString("pickedBy").let { it == "dict" || it.startsWith("llm-") })
        }
    }
}
