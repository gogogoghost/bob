package cc.jaxy.anlobehub.core.data.chat

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Response

/** Read the actual wire protocol, including providers that do not support SSE. */
internal suspend fun executeProviderRequest(
    client: OkHttpClient,
    prepared: PreparedChatRequest,
    emit: suspend (ChatStreamChunk) -> Unit,
) = coroutineScope {
    val call = client.newCall(prepared.request)
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            try {
                call.execute().use { response -> readProviderResponse(response, prepared.protocol, prepared.webSearchEnabled, emit) }
            } catch (error: IOException) {
                // Closing a cancelled call must remain coroutine cancellation, not socket failure.
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
    } finally {
        cancellation.cancel()
    }
}

private suspend fun readProviderResponse(response: Response, protocol: String, webSearchEnabled: Boolean, emit: suspend (ChatStreamChunk) -> Unit) {
    val body = response.body
    if (!response.isSuccessful) throw IOException("Provider returned HTTP ${response.code}: ${body.string().take(1500)}")
    val completion = ProviderCompletionState(protocol)
    val search = if (webSearchEnabled && protocol == "responses") NativeWebSearchParser() else null
    var receivedText = false
    var receivedContent = false
    var receivedContext = false
    suspend fun consume(obj: JsonObject, event: String = "", complete: Boolean = false) {
        // Recover snapshot-only text without duplicating previously streamed reasoning or opaque items.
        val terminalResponse = protocol == "responses" && (obj.string("type") ?: event) in RESPONSE_END_EVENTS
        val snapshot = if (terminalResponse && !receivedText) obj["response"] as? JsonObject else null
        val contentChunk = if (snapshot != null) providerChunk(protocol, snapshot, event, complete = true)?.let {
            it.copy(reasoningDelta = if (receivedContent) null else it.reasoningDelta,
                providerContext = if (receivedContext) emptyList() else it.providerContext)
        } else providerChunk(protocol, obj, event, complete)
        val error = providerError(obj)
        val chunk = search?.consume(obj, event, complete, contentChunk, error) ?: contentChunk
        val hasContent = chunk != null && (chunk.textDelta.isNotEmpty() || !chunk.reasoningDelta.isNullOrEmpty() || chunk.providerContext.isNotEmpty())
        if (chunk != null) {
            receivedText = receivedText || chunk.textDelta.isNotBlank()
            receivedContent = receivedContent || chunk.textDelta.isNotEmpty() || !chunk.reasoningDelta.isNullOrEmpty()
            receivedContext = receivedContext || chunk.providerContext.isNotEmpty()
            if (hasContent || chunk.searchActivity != null || chunk.citations.isNotEmpty()) emit(chunk)
        }
        error?.let {
            throw ChatStreamException(ChatCompletion(ChatCompletionStatus.FAILED, obj.string("type") ?: event.takeIf(String::isNotBlank)), it)
        }
        completion.observe(obj, event, complete, hasContent)
    }
    val source = body.source()
    val subtype = body.contentType()?.subtype.orEmpty()
    val completeJson = subtype == "json" || subtype.endsWith("+json")
    when {
        protocol == "bedrock" -> for (event in readBedrockEvents(source)) {
            consume(event)
        }
        completeJson -> consume(parseObject(body.string()), complete = true)
        protocol == "ollama" -> while (!source.exhausted()) {
            val line = source.readUtf8Line()?.trim().orEmpty()
            if (line.isNotEmpty()) consume(parseObject(line))
        }
        else -> {
            var event = ""
            val data = StringBuilder()
            suspend fun dispatch(): Boolean {
                if (data.isEmpty()) {
                    if (event.isNotEmpty()) completion.observe(JsonObject(emptyMap()), event, complete = false)
                    event = ""
                    return !completion.ended
                }
                val value = data.toString()
                data.setLength(0)
                if (value.trim() == "[DONE]") {
                    completion.doneMarker()
                    return false
                }
                consume(parseObject(value), event)
                event = ""
                return !completion.ended
            }
            while (!source.exhausted()) {
                val line = source.readUtf8Line()?.removePrefix("\uFEFF") ?: break
                if (line.isEmpty()) {
                    if (!dispatch()) break
                } else if (line.startsWith("event:")) {
                    event = line.substringAfter(':').trim()
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter(':').removePrefix(" "))
                }
            }
            if (data.isNotEmpty() || event.isNotEmpty()) dispatch()
        }
    }
    val result = completion.result()
    if (result.status == ChatCompletionStatus.COMPLETED && !receivedText) {
        throw ChatStreamException(ChatCompletion(ChatCompletionStatus.FAILED, result.finishReason), "Provider ended without a final assistant answer")
    }
    emit(ChatStreamChunk(completion = result))
}

private val responseJson = Json { ignoreUnknownKeys = true }
private fun parseObject(value: String): JsonObject = try {
    responseJson.parseToJsonElement(value) as? JsonObject ?: throw IOException("Provider returned a non-object response")
} catch (e: IllegalArgumentException) { throw IOException("Provider returned invalid JSON", e) }

