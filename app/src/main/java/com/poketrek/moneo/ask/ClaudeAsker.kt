package com.poketrek.moneo.ask

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import java.time.Duration

/**
 * Answers 💬 questions by calling the Claude API directly with the player's
 * own API key (or through a gateway at [AskEndpoint.baseUrl]): the
 * Termux-free alternative to the ask folder. Sends the same
 * [AskRequest] JSON the folder watcher gets, with the screenshot attached as
 * an image, under the same tutor instructions ([AskPrompt.SYSTEM_PROMPT]).
 *
 * A follow-up continues the conversation; any other question starts a new
 * one. Blocking; call off the main thread, one question at a time.
 */
class ClaudeAsker(override val endpoint: AskEndpoint) : Asker {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(endpoint.apiKey)
        .apply { endpoint.baseUrl?.let { baseUrl(it) } }
        .apply { endpoint.headers.forEach { (name, value) -> putHeader(name, value) } }
        .timeout(Duration.ofSeconds(120))
        .build()

    private val history = AskHistory<MessageParam>()

    override fun ask(request: AskRequest, screen: Bitmap?): String {
        val blocks = buildList {
            add(ContentBlockParam.ofText(AskPrompt.userText(request)))
            if (screen != null) add(ContentBlockParam.ofImage(pngBlock(screen)))
        }
        val turn = MessageParam.builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(blocks)
            .build()
        val messages = history.begin(turn, request.followUp)

        val params = MessageCreateParams.builder()
            .model(endpoint.modelOrDefault)
            .maxTokens(4096L)
            .system(AskPrompt.SYSTEM_PROMPT)
            // Short tutoring answers: low effort keeps them quick while walking.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .messages(messages)
            .apply {
                // Server-side fallback: a refused request is retried on a model
                // chosen by the refusal's category instead of just stopping.
                // Only on Anthropic's own API; a gateway may not pass the beta on.
                if (endpoint.baseUrl == null) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw AskException("The API key was rejected. Check it in Settings → Ask an LLM.", e)
        } catch (e: RateLimitException) {
            throw AskException("Rate limited by the Claude API. Try again in a moment.", e)
        } catch (e: AnthropicServiceException) {
            throw AskException("Claude API error ${e.statusCode()}: ${e.message}", e)
        } catch (e: AnthropicIoException) {
            throw AskException("Couldn't reach the Claude API. Are you online?", e)
        }

        if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
            throw AskException("Claude declined to answer this one.")
        }
        val text = response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("\n")
            .trim()
        if (text.isEmpty()) throw AskException("Claude sent an empty answer.")
        // Keep the whole assistant turn (thinking blocks included) so a
        // follow-up replays it unchanged.
        history.answered(response.toParam())
        return text
    }

    override fun reset() = history.clear()

    private fun pngBlock(screen: Bitmap): ImageBlockParam {
        val png = Asker.pngBytes(screen)
        return ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                    .data(Base64.encodeToString(png, Base64.NO_WRAP))
                    .build(),
            )
            .build()
    }

    companion object {
        /** The default model on Anthropic's API. */
        const val MODEL = "claude-opus-5-5"
    }
}
