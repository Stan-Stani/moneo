package com.poketrek.moneo.ask

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Which wire format a direct 💬 endpoint speaks. */
enum class AskProvider(val label: String, val defaultModel: String?) {
    /** Anthropic Messages API: api.anthropic.com, or a gateway in front of it. */
    ANTHROPIC("Claude (Anthropic)", ClaudeAsker.MODEL),

    /**
     * OpenAI Chat Completions, which most other hosts also speak: OpenAI,
     * Azure OpenAI (…/openai/v1), OpenRouter, Ollama, LM Studio, MLX.
     */
    OPENAI("OpenAI-compatible", null);

    companion object {
        fun fromStored(value: String?): AskProvider = entries.firstOrNull { it.name == value } ?: ANTHROPIC
    }
}

/**
 * Where direct 💬 questions go. [baseUrl] and [model] are null for the
 * provider's defaults; [headers] are sent with every request (e.g. an API
 * gateway's subscription key).
 */
data class AskEndpoint(
    val provider: AskProvider,
    val apiKey: String,
    val baseUrl: String? = null,
    val model: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    /** The model to request; OPENAI has no default, so settings require one. */
    val modelOrDefault: String get() = model ?: provider.defaultModel ?: error("no model set for $provider")

    /** Short description for the settings summary, e.g. "OpenAI-compatible · gpt-5.6-sol". */
    val summary: String get() = "${provider.label} · ${model ?: provider.defaultModel}"

    companion object {
        /**
         * The SDK base URL for [url] as typed or pasted. Accepts the full
         * endpoint URL that other tools store (…/v1/messages,
         * …/chat/completions, …/responses) and trims it to what each SDK
         * appends its paths to. Blank means the provider's default (null).
         */
        fun normalizeBaseUrl(provider: AskProvider, url: String?): String? {
            var u = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            if (!u.contains("://")) u = "https://$u"
            val suffixes = when (provider) {
                // anthropic-java appends /v1/messages.
                AskProvider.ANTHROPIC -> listOf("/v1/messages", "/messages", "/v1")
                // OpenAiAsker appends /chat/completions to a …/v1 base.
                AskProvider.OPENAI -> listOf("/chat/completions", "/responses")
            }
            for (s in suffixes) {
                if (u.endsWith(s)) {
                    u = u.removeSuffix(s).trimEnd('/')
                    break
                }
            }
            return u
        }

        /** Parses "Name: value" lines; blank lines and lines without a name are skipped. */
        fun parseHeaders(text: String?): Map<String, String> =
            text.orEmpty().lines().mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) return@mapNotNull null
                val name = line.substring(0, i).trim()
                val value = line.substring(i + 1).trim()
                if (name.isEmpty() || name.any { it.isWhitespace() }) null else name to value
            }.toMap()
    }
}

/** Answers one 💬 question at a time; implemented per [AskProvider]. */
interface Asker {
    val endpoint: AskEndpoint

    /**
     * Returns the answer text, or throws [AskException] with a message fit for
     * the panel. [onText] gets the answer so far as it streams in.
     */
    fun ask(request: AskRequest, screen: Bitmap?, onText: (String) -> Unit = {}): String

    /**
     * Sends a tiny request to check the key, URL and model; returns the model
     * that answered. Throws [AskException] like [ask]. Blocking.
     */
    fun test(): String

    /** Forgets the conversation. */
    fun reset()

    companion object {
        fun create(endpoint: AskEndpoint): Asker = when (endpoint.provider) {
            AskProvider.ANTHROPIC -> ClaudeAsker(endpoint)
            AskProvider.OPENAI -> OpenAiAsker(endpoint)
        }

        fun pngBytes(screen: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
            screen.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }
}

class AskException(message: String, cause: Throwable? = null) : Exception(message, cause)
