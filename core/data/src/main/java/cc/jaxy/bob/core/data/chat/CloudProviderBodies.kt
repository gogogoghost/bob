package cc.jaxy.bob.core.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun JsonObject.cloudText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun cloudMessages(body: JsonObject): List<JsonObject> =
    (body["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun textPart(text: String): JsonObject = buildJsonObject { put("text", text) }

private data class CloudImage(val mimeType: String, val data: String)

private fun cloudImage(part: JsonObject): CloudImage {
    val url = (part["image_url"] as? JsonObject)?.cloudText("url").orEmpty()
    require(url.startsWith("data:") && url.substringBefore(',').endsWith(";base64")) {
        "This cloud adapter requires base64 data-URL image attachments"
    }
    return CloudImage(url.substringAfter("data:").substringBefore(';'), url.substringAfter(','))
}

private fun contentParts(message: JsonObject): List<JsonObject> = when (val content = message["content"]) {
    is JsonArray -> content.mapNotNull { it as? JsonObject }
    is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", content.content) })
    else -> emptyList()
}

private fun systemText(body: JsonObject): String = cloudMessages(body)
    .filter { it.cloudText("role") in listOf("system", "developer") }
    .flatMap(::contentParts).mapNotNull { it.cloudText("text") }.joinToString("\n\n")

/** Converts only canonical chat fields; signed provider reasoning blocks cannot be reconstructed from text. */
internal fun cloudGeminiBody(body: JsonObject, modelId: String): JsonObject = buildJsonObject {
    val system = systemText(body)
    if (system.isNotEmpty()) put("systemInstruction", buildJsonObject {
        put("parts", JsonArray(listOf(textPart(system))))
    })
    put("contents", JsonArray(cloudMessages(body).filter { it.cloudText("role") !in listOf("system", "developer") }.map { message ->
        buildJsonObject {
            put("role", if (message.cloudText("role") == "assistant") "model" else "user")
            put("parts", JsonArray(contentParts(message).map { part ->
                when (part.cloudText("type")) {
                    "image_url" -> {
                        val image = cloudImage(part)
                        buildJsonObject { put("inlineData", buildJsonObject { put("mimeType", image.mimeType); put("data", image.data) }) }
                    }
                    else -> textPart(part.cloudText("text").orEmpty())
                }
            }))
        }
    }))
    val generation = buildJsonObject {
        body["temperature"]?.let { put("temperature", it) }
        body["top_p"]?.let { put("topP", it) }
        (body["max_completion_tokens"] ?: body["max_tokens"])?.let { put("maxOutputTokens", it) }
        cloudGeminiThinking(body.cloudText("reasoning_effort"), modelId)?.let { put("thinkingConfig", it) }
    }
    if (generation.isNotEmpty()) put("generationConfig", generation)
}

internal fun cloudAnthropicBody(body: JsonObject, version: String, modelId: String): JsonObject = buildJsonObject {
    put("anthropic_version", version)
    put("stream", true)
    put("max_tokens", body["max_completion_tokens"] ?: body["max_tokens"] ?: JsonPrimitive(DEFAULT_ANTHROPIC_OUTPUT_TOKENS))
    val system = systemText(body)
    if (system.isNotEmpty()) put("system", system)
    val adaptive = cloudClaudeAdaptive(body.cloudText("reasoning_effort"), modelId)
    adaptive?.forEach { (key, value) -> put(key, value) }
    if (adaptive == null && !cloudModernClaude(modelId)) body["temperature"]?.let { put("temperature", it) }
    put("messages", JsonArray(cloudMessages(body).filter { it.cloudText("role") !in listOf("system", "developer") }.map { message ->
        buildJsonObject {
            put("role", if (message.cloudText("role") == "assistant") "assistant" else "user")
            put("content", JsonArray(contentParts(message).map { part ->
                when (part.cloudText("type")) {
                    "image_url" -> {
                        val image = cloudImage(part)
                        buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject { put("type", "base64"); put("media_type", image.mimeType); put("data", image.data) })
                        }
                    }
                    else -> buildJsonObject { put("type", "text"); put("text", part.cloudText("text").orEmpty()) }
                }
            }))
        }
    }))
}

