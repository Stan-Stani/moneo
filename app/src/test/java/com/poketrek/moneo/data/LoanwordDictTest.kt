package com.poketrek.moneo.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoanwordDictTest {
    @Test fun parsesWholeAndPartialLoans() {
        val d = LoanwordDict.parse(
            """{"words":{"시티":{"part":"시티","source":"city","lang":"English"},
               "불꽃펀치":{"part":"펀치","source":"punch","lang":"English"}}}"""
        )
        assertEquals(LoanwordDict.Loan("시티", "city", "English"), d.lookup("시티"))
        assertEquals("펀치", d.lookup("불꽃펀치")!!.part)
        assertNull(d.lookup("마을"))
    }

    /** Every shipped entry's borrowed part is really inside the word, and none is also hanja. */
    @Test fun shippedAssetIsConsistent() {
        val words = JSONObject(File("src/main/assets/moneo/loanwords.json").readText()).getJSONObject("words")
        val hanja = JSONObject(File("src/main/assets/moneo/hanja.json").readText()).getJSONObject("words")
        assertTrue(words.length() > 150)
        for (k in words.keys()) {
            val w = words.getJSONObject(k)
            assertTrue("$k contains ${w.getString("part")}", k.contains(w.getString("part")))
            assertTrue("$k source", w.getString("source").isNotBlank())
            assertTrue("$k is not also a hanja word", !hanja.has(k))
        }
    }
}
