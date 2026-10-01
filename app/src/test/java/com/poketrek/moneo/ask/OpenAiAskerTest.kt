package com.poketrek.moneo.ask

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiAskerTest {

    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun asker(headers: Map<String, String> = emptyMap()) = OpenAiAsker(
        AskEndpoint(AskProvider.OPENAI, "sk-test", baseUrl = server.url("/openai/v1").toString(), model = "gpt-test", headers = headers),
    )

    private fun request(question: String, followUp: Boolean) = AskRequest(
        id = "20261001-120000-000",
        question = question,
        message = "간판은 도움이 되지!",
        lineId = 7,
        words = emptyList(),
        knownWords = emptyList(),
        location = null,
        rom = "LEAFGREEN_KR_2024",
        followUp = followUp,
        hasScreenshot = true,
        createdMs = 0,
    )

    private fun answer(text: String) = MockResponse().setBody(
        """{"choices":[{"index":0,"message":{"role":"assistant","content":${JSONObject.quote(text)},"refusal":null}}]}""",
    )

    @Test fun sendsChatCompletionWithScreenshotAndHeaders() {
        server.enqueue(answer("  간판은 도움이 돼요.  "))
        val reply = asker(mapOf("X-Gateway" to "g1")).askPng(request("쉽게", false), byteArrayOf(1, 2, 3))
        assertEquals("간판은 도움이 돼요.", reply)

        val sent = server.takeRequest()
        assertEquals("/openai/v1/chat/completions", sent.path)
        assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
        assertEquals("g1", sent.getHeader("X-Gateway"))
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("gpt-test", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertTrue(parts.getJSONObject(0).getString("text").startsWith("Request:\n"))
        assertEquals("data:image/png;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun followUpReplaysTheConversation() {
        server.enqueue(answer("a1"))
        server.enqueue(answer("a2"))
        val a = asker()
        a.askPng(request("q1", false), null)
        a.askPng(request("q2", true), null)
        server.takeRequest()
        val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertEquals("a1", messages.getJSONObject(2).getString("content"))
    }

    @Test fun headerCanReplaceAuthorization() {
        server.enqueue(answer("ok"))
        asker(mapOf("Authorization" to "Custom x")).askPng(request("q", false), null)
        assertEquals("Custom x", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun errorsBecomePanelMessages() {
        fun messageFor(response: MockResponse): String {
            server.enqueue(response)
            return try {
                asker().askPng(request("q", false), null); fail("no exception"); ""
            } catch (e: AskException) {
                e.message!!
            }
        }
        assertTrue(messageFor(MockResponse().setResponseCode(401)).contains("key was rejected"))
        assertTrue(messageFor(MockResponse().setResponseCode(404)).contains("gpt-test"))
        assertEquals(
            "API error 400: bad image",
            messageFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad image"}}""")),
        )
        assertTrue(messageFor(MockResponse().setBody("""{"choices":[{"message":{"content":null,"refusal":"no"}}]}""")).contains("declined"))
        assertTrue(messageFor(MockResponse().setBody("not json")).contains("can't read"))
    }

    @Test fun testSendsATinyRequestAndReturnsTheModel() {
        server.enqueue(MockResponse().setBody("""{"model":"gpt-test-2026","choices":[{"message":{"content":"OK"}}]}"""))
        assertEquals("gpt-test-2026", asker().test())
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(1, body.getJSONArray("messages").length())
        server.enqueue(MockResponse().setResponseCode(401))
        try { asker().test(); fail("no exception") } catch (e: AskException) { assertTrue(e.message!!.contains("rejected")) }
    }

    @Test fun defaultUrlIsOpenAi() {
        assertEquals("https://api.openai.com/v1/chat/completions", OpenAiAsker.chatCompletionsUrl(null))
    }
}