internal fun cloudBedrockBody(body: JsonObject, modelId: String): JsonObject = buildJsonObject {
    val system = systemText(body)
    if (system.isNotEmpty()) put("system", JsonArray(listOf(textPart(system))))
    put("messages", JsonArray(cloudMessages(body).filter { it.cloudText("role") !in listOf("system", "developer") }.map { message ->
        buildJsonObject {
            put("role", if (message.cloudText("role") == "assistant") "assistant" else "user")
            put("content", JsonArray(contentParts(message).map { part ->
                when (part.cloudText("type")) {
                    "image_url" -> {
                        val image = cloudImage(part)
                        val format = when (image.mimeType) {
                            "image/jpeg" -> "jpeg"
                            "image/png" -> "png"
                            "image/gif" -> "gif"
                            "image/webp" -> "webp"
                            else -> error("Bedrock does not support image format ${image.mimeType}")
                        }
                        buildJsonObject { put("image", buildJsonObject { put("format", format); put("source", buildJsonObject { put("bytes", image.data) }) }) }
                    }
                    else -> textPart(part.cloudText("text").orEmpty())
                }
            }))
        }
    }))
    val inference = buildJsonObject {
        (body["max_completion_tokens"] ?: body["max_tokens"])?.let { put("maxTokens", it) }
        // New Claude generations reject temperature/topP even when thinking is disabled.
        if (!cloudModernClaude(modelId)) {
            body["temperature"]?.let { put("temperature", it) }
            body["top_p"]?.let { put("topP", it) }
        }
    }
    if (inference.isNotEmpty()) put("inferenceConfig", inference)
    cloudClaudeAdaptive(body.cloudText("reasoning_effort"), modelId)?.let { put("additionalModelRequestFields", it) }
}

internal fun cloudReasoningBody(body: JsonObject, modelId: String, azure: Boolean): JsonObject {
    val shortName = modelId.substringAfterLast('/')
    val reasoning = isOpenAiReasoning(shortName)
    val result = body.toMutableMap()
    if (azure && body.cloudText("reasoning_effort") == "minimal") result["reasoning_effort"] = JsonPrimitive("low")
    if (reasoning) {
        listOf("temperature", "top_p", "frequency_penalty", "presence_penalty", "logprobs", "top_logprobs", "logit_bias").forEach(result::remove)
        result.remove("max_tokens")?.let { result["max_completion_tokens"] = it }
        result["messages"] = JsonArray(cloudMessages(body).map { message ->
            if (message.cloudText("role") != "system") message else JsonObject(message + ("role" to JsonPrimitive(
                if (shortName.startsWith("o1-preview") || shortName.startsWith("o1-mini")) "user" else "developer",
            )))
        })
    }
    return JsonObject(result)
}

internal fun cloudResponsesBody(body: JsonObject, deployment: String): JsonObject = buildJsonObject {
    put("model", deployment)
    put("stream", body["stream"] ?: JsonPrimitive(true))
    put("store", false)
    val instructions = systemText(body)
    if (instructions.isNotEmpty()) put("instructions", instructions)
    put("input", JsonArray(cloudMessages(body).filter { it.cloudText("role") !in listOf("system", "developer") }.map { message ->
        val assistant = message.cloudText("role") == "assistant"
        buildJsonObject {
            put("role", if (assistant) "assistant" else "user")
            put("content", JsonArray(contentParts(message).map { part ->
                if (part.cloudText("type") == "image_url") buildJsonObject {
                    put("type", "input_image")
                    put("image_url", (part["image_url"] as? JsonObject)?.cloudText("url").orEmpty())
                } else buildJsonObject { put("type", if (assistant) "output_text" else "input_text"); put("text", part.cloudText("text").orEmpty()) }
            }))
        }
    }))
    (body["max_completion_tokens"] ?: body["max_tokens"])?.let { put("max_output_tokens", it) }
    body.cloudText("reasoning_effort")?.let { effort -> put("reasoning", buildJsonObject { put("effort", effort) }) }
}

private fun cloudModernClaude(modelId: String): Boolean =
    Regex("claude-(?:opus|sonnet|haiku)-(?:4-[6-9]|[5-9])").containsMatchIn(modelId)

private fun cloudClaudeAdaptive(effort: String?, modelId: String): JsonObject? {
    if (effort == null || !cloudModernClaude(modelId)) return null
    return buildJsonObject {
        put("thinking", buildJsonObject { put("type", if (effort == "none") "disabled" else "adaptive") })
        if (effort != "none") put("output_config", buildJsonObject {
            put("effort", when {
                effort == "minimal" -> "low"
                effort == "max" && !modelId.contains("opus") -> "high"
                effort == "xhigh" && modelId.contains("4-6") -> if (modelId.contains("opus")) "max" else "high"
                else -> effort
            })
        })
    }
}

private fun cloudGeminiThinking(effort: String?, modelId: String): JsonObject? {
    if (effort == null) return null
    val name = modelId.substringAfterLast('/')
    val major = Regex("^gemini-([0-9]+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return when {
        major >= 3 -> buildJsonObject {
            val level = when (effort) {
                "none", "minimal" -> if (name.contains("pro")) "low" else "minimal"
                "medium" -> if (name.startsWith("gemini-3-pro")) "high" else "medium"
                "max", "xhigh" -> "high"
                else -> effort
            }
            put("thinkingLevel", level.uppercase(java.util.Locale.ROOT))
            put("includeThoughts", true)
        }
        name.startsWith("gemini-2.5-") -> buildJsonObject {
            val budget = when (effort) {
                "none", "minimal" -> if (name.contains("pro")) 128 else 0
                "low" -> 1024
                "medium" -> 8192
                "high", "xhigh", "max" -> 24576
                else -> -1
            }
            put("thinkingBudget", budget)
            if (budget != 0) put("includeThoughts", true)
        }
        else -> return null
    }
}