private fun providerError(obj: JsonObject): String? {
    val business = obj["base_resp"] as? JsonObject ?: obj["header"] as? JsonObject
    val code = business?.string("status_code") ?: business?.string("code") ?: obj.string("code")
    if (code != null && code != "0" && code != "200") return business?.string("status_msg") ?: business?.string("message") ?: obj.string("message") ?: "Provider error $code"
    obj["error"]?.takeUnless { it is JsonNull }?.let { error ->
        return (error as? JsonObject)?.string("message") ?: (error as? JsonPrimitive)?.contentOrNull ?: error.toString()
    }
    if (obj.string("type") == "response.failed" || obj.string("status") == "failed") {
        val result = obj["response"] as? JsonObject ?: obj
        return (result["error"] as? JsonObject)?.string("message") ?: "Provider failed to generate a response"
    }
    return null
}

private fun providerChunk(protocol: String, obj: JsonObject, event: String, complete: Boolean): ChatStreamChunk? = when (protocol) {
    "anthropic" -> {
        val delta = obj["delta"] as? JsonObject
        when (delta?.string("type") ?: obj.string("type") ?: event) {
            "text_delta" -> ChatStreamChunk(delta?.string("text").orEmpty())
            "thinking_delta" -> ChatStreamChunk(reasoningDelta = delta?.string("thinking"))
            "content_block_start" -> anthropicContent(JsonArray(listOfNotNull(obj["content_block"])))
            else -> if (complete) anthropicContent(obj["content"] as? JsonArray) else null
        }
    }
    "gemini" -> {
        val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray
        val text = StringBuilder(); val reasoning = StringBuilder()
        parts?.forEach { part ->
            val item = part as? JsonObject ?: return@forEach
            if (item["thought"]?.jsonPrimitive?.booleanOrNull == true) reasoning.append(item.string("text").orEmpty())
            else text.append(item.string("text").orEmpty())
        }
        ChatStreamChunk(text.toString(), reasoning.toString().takeIf(String::isNotEmpty))
    }
    "responses" -> when (obj.string("type") ?: event) {
        "response.output_text.delta" -> ChatStreamChunk(obj.string("delta").orEmpty())
        "response.refusal.delta" -> ChatStreamChunk(obj.string("delta").orEmpty())
        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> ChatStreamChunk(reasoningDelta = obj.string("delta"))
        "response.output_item.done" -> {
            val item = obj["item"] as? JsonObject
            if (item?.string("type") == "reasoning" && !item.string("encrypted_content").isNullOrBlank()) ChatStreamChunk(providerContext = listOf(item)) else null
        }
        else -> if (complete) {
            val text = StringBuilder(); val reasoning = StringBuilder()
            val context = mutableListOf<JsonObject>()
            (obj["output"] as? JsonArray)?.forEach { output ->
                val item = output as? JsonObject ?: return@forEach
                if (item.string("type") == "reasoning" && !item.string("encrypted_content").isNullOrBlank()) context += item
                (item["content"] as? JsonArray)?.forEach contentPart@ { part ->
                    val content = part as? JsonObject ?: return@contentPart
                    text.append(content.string("text") ?: content.string("refusal").orEmpty())
                }
                (item["summary"] as? JsonArray)?.forEach { part -> reasoning.append((part as? JsonObject)?.string("text").orEmpty()) }
            }
            ChatStreamChunk(text.toString(), reasoning.toString().takeIf(String::isNotEmpty), context)
        } else null
    }
    "bedrock" -> {
        val delta = obj["delta"] as? JsonObject
        ChatStreamChunk(delta?.string("text").orEmpty(), (delta?.get("reasoningContent") as? JsonObject)?.string("text"))
    }
    "ollama" -> {
        val message = obj["message"] as? JsonObject
        ChatStreamChunk(message?.string("content").orEmpty(), message?.string("thinking"))
    }
    "completions" -> ChatStreamChunk(((obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("text").orEmpty())
    else -> {
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = (choice?.get(if (complete) "message" else "delta") as? JsonObject)
        val content = delta?.get("content")
        val text = if (content is JsonArray) content.joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }
            else (content as? JsonPrimitive)?.contentOrNull ?: delta?.string("refusal").orEmpty()
        val details = (delta?.get("reasoning_details") as? JsonArray)?.joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }?.takeIf(String::isNotEmpty)
        ChatStreamChunk(text, delta?.string("reasoning_content") ?: delta?.string("reasoning") ?: details)
    }
}

private fun anthropicContent(parts: JsonArray?): ChatStreamChunk {
    val text = StringBuilder(); val reasoning = StringBuilder()
    parts?.forEach { part ->
        val block = part as? JsonObject ?: return@forEach
        when (block.string("type")) {
            "text" -> text.append(block.string("text").orEmpty())
            "thinking" -> reasoning.append(block.string("thinking").orEmpty())
        }
    }
    return ChatStreamChunk(text.toString(), reasoning.toString().takeIf(String::isNotEmpty))
}

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull


