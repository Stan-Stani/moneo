package com.poketrek.moneo.ask

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskTest {

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
        val text = AskPrompt.userText(request)
        val json = JSONObject(text.substringAfter("Request:\n"))
        assertFalse(json.has("screenshot"))
        assertTrue(json.getBoolean("screenshotAttached"))
        assertEquals("간판이다", json.getString("message"))
    }

    @Test fun baseUrlBlankMeansDefault() {
        assertNull(AskEndpoint.normalizeBaseUrl(AskProvider.OPENAI, "  "))
        assertNull(AskEndpoint.normalizeBaseUrl(AskProvider.ANTHROPIC, null))
    }

    @Test fun openAiBaseUrlAcceptsFullEndpointUrls() {
        val base = "https://x.services.ai.azure.com/openai/v1"
        assertEquals(base, AskEndpoint.normalizeBaseUrl(AskProvider.OPENAI, "$base/responses"))
        assertEquals(base, AskEndpoint.normalizeBaseUrl(AskProvider.OPENAI, "$base/chat/completions/"))
        assertEquals(base, AskEndpoint.normalizeBaseUrl(AskProvider.OPENAI, base))
        assertEquals("http://localhost:11434/v1", AskEndpoint.normalizeBaseUrl(AskProvider.OPENAI, "http://localhost:11434/v1"))
    }

    @Test fun anthropicBaseUrlDropsTheSdkPath() {
        val gw = "https://gw.azure-api.net/anthropic"
        assertEquals(gw, AskEndpoint.normalizeBaseUrl(AskProvider.ANTHROPIC, "$gw/v1/messages"))
        assertEquals(gw, AskEndpoint.normalizeBaseUrl(AskProvider.ANTHROPIC, "$gw/v1"))
        assertEquals(gw, AskEndpoint.normalizeBaseUrl(AskProvider.ANTHROPIC, "gw.azure-api.net/anthropic"))
    }

    @Test fun headersParseNameValueLines() {
        val h = AskEndpoint.parseHeaders("Ocp-Apim-Subscription-Key: abc:def\n\n  X-Trace :  1 \nnot a header\nbad name: x")
        assertEquals(mapOf("Ocp-Apim-Subscription-Key" to "abc:def", "X-Trace" to "1"), h)
    }

    @Test fun openAiHasNoDefaultModel() {
        assertEquals("claude-opus-5-5", AskEndpoint(AskProvider.ANTHROPIC, "k").modelOrDefault)
        assertEquals("gpt-5.6-sol", AskEndpoint(AskProvider.OPENAI, "k", model = "gpt-5.6-sol").modelOrDefault)
    }
}
