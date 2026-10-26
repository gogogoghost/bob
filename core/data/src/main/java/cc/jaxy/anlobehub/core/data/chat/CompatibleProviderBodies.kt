package cc.jaxy.anlobehub.core.data.chat

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Provider-specific OpenAI-compatible body rules, after the canonical text/image conversion. */
internal fun compatibleProviderBody(
    providerId: String,
    modelId: String,
    body: JsonObject,
    history: List<ChatMessage>,
    reasoning: Map<String, String>,
): JsonObject {
    val id = providerId.lowercase(Locale.ROOT)
    if (id == "custom") return body
    val model = modelId.lowercase(Locale.ROOT).substringAfterLast('/')
    val values = body.toMutableMap()
    val effort = reasoning["reasoningEffort"] ?: reasoning["effort"] ?: body.compatibleString("reasoning_effort")
    val budget = (reasoning["budgetTokens"] ?: reasoning["budget_tokens"] ?: reasoning["thinkingBudget"])?.toIntOrNull()
    val explicitType = reasoning["thinkingType"] ?: reasoning["type"] ?: (body["thinking"] as? JsonObject)?.compatibleString("type")
    val thinkingType = explicitType ?: when {
        effort == "none" || budget == 0 -> "disabled"
        effort != null || budget != null -> "enabled"
        else -> null
    }
    val thinkingEnabled = thinkingType != "disabled"
    val preserve = reasoning["preserveThinking"]?.toBooleanStrictOrNull()
    fun number(key: String) = (values[key] as? JsonPrimitive)?.doubleOrNull
    fun change(key: String, transform: (Double) -> Double) { number(key)?.let { values[key] = JsonPrimitive(transform(it)) } }
    fun clamp(key: String, min: Double, max: Double) = change(key) { it.coerceIn(min, max) }
    fun removePenalties() { values.remove("frequency_penalty"); values.remove("presence_penalty") }
    fun nativeThinking(type: String? = thinkingType) {
        if (type != null) values["thinking"] = buildJsonObject { put("type", type) }
    }
    fun outputLimit() { values.remove("max_tokens")?.let { values["max_completion_tokens"] = it } }
    fun flag(key: String, enabled: Boolean) { values[key] = JsonPrimitive(enabled) }
    fun replay(fillMissing: Boolean = false) {
        values["messages"] = compatibleHistory(values["messages"], history) { message, previous ->
            val text = message.compatibleString("reasoning_content") ?: previous?.reasoning
            if (message.compatibleString("role") == "assistant" && (text != null || fillMissing))
                JsonObject(message + ("reasoning_content" to JsonPrimitive(text.orEmpty()))) else message
        }
    }

    // Aggregator IDs do not identify the underlying model's unsigned passback contract.
    if (id !in setOf("cerebras", "zhipu", "qwen", "nvidia", "hunyuan", "xiaomimimo") &&
        (model.contains("deepseek") || model.contains("glm") || model.contains("qwen") || model.contains("mimo") || model.contains("kimi"))) replay()

    when (id) {
        "taichu" -> {
            change("temperature") { (it / 2).coerceAtLeast(0.01) }
            // This unusual upper bound is the pinned upstream adapter's actual contract.
            change("top_p") { (it / 2).coerceIn(0.1, 9.9) }
        }
        "baichuan" -> {
            change("temperature") { it / 2 }
            if (model.startsWith("baichuan-m")) removePenalties()
            budget?.let { values["budget_tokens"] = JsonPrimitive(it.coerceAtMost(1024)) }
        }
        "zhipu" -> {
            replay()
            change("temperature") { it / 2 }
            if (model.contains("glm-4v")) change("max_tokens") { it.coerceAtMost(1024.0) }
            if (model == "glm-zero-preview") change("max_tokens") { it.coerceAtMost(15300.0) }
            if (model == "glm-4-alltools") { clamp("temperature", 0.01, 0.99); clamp("top_p", 0.01, 0.99) }
            val version = Regex("^glm-(\\d+)(?:[.-](\\d+))?(?:$|-)").find(model)
            val major = version?.groupValues?.get(1)?.toIntOrNull()
            val minor = version?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val forced = major != null && (major > 5 || major == 5 && minor >= 3)
            val thinking = ((values["thinking"] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
            if (thinkingType != null || forced) thinking["type"] = JsonPrimitive(if (forced) "enabled" else thinkingType!!)
            preserve?.let { thinking["clear_thinking"] = JsonPrimitive(!it) }
            if (thinking.isNotEmpty()) values["thinking"] = JsonObject(thinking)
            if (effort != null) values["reasoning_effort"] = JsonPrimitive(effort)
            if (major != null && (major >= 5 || major == 4 && minor >= 6) && body.compatibleString("stream") == "true") flag("tool_stream", true)
        }
        "qwen" -> {
            replay()
            values.remove("thinking")
            values.remove("frequency_penalty")
            clamp("temperature", 0.0, 2.0); clamp("top_p", 0.0, 1.0); clamp("presence_penalty", -2.0, 2.0)
            preserve?.let { flag("preserve_thinking", it) }
            val version = Regex("^qwen(\\d+)(?:\\.(\\d+))?-max(?:$|-)").find(model)
            val major = version?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minor = version?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val forced = model.contains("-thinking") || model.startsWith("qvq") || major > 3 || major == 3 && minor > 8
            if (thinkingType != null || forced) flag("enable_thinking", forced || thinkingEnabled)
            if (forced && !thinkingEnabled) { values.remove("thinking_budget"); values.remove("reasoning_effort") }
            else if (model.startsWith("qwen3.8-") && thinkingEnabled && effort != null) {
                values["reasoning_effort"] = JsonPrimitive(effort); values.remove("thinking_budget")
            } else budget?.let { values["thinking_budget"] = JsonPrimitive(it) }
            if (!thinkingEnabled) values.remove("reasoning_effort")
            flag("stream", true)
            if (values["tools"] is JsonArray) flag("parallel_tool_calls", true)
        }
        "bailiancodingplan", "glmcodingplan", "minimaxcodingplan", "volcenginecodingplan" -> {
            values.remove("thinking"); values.remove("enable_thinking"); values.remove("thinking_budget")
            if (thinkingType == "enabled" && budget != 0) {
                flag("enable_thinking", true)
                budget?.let { values["thinking_budget"] = JsonPrimitive(it) }
            }
            if (id == "bailiancodingplan") { values.remove("frequency_penalty"); clamp("temperature", 0.0, 2.0) }
            flag("stream", true)
            if (values["tools"] is JsonArray) flag("parallel_tool_calls", true)
        }
        "minimax" -> {
            val m3 = model.startsWith("minimax-m3")
            values["messages"] = compatibleHistory(values["messages"], history) { message, previous ->
                val result = message.toMutableMap()
                if (message.compatibleString("role") == "assistant") {
                    val text = message.compatibleString("reasoning_content") ?: previous?.reasoning
                    result.remove("reasoning_content"); result.remove("reasoning")
                    if (!text.isNullOrEmpty() && result["reasoning_details"] == null) result["reasoning_details"] = JsonArray(listOf(buildJsonObject {
                        put("format", "MiniMax-response-v1"); put("id", "reasoning-text-0"); put("index", 0)
                        put("text", text); put("type", "reasoning.text")
                    }))
                }
                (message["content"] as? JsonArray)?.let { parts ->
                    result["content"] = JsonArray(parts.map { part ->
                        val objectPart = part as? JsonObject
                        val image = objectPart?.get("image_url") as? JsonObject
                        if (objectPart != null && image?.compatibleString("detail") == "auto") JsonObject(objectPart + ("image_url" to JsonObject(image - "detail"))) else part
                    })
                }
                JsonObject(result)
            }
            flag("reasoning_split", true)
            if (m3) {
                outputLimit(); clamp("temperature", 0.0, 2.0); clamp("top_p", 0.0, 1.0)
                nativeThinking(thinkingType?.let { if (it == "disabled") "disabled" else "adaptive" })
            } else {
                change("temperature") { it / 2 }
                if (number("temperature")?.let { it <= 0 } == true) values.remove("temperature")
                clamp("top_p", 0.01, 1.0); nativeThinking()
            }
        }
        "cerebras" -> {
            removePenalties(); values.remove("thinking"); values.remove("reasoning"); values.remove("reasoning_effort")
            when {
                model.contains("glm") -> if (!thinkingEnabled) values["reasoning_effort"] = JsonPrimitive("none") else values["reasoning_format"] = JsonPrimitive("parsed")
                model.contains("gemma-4") -> when {
                    !thinkingEnabled -> values["reasoning_effort"] = JsonPrimitive("none")
                    thinkingType != null -> { values["reasoning_effort"] = JsonPrimitive("medium"); values["reasoning_format"] = JsonPrimitive("parsed") }
                    else -> Unit
                }
                model.contains("gpt-oss") -> if (!thinkingEnabled) values["reasoning_format"] = JsonPrimitive("hidden") else {
                    values["reasoning_effort"] = JsonPrimitive(compatibleThreeLevelEffort(effort) ?: "medium")
                    values["reasoning_format"] = JsonPrimitive("parsed")
                }
            }
            values["messages"] = compatibleHistory(values["messages"], history) { message, previous ->
                if (message.compatibleString("role") != "assistant") message else {
                    val text = message.compatibleString("reasoning_content") ?: previous?.reasoning
                    val result = message.toMutableMap().apply { remove("reasoning_content"); remove("reasoning") }
                    val prefix = when {
                        text.isNullOrEmpty() -> ""
                        model.contains("glm") -> "<think>$text</think>"
                        model.contains("gpt-oss") -> text
                        else -> ""
                    }
                    if (prefix.isNotEmpty()) result["content"] = compatiblePrependContent(prefix, message["content"])
                    JsonObject(result)
                }
            }
        }
        "nvidia" -> {
            replay(); values.remove("thinking")
            if (thinkingType == "enabled" || thinkingType == "disabled") {
                val kwargs = (values["chat_template_kwargs"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                when (modelId.lowercase(Locale.ROOT)) {
                    "z-ai/glm4.7", "z-ai/glm5" -> { kwargs["enable_thinking"] = JsonPrimitive(thinkingEnabled); kwargs["clear_thinking"] = JsonPrimitive(false) }
                    "qwen/qwen3.5-397b-a17b" -> kwargs["enable_thinking"] = JsonPrimitive(thinkingEnabled)
                    else -> kwargs["thinking"] = JsonPrimitive(thinkingEnabled)
                }
                values["chat_template_kwargs"] = JsonObject(kwargs)
            }
        }
        "hunyuan" -> { removePenalties(); replay(model == "hy3" || model == "hy3-preview"); nativeThinking() }
        "xiaomimimo" -> {
            outputLimit(); replay(thinkingEnabled)
            clamp("temperature", 0.0, 1.5); clamp("top_p", 0.01, 1.0)
            if (thinkingEnabled) { values["temperature"] = JsonPrimitive(1.0); values["top_p"] = JsonPrimitive(0.95) }
            nativeThinking()
            val messages = (values["messages"] as? JsonArray).orEmpty().filter { element ->
                val message = element as? JsonObject
                message?.compatibleString("role") != "user" || when (val content = message["content"]) {
                    is JsonPrimitive -> content.content.isNotBlank()
                    is JsonArray -> content.isNotEmpty()
                    else -> false
                }
            }.toMutableList()
            while ((messages.lastOrNull() as? JsonObject)?.compatibleString("role") == "assistant") messages.removeAt(messages.lastIndex)
            values["messages"] = JsonArray(messages)
        }
        "moonshot" -> if (Regex("^kimi-k(?:[3-9]|[1-9][0-9])").containsMatchIn(model)) {
            outputLimit(); values.remove("thinking"); values.remove("temperature"); values.remove("top_p"); removePenalties()
            if (effort in setOf("low", "high", "max")) values["reasoning_effort"] = JsonPrimitive(effort) else values.remove("reasoning_effort")
        }
        "groq" -> {
            values.remove("thinking"); values.remove("reasoning"); values.remove("reasoning_effort")
            if (number("temperature")?.let { it <= 0 } == true) values.remove("temperature")
            if (model.contains("deepseek-r1") || model.contains("qwen3")) values["reasoning_format"] = JsonPrimitive(if (thinkingEnabled) "parsed" else "hidden")
            if (model.contains("qwen3.6-27b")) values["reasoning_effort"] = JsonPrimitive(if (thinkingEnabled) "default" else "none")
            else if (model.contains("gpt-oss")) compatibleThreeLevelEffort(effort)?.let { values["reasoning_effort"] = JsonPrimitive(it) }
        }
        "cohere" -> {
            values.remove("user"); values.remove("stream_options")
            clamp("frequency_penalty", 0.0, 1.0); clamp("presence_penalty", 0.0, 1.0); clamp("top_p", 0.0, 1.0)
        }
        "longcat" -> { removePenalties(); change("temperature") { (it / 2).coerceIn(0.0, 1.0) }; nativeThinking(); flag("stream", true) }
        "mistral" -> {
            values.remove("user"); values.remove("stream_options"); values.remove("thinking")
            change("temperature") { it / 2 }; flag("stream", true)
            (effort ?: thinkingType?.let { if (it == "disabled") "none" else "high" })?.let { values["reasoning_effort"] = JsonPrimitive(it) }
        }
        "internlm" -> { values.remove("thinking"); thinkingType?.let { flag("thinking_mode", it == "enabled") } }
        "infiniai" -> { values.remove("thinking"); flag("enable_thinking", thinkingType == "enabled") }
        "streamlake" -> { values.remove("thinking"); thinkingType?.let { flag("enable_thinking", it == "enabled") } }
        "siliconcloud" -> {
            values.remove("thinking"); change("max_tokens") { it.coerceIn(1.0, 16384.0) }
            thinkingType?.let { flag("enable_thinking", it == "enabled") }
            budget?.let { values["thinking_budget"] = JsonPrimitive(it.coerceIn(128, 32768)) }
            if (!thinkingEnabled) values.remove("reasoning_effort")
        }
        "wenxin" -> {
            values.remove("thinking"); thinkingType?.let { flag("enable_thinking", it == "enabled") }
            budget?.takeIf { it != 0 }?.let { values["thinking_budget"] = JsonPrimitive(it.coerceIn(100, 16384)) }
            flag("stream", true)
        }
        "volcengine" -> {
            values.remove("thinking")
            if (model.contains("deepseek-v4") || model.contains("glm-5-2")) {
                if (!thinkingEnabled) { nativeThinking("disabled"); values["reasoning_effort"] = JsonPrimitive("minimal") }
                else if (thinkingType == "enabled" || effort != null) { nativeThinking("enabled"); values["reasoning_effort"] = JsonPrimitive(effort ?: "high") }
            } else if (thinkingType in setOf("enabled", "disabled")) nativeThinking()
        }
        "antgroup" -> {
            values.remove("reasoning_effort")
            effort?.let { values["reasoning"] = buildJsonObject { put("effort", it) } }
            nativeThinking()
        }
        "akashchat" -> {
            values["allowed_openai_params"] = JsonArray(listOf(JsonPrimitive("reasoning_effort")))
            values["cache"] = buildJsonObject { put("no-cache", true) }
            if (model.contains("deepseek-v3-1") && thinkingType != null) values["chat_template_kwargs"] = buildJsonObject { put("thinking", thinkingEnabled) }
        }
        "perplexity", "search1api" -> {
            if (number("presence_penalty") == 0.0) {
                values.remove("presence_penalty")
                if (number("frequency_penalty") == null || number("frequency_penalty") == 0.0) values["frequency_penalty"] = JsonPrimitive(1)
            } else values.remove("frequency_penalty")
            if (number("temperature")?.let { it >= 2 } == true) values.remove("temperature")
        }
        "sensenova" -> {
            for (key in listOf("frequency_penalty", "presence_penalty", "temperature")) if (number(key)?.let { it <= 0 || it > 2 } == true) values.remove(key)
            if (number("top_p")?.let { it <= 0 || it >= 1 } == true) values.remove("top_p")
        }
        "cometapi", "nebius" -> flag("stream", true)
        "ai21" -> if (values["tools"] is JsonArray) flag("stream", false)
        "ai360" -> if (values["tools"] is JsonArray || reasoning["enabledSearch"] == "true") flag("stream", false)
        "spark" -> values.remove("user")
        "straico" -> flag("stream", false)
        "openrouter" -> {
            values.remove("reasoning_effort"); values.remove("thinking")
            val selectedEffort = effort ?: reasoning["thinkingLevel"]
            if (thinkingType != null || budget != null || selectedEffort != null) values["reasoning"] = buildJsonObject {
                when {
                    !thinkingEnabled -> put("enabled", false)
                    budget != null -> put("max_tokens", budget)
                    selectedEffort != null -> put("effort", selectedEffort)
                }
            }
        }
        "zenmux" -> {
            values.remove("reasoning_effort"); values.remove("thinking")
            if (budget != null || effort != null || thinkingType != null) values["reasoning"] = buildJsonObject {
                effort?.let { put("effort", it) }; budget?.let { put("max_tokens", it) }
                thinkingType?.let { put("enabled", it != "disabled") }
            }
        }
        "vercelaigateway" -> {
            values.remove("reasoning_effort"); values.remove("thinking")
            if (thinkingType != null || budget != null || effort != null) values["reasoning"] = buildJsonObject {
                put("enabled", thinkingEnabled)
                if (thinkingEnabled) budget?.let { put("max_tokens", it) }
            }
            val verbosity = reasoning["verbosity"] ?: body.compatibleString("verbosity")
            values.remove("verbosity")
            if (modelId.contains("openai", ignoreCase = true) && (effort != null || verbosity != null)) {
                val providerOptions = (values["providerOptions"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                val openai = (providerOptions["openai"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                if (effort != null) { openai["reasoningEffort"] = JsonPrimitive(effort); openai["reasoningSummary"] = JsonPrimitive("auto") }
                verbosity?.let { openai["textVerbosity"] = JsonPrimitive(it) }
                providerOptions["openai"] = JsonObject(openai); values["providerOptions"] = JsonObject(providerOptions)
            }
        }
    }
    // The K3 family renamed its output limit even through compatible aggregation routes.
    if (id != "moonshot" && Regex("^kimi-k(?:[3-9]|[1-9][0-9])").containsMatchIn(model)) outputLimit()
    return JsonObject(values)
}

private fun JsonObject.compatibleString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun compatibleThreeLevelEffort(effort: String?): String? = when (effort) {
    "minimal", "low" -> "low"
    "medium" -> "medium"
    "high", "xhigh", "max" -> "high"
    else -> null
}

private inline fun compatibleHistory(
    messages: JsonElement?,
    history: List<ChatMessage>,
    transform: (JsonObject, ChatMessage?) -> JsonObject,
): JsonArray {
    val original = history.filter { it.role != "system" }
    var index = 0
    return JsonArray((messages as? JsonArray).orEmpty().map { element ->
        val message = element as? JsonObject
        if (message == null || message.compatibleString("role") in setOf("system", "developer")) element else {
            transform(message, original.getOrNull(index++))
        }
    })
}

private fun compatiblePrependContent(prefix: String, content: JsonElement?): JsonElement = when (content) {
    is JsonArray -> JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", prefix) }) + content)
    is JsonPrimitive -> JsonPrimitive(prefix + content.content)
    else -> JsonPrimitive(prefix)
}
