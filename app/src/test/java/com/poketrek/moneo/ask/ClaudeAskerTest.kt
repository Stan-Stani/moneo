package com.poketrek.moneo.ask

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeAskerTest {

    @Test fun newQuestionStartsFresh() {
        val h = AskHistory<String>()
        h.begin("q1", followUp = false); h.answered("a1")
        assertEquals(listOf("q2"), h.begin("q2", followUp = false))
    }

    @Test fun followUpContinuesTheConversation() {
        val h = AskHistory<String>()
        h.begin("q1", followUp = false); h.answered("a1")
        assertEquals(listOf("q1", "a1", "q2"), h.begin("q2", followUp = true))
    }

    @Test fun failedQuestionIsDroppedBeforeTheNextOne() {
        val h = AskHistory<String>()
        h.begin("q1", followUp = false); h.answered("a1")
        h.begin("q2", followUp = true) // no answer: the call failed
        assertEquals(listOf("q1", "a1", "q3"), h.begin("q3", followUp = true))
    }

    @Test fun userTextReplacesTheScreenshotFileWithAFlag() {
        val request = AskRequest(
            id = "20260928-143005-123",
            question = "뭐라고?",
            message = "간판이다",
            lineId = 7,
            words = emptyList(),
            knownWords = listOf("간판"),
            location = null,
            rom = "LEAFGREEN_KR_2024",
            followUp = false,
            hasScreenshot = true,
            createdMs = 0,
        )
        val text = ClaudeAsker.userText(request)
        val json = JSONObject(text.substringAfter("Request:\n"))
        assertFalse(json.has("screenshot"))
        assertTrue(json.getBoolean("screenshotAttached"))
        assertEquals("간판이다", json.getString("message"))
    }
}
