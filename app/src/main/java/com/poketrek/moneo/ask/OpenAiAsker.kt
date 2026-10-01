package com.poketrek.moneo.ask

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.util.Base64

/**
 * Answers 💬 questions through an OpenAI-compatible Chat Completions API:
 * OpenAI itself, Azure AI Foundry's v1 endpoint, OpenRouter, or a local server
 * (Ollama, LM Studio, MLX). Same request and prompt as [ClaudeAsker], with the
 * screenshot as a data-URL image part; vision-less models may reject it.
 *
 * Plain OkHttp + org.json rather than an SDK: it's one POST in a format every
 * one of those hosts copies, and the OpenAI SDK would add tens of MB.
 */
class OpenAiAsker(
    override val endpoint: AskEndpoint,
    private val http: OkHttpClient = defaultHttp,
) : Asker {

    private val history = AskHistory<JSONObject>()

    override fun ask(request: AskRequest, screen: Bitmap?): String =
        askPng(request, screen?.let(Asker::pngBytes))

    /** [ask] with the screenshot already encoded; the JVM-testable part. */
    internal fun askPng(request: AskRequest, png: ByteArray?): String {
        val messages = history.begin(userMessage(request, png), request.followUp)
        val body = JSONObject()
            .put("model", endpoint.modelOrDefault)
            .put("messages", JSONArray().put(systemMessage).apply { messages.forEach { put(it) } })
        val text = post(body)

        val message = try {
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        } catch (e: JSONException) {
            throw AskException("The API sent an answer this app can't read.", e)
        }
        if (!message.isNull("refusal")) throw AskException("The model declined: ${message.getString("refusal")}")
        val answer = if (message.isNull("content")) "" else message.getString("content").trim()
        if (answer.isEmpty()) throw AskException("The model sent an empty answer.")
        history.answered(JSONObject().put("role", "assistant").put("content", answer))
        return answer
    }

    override fun test(): String {
        val body = JSONObject()
            .put("model", endpoint.modelOrDefault)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK.")))
        val text = post(body)
        return runCatching { JSONObject(text).getString("model") }.getOrDefault(endpoint.modelOrDefault)
    }

    override fun reset() = history.clear()

    /** POSTs [body] to chat/completions; returns the response text or throws [AskException]. */
    private fun post(body: JSONObject): String {
        val call = Request.Builder()
            .url(chatCompletionsUrl(endpoint.baseUrl))
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            // After Authorization, so a gateway can swap in its own auth header.
            .apply { endpoint.headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody(JSON))
            .build()
        val (code, text) = try {
            http.newCall(call).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw AskException("Couldn't reach ${endpoint.baseUrl ?: "the OpenAI API"}. Are you online?", e)
        }
        if (code !in 200..299) throw AskException(errorMessage(code, text))
        return text
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).getJSONObject("error").getString("message") }
            .getOrElse { body.take(200) }
        return when (code) {
            401, 403 -> "The API key was rejected. Check it in Settings → Ask an LLM."
            404 -> "Not found (404): check the base URL and model \"${endpoint.modelOrDefault}\"."
            429 -> "Rate limited by the API. Try again in a moment."
            else -> "API error $code: $detail"
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private val JSON = "application/json".toMediaType()

        private val defaultHttp: OkHttpClient by lazy {
            // Reasoning models can think for a while before the first byte.
            OkHttpClient.Builder()
                .readTimeout(Duration.ofSeconds(120))
                .callTimeout(Duration.ofSeconds(150))
                .build()
        }

        private val systemMessage get() = JSONObject().put("role", "system").put("content", AskPrompt.SYSTEM_PROMPT)

        fun chatCompletionsUrl(baseUrl: String?): String = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/') + "/chat/completions"

        /** The user turn: the request JSON as text, then the screenshot as a data URL. */
        fun userMessage(request: AskRequest, png: ByteArray?): JSONObject {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", AskPrompt.userText(request)))
            if (png != null) {
                val url = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
                parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
            }
            return JSONObject().put("role", "user").put("content", parts)
        }
    }
}
