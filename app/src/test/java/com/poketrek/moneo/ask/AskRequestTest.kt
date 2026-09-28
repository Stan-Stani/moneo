package com.poketrek.moneo.ask

import com.poketrek.moneo.reading.ReadingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskRequestTest {

    private fun request(message: String?, screenshot: Boolean) = AskRequest(
        id = "20260928-143005-123",
        question = "Explain this line in simpler Korean.",
        message = message,
        lineId = message?.let { 42 },
        words = listOf(ReadingRow("간판", "sign", "mined:간판", known = true), ReadingRow("도움", "help", null, known = false)),
        knownWords = listOf("간판"),
        location = "map 3:0 (pallet_town)",
        rom = "LEAFGREEN_KR_2024",
        followUp = false,
        hasScreenshot = screenshot,
        createdMs = 0,
    )

    @Test fun jsonCarriesTheMessageAndWords() {
        val j = request("간판은 도움이 되지!", screenshot = true).toJson()
        assertEquals("간판은 도움이 되지!", j.getString("message"))
        assertEquals(42, j.getInt("lineId"))
        assertEquals("20260928-143005-123.png", j.getString("screenshot"))
        val w = j.getJSONArray("words").getJSONObject(1)
        assertEquals("도움", w.getString("korean"))
        assertFalse(w.getBoolean("known"))
        assertEquals("간판", j.getJSONArray("knownWords").getString(0))
    }

    @Test fun missingMessageAndScreenshotAreJsonNull() {
        val j = request(null, screenshot = false).toJson()
        assertTrue(j.isNull("message"))
        assertTrue(j.isNull("lineId"))
        assertTrue(j.isNull("screenshot"))
    }

    @Test fun followUpOnlyForTheSameMessage() {
        assertFalse(AskRequest.isFollowUp(null, hadPrevious = false, message = "가"))
        assertTrue(AskRequest.isFollowUp("가", hadPrevious = true, message = "가"))
        assertFalse(AskRequest.isFollowUp("가", hadPrevious = true, message = "나"))
        // No box open for either question: same screen, keep the conversation.
        assertTrue(AskRequest.isFollowUp(null, hadPrevious = true, message = null))
    }

    @Test fun idsSortByTime() {
        val a = AskRequest.newId(1_000_000_000_000)
        val b = AskRequest.newId(1_000_000_000_001)
        assertTrue(a < b)
        assertTrue(Regex("""\d{8}-\d{6}-\d{3}""").matches(a))
    }
}
