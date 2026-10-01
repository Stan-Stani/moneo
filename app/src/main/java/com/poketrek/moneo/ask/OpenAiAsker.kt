package com.poketrek.moneo.ask

import android.graphics.Bitmap
import android.util.Base64
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.NotFoundException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import java.time.Duration

/**
 * Answers 💬 questions through an OpenAI-compatible Chat Completions API:
 * OpenAI itself, Azure OpenAI's v1 endpoint, OpenRouter, or a local server
 * (Ollama, LM Studio, MLX). Same request and prompt as [ClaudeAsker], with the
 * screenshot as a data-URL image part; vision-less models may reject it.
 */
class OpenAiAsker(override val endpoint: AskEndpoint) : Asker {

    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(endpoint.apiKey)
        .apply { endpoint.baseUrl?.let { baseUrl(it) } }
        .apply { endpoint.headers.forEach { (name, value) -> putHeader(name, value) } }
        .timeout(Duration.ofSeconds(120))
        .build()

    private val history = AskHistory<ChatCompletionMessageParam>()

    override fun ask(request: AskRequest, screen: Bitmap?): String {
        val parts = buildList {
            add(ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text(AskPrompt.userText(request)).build(),
            ))
            if (screen != null) add(ChatCompletionContentPart.ofImageUrl(imagePart(screen)))
        }
        val turn = ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().contentOfArrayOfContentParts(parts).build(),
        )
        val messages = history.begin(turn, request.followUp)

        val params = ChatCompletionCreateParams.builder()
            .model(endpoint.modelOrDefault)
            .addSystemMessage(AskPrompt.SYSTEM_PROMPT)
            .apply { messages.forEach { addMessage(it) } }
            .build()

        val response = try {
            client.chat().completions().create(params)
        } catch (e: UnauthorizedException) {
            throw AskException("The API key was rejected. Check it in Settings → Ask an LLM.", e)
        } catch (e: NotFoundException) {
            throw AskException("Not found (404): check the base URL and model \"${endpoint.modelOrDefault}\".", e)
        } catch (e: RateLimitException) {
            throw AskException("Rate limited by the API. Try again in a moment.", e)
        } catch (e: OpenAIServiceException) {
            throw AskException("API error ${e.statusCode()}: ${e.message}", e)
        } catch (e: OpenAIIoException) {
            throw AskException("Couldn't reach ${endpoint.baseUrl ?: "the OpenAI API"}. Are you online?", e)
        }

        val message = response.choices().firstOrNull()?.message()
            ?: throw AskException("The API sent no answer.")
        message.refusal().orElse(null)?.let { throw AskException("The model declined: $it") }
        val text = message.content().orElse("").trim()
        if (text.isEmpty()) throw AskException("The model sent an empty answer.")
        history.answered(ChatCompletionMessageParam.ofAssistant(message.toParam()))
        return text
    }

    override fun reset() = history.clear()

    private fun imagePart(screen: Bitmap): ChatCompletionContentPartImage {
        val png = Base64.encodeToString(Asker.pngBytes(screen), Base64.NO_WRAP)
        return ChatCompletionContentPartImage.builder()
            .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url("data:image/png;base64,$png").build())
            .build()
    }
}