private val RESPONSE_END_EVENTS = setOf("response.completed", "response.incomplete", "response.failed")

/** Receiving text is not proof that generation completed: each protocol has a terminal signal. */
private class ProviderCompletionState(private val protocol: String) {
    var ended: Boolean = false
        private set
    private var completion: ChatCompletion? = null
    private var contentAfterFinish = false

    fun observe(obj: JsonObject, event: String, complete: Boolean, hasContent: Boolean = false) {
        if (hasContent && completion != null) contentAfterFinish = true
        when (protocol) {
            "anthropic" -> {
                val delta = obj["delta"] as? JsonObject
                val reason = delta?.string("stop_reason") ?: obj.string("stop_reason")
                if (reason != null) completion = fromReason(reason)
                if ((obj.string("type") ?: event) == "message_stop" || complete) {
                    ended = true
                    if (completion == null) completion = success(reason ?: "message_stop")
                }
            }
            "responses" -> {
                val type = obj.string("type") ?: event
                val response = obj["response"] as? JsonObject ?: obj
                val status = response.string("status")
                if (type in RESPONSE_END_EVENTS || complete) {
                    ended = true
                    val reason = (response["incomplete_details"] as? JsonObject)?.string("reason")
                    completion = when {
                        status == "incomplete" || type == "response.incomplete" -> reason?.let(::fromReason)
                            ?: ChatCompletion(ChatCompletionStatus.FAILED, "incomplete")
                        status == "failed" || type == "response.failed" -> ChatCompletion(ChatCompletionStatus.FAILED, reason ?: "failed")
                        status == "cancelled" -> ChatCompletion(ChatCompletionStatus.INTERRUPTED, status)
                        status == "queued" || status == "in_progress" -> ChatCompletion(ChatCompletionStatus.INTERRUPTED, status)
                        else -> success(status ?: "response.completed")
                    }
                }
            }
            "gemini" -> {
                val block = (obj["promptFeedback"] as? JsonObject)?.string("blockReason")
                val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                val reason = candidate?.string("finishReason")
                if (block != null) {
                    completion = ChatCompletion(ChatCompletionStatus.CONTENT_FILTERED, block)
                    contentAfterFinish = false
                } else if (reason != null && reason != "FINISH_REASON_UNSPECIFIED") {
                    val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                    completion = if (parts?.any { (it as? JsonObject)?.containsKey("functionCall") == true } == true)
                        ChatCompletion(ChatCompletionStatus.TOOL_CALL_REQUIRED, reason) else fromReason(reason)
                    contentAfterFinish = false
                } else if (complete) {
                    completion = success("json")
                }
                if (complete) ended = true
            }
            "ollama" -> if ((obj["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                completion = fromReason(obj.string("done_reason") ?: "stop")
                ended = true
            }
            "bedrock" -> if (obj.string("type") == "messageStop") {
                completion = fromReason(obj.string("stopReason") ?: "end_turn")
                ended = true
            }
            else -> {
                val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                val reason = choice?.string("finish_reason")
                if (reason != null) {
                    completion = fromReason(reason)
                    // A choice finish reason is metadata, not the SSE terminator.
                    contentAfterFinish = false
                } else if (complete) {
                    completion = success("json")
                }
                if (complete) ended = true
            }
        }
    }

    fun doneMarker() {
        if (protocol == "openai" || protocol == "completions" || protocol == "straico") {
            if (completion == null) completion = success("[DONE]")
            ended = true
        }
    }

    fun result(): ChatCompletion {
        // Some compatible servers close without [DONE]; only a final, non-stale reason suffices.
        val finalReasonAtEof = completion != null && !contentAfterFinish &&
            (protocol == "openai" || protocol == "completions" || protocol == "straico" || protocol == "gemini")
        if (!ended && !finalReasonAtEof) throw ChatStreamException(
            ChatCompletion(ChatCompletionStatus.INTERRUPTED, completion?.finishReason),
            "Provider stream ended before its completion signal",
        )
        return checkNotNull(completion)
    }
}

private fun success(reason: String) = ChatCompletion(ChatCompletionStatus.COMPLETED, reason)
private fun fromReason(reason: String): ChatCompletion = ChatCompletion(
    when (reason) {
        "stop", "STOP", "end_turn", "stop_sequence", "eos_token", "completed" -> ChatCompletionStatus.COMPLETED
        "length", "max_tokens", "max_output_tokens", "MAX_TOKENS", "model_length", "model_context_window_exceeded", "context_length_exceeded" -> ChatCompletionStatus.OUTPUT_LIMIT
        "content_filter", "content_filtered", "guardrail_intervened", "refusal", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> ChatCompletionStatus.CONTENT_FILTERED
        "tool_calls", "function_call", "tool_use", "pause_turn", "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" -> ChatCompletionStatus.TOOL_CALL_REQUIRED
        "cancelled", "canceled", "abort" -> ChatCompletionStatus.INTERRUPTED
        else -> ChatCompletionStatus.FAILED
    },
    reason,
)
