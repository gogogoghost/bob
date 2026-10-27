package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.common.util.providerAdapterType
import cc.jaxy.bob.core.common.util.providerDefaultEndpoint
import cc.jaxy.bob.core.data.files.FileRepository
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Provider-specific contracts live here; a catalog protocol alone is not a routing rule. */
internal class ProviderChatRequests(private val files: FileRepository? = null, private val client: OkHttpClient) {
    private val cloud = CloudProviderRequests(client)
    suspend fun prepare(
        providerId: String,
        source: String,
        config: Map<String, String>,
        modelId: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Double?,
        sessionId: String,
        reasoning: Map<String, String> = emptyMap(),
        maxOutput: Int? = null,
        webSearchEnabled: Boolean = false,
        toolSearch: Boolean = false,
    ): PreparedChatRequest {
        if (webSearchEnabled) requireWebSearchSupport(providerId, source, modelId, reasoning)
        val builtin = source != "custom"
        val id = if (builtin) providerId.lowercase() else "custom"
        val adapter = if (!builtin) "openai" else providerAdapterType(id, config["__catalogProtocol"], config["__sdkType"])
            ?: throw UnsupportedOperationException("No direct chat adapter is implemented for ${config["__providerName"] ?: providerId}.")
        val model = modelId.lowercase().substringAfterLast('/')
        val effort = reasoning["reasoningEffort"] ?: reasoning["effort"]
        val thinkingType = reasoning["thinkingType"] ?: reasoning["type"] ?: effort?.takeIf { it == "none" }?.let { "disabled" }
        val thinkingEnabled = thinkingType != "disabled"
        val kimiCodeAlias = id == "kimicodingplan" && (model == "k3" || model == "k3-256k" || model.startsWith("kimi-for-coding"))
        val kimi = model.startsWith("kimi-k") || model == "k2p5" || kimiCodeAlias
        val nativeKimi = KIMI_K3.containsMatchIn(model) || model.contains("thinking") || KIMI_CODE.containsMatchIn(model) || kimiCodeAlias
        val kimiToggle = model == "k2p5" || KIMI_TOGGLE.containsMatchIn(model)
        val kimiEffort = KIMI_K3.containsMatchIn(model) || kimiCodeAlias
        val interleaved = id in setOf("opencodecodingplan", "opencodezen") &&
            (model.startsWith("glm-") || model.startsWith("deepseek-") || model.startsWith("mimo-"))
        val replayReasoning = (kimi && (nativeKimi || thinkingEnabled)) || (interleaved && thinkingEnabled) ||
            ((id == "deepseek" || model.startsWith("deepseek-")) && thinkingEnabled)
        val canonicalMessages = ArrayList<JsonObject>(history.size + 1)
        if (systemPrompt.isNotBlank()) canonicalMessages += buildJsonObject { put("role", "system"); put("content", systemPrompt) }
        val toolNames = HashMap<String, String>()
        for (message in history) {
            if (message.role == "system") continue
            if (message.role == "tool") {
                canonicalMessages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", message.toolCallId.orEmpty())
                    put("content", message.content)
                }
                continue
            }
            val parts = if (message.attachments.isEmpty()) null else ArrayList<JsonElement>(message.attachments.size + 1).apply {
                if (message.content.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", message.content) })
                for (attachment in message.attachments) {
                    require(attachment.mimeType.startsWith("image/")) { "This chat adapter accepts image attachments, not ${attachment.mimeType}" }
                    val data = Base64.getEncoder().encodeToString(readAttachment(attachment.uri))
                    add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", "data:${attachment.mimeType};base64,$data") } })
                }
            }
            canonicalMessages += buildJsonObject {
                put("role", if (message.role == "assistant") "assistant" else "user")
                if (parts == null) put("content", if (kimi && message.content.isEmpty() && message.toolCalls.isEmpty()) " " else message.content)
                else put("content", JsonArray(parts))
                if (message.role == "assistant" && replayReasoning) put("reasoning_content", message.reasoning?.takeIf(String::isNotBlank) ?: " ")
                if (message.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        message.toolCalls.forEach { call ->
                            toolNames[call.id] = call.name
                            add(buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                }
                            })
                        }
                    }
                }
            }
        }
        val messages = JsonArray(canonicalMessages)
        // Stream-first, except documented native nonstream models and Straico's JSON API.
        val stream = id != "straico" && !((id == "openai" || id == "azure") &&
            (model.startsWith("o1-pro") || model.startsWith("computer-use-preview")))
        val body = buildJsonObject {
            put("model", modelId); put("stream", stream); put("messages", messages)
            temperature?.let { put("temperature", it) }
            // Cloud adapters consume the same canonical message representation.
            maxOutput?.takeIf { it > 0 }?.let { put("max_tokens", it) }
            effort?.let { put("reasoning_effort", it) }
            if (toolSearch) putJsonArray("tools") { add(openAiWebSearchTool()) }
        }
        if (builtin) cloud.prepare(id, config, modelId, body)?.let {
            require(!toolSearch || it.protocol in TOOL_PROTOCOLS) { UNSUPPORTED_TOOL_SEARCH }
            return it.copy(toolSearch = toolSearch)
        }
        var base = (config["baseURL"]?.takeIf(String::isNotBlank) ?: providerDefaultEndpoint(id))
            ?.trim()?.trimEnd('/') ?: error("Provider endpoint is not configured")
        var protocol = when (adapter) {
            "google" -> "gemini"
            "ollama" -> "ollama"
            "anthropic" -> "anthropic"
            else -> "openai"
        }
        if (id == "ollamacloud") protocol = "ollama"
        if (id == "chatgpt" || id == "supergrok" || id == "xai" || id == "meta") protocol = "responses"
        if (id == "githubcopilot") protocol = when {
            model.contains("claude") -> "anthropic"
            usesResponses(model) || model.contains("oswe") -> "responses"
            else -> "openai"
        }
        if (id == "opencodezen" || id == "opencodecodingplan") {
            val route = openCodeRoute(id, base, model)
            protocol = route.first
            base = route.second
        }
        if (id == "aihubmix" || id == "zenmux" || id == "newapi") {
            val root = base.replace(API_VERSION_SUFFIX, "").let { if (id == "zenmux") it.removeSuffix("/api") else it }
            when {
                model.startsWith("claude-") -> { protocol = "anthropic"; base = if (id == "zenmux") "$root/api/anthropic" else root }
                model.startsWith("gemini-") -> { protocol = "gemini"; base = when (id) { "zenmux" -> "$root/api/vertex-ai"; "aihubmix" -> "$root/gemini"; else -> root } }
                else -> {
                    val broadResponses = id != "zenmux" && (model.startsWith("gpt-") || OPENAI_O_SERIES.containsMatchIn(model) || model.startsWith("grok-"))
                    protocol = if (broadResponses || usesResponses(modelId.lowercase())) "responses" else "openai"
                    base = if (id == "zenmux") "$root/api/v1" else "$root/v1"
                }
            }
        }
        if (id == "openai" && OPENAI_NATIVE_RESPONSES_MODEL.containsMatchIn(model) &&
            !OPENAI_CHAT_ONLY_MODEL.containsMatchIn(model)) protocol = "responses"
        if (protocol == "openai" && id in DIRECT_OPENAI_ROUTES && usesResponses(modelId.lowercase())) protocol = "responses"
        if (model.startsWith("gpt-3.5-turbo-instruct")) protocol = "completions"
        if (id == "straico") protocol = "straico"
        if (webSearchEnabled) protocol = "responses"
        if (id == "spark" && base.toHttpUrl().host == "spark-api-open.xf-yun.com") {
            val path = when (model) { "spark-x2-flash" -> "/agent/v1"; "spark-x2" -> "/x2"; "spark-x1.5" -> "/v2"; else -> "/v1" }
            base = "https://spark-api-open.xf-yun.com$path"
        }
        if (toolSearch) require(protocol in TOOL_PROTOCOLS) { UNSUPPORTED_TOOL_SEARCH }
        val key = config["apiKey"].orEmpty()
        val headers = mutableMapOf("User-Agent" to "BobAndroid/0.1.0", "Accept" to "text/event-stream, application/json")
        if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
        if (id == "opencodecodingplan") {
            headers["x-opencode-client"] = "local-agent-android"
            headers["x-opencode-session"] = sessionId
        }
        if (id == "githubcopilot") {
            headers["Copilot-Integration-Id"] = "vscode-chat"
            headers["Editor-Plugin-Version"] = "LocalAgentAndroid/0.1.0"
            headers["Editor-Version"] = "LocalAgentAndroid/0.1.0"
        }
        when (id) {
            "xiaomimimo" -> { headers.remove("Authorization"); if (key.isNotBlank()) headers["api-key"] = key }
            "aihubmix" -> headers["APP-Code"] = "LocalAgentAndroid"
            "newapi" -> headers["X-Client"] = "LocalAgentAndroid"
            "novita" -> headers["X-Novita-Source"] = "local-agent-android"
            "ppio" -> headers["X-API-Source"] = "local-agent-android"
            "higress" -> headers["x-Request-Id"] = java.util.UUID.randomUUID().toString()
        }
        if (id in ATTRIBUTED_PROVIDERS) {
            headers["X-Title"] = "Local Agent for Android"
        }
        val payload: JsonObject
        val url: String
        when (protocol) {
            "completions" -> {
                require(history.none { it.attachments.isNotEmpty() }) { "Legacy completion models do not accept image attachments" }
                val prompt = buildString {
                    if (systemPrompt.isNotBlank()) append(systemPrompt).append("\n\n")
                    for (message in history) if (message.role != "system") append(message.role).append(": ").append(message.content).append('\n')
                    append("assistant:")
                }
                payload = buildJsonObject {
                    put("model", modelId); put("prompt", prompt); put("stream", true)
                    body["max_tokens"]?.let { put("max_tokens", it) }
                    temperature?.let { put("temperature", it) }
                }
                url = apiPath(base, "completions", id)
            }
            "straico" -> {
                payload = JsonObject(body + ("stream" to JsonPrimitive(false)))
                url = if (base.endsWith("/chat")) base else "$base/chat"
            }
            "anthropic" -> {
                headers["anthropic-version"] = "2023-06-01"
                if (key.isNotBlank()) headers["x-api-key"] = key
                val forceThinking = id == "kimicodingplan" && (kimiToggle || nativeKimi)
                val budget = (reasoning["budgetTokens"] ?: reasoning["budget_tokens"])?.toIntOrNull()?.coerceAtLeast(1024) ?: 1024
                val limit = maxOutput?.takeIf { it > 0 } ?: DEFAULT_ANTHROPIC_OUTPUT_TOKENS
                payload = buildJsonObject {
                    put("model", modelId); put("max_tokens", limit); put("stream", stream)
                    if (systemPrompt.isNotBlank()) put("system", systemPrompt)
                    if (toolSearch) putJsonArray("tools") { add(anthropicWebSearchTool()) }
                    val chatMessages = anthropicMessages(messages, forceThinking && (nativeKimi || thinkingEnabled))
                    put("messages", if (MODERN_CLAUDE.containsMatchIn(model)) JsonArray(chatMessages.dropLastWhile { it.jsonObject.string("role") == "assistant" }) else chatMessages)
                    if (forceThinking) {
                        if (kimiEffort) {
                            effort?.takeIf { it in KIMI_EFFORTS }?.let { put("reasoning_effort", it) }
                        } else {
                            val enabled = nativeKimi || thinkingEnabled
                            putJsonObject("thinking") { put("type", if (enabled) "enabled" else "disabled"); if (enabled) put("budget_tokens", minOf(budget, limit - 1)) }
                            put("temperature", if (enabled) 1.0 else 0.6); put("top_p", 0.95)
                        }
                    } else {
                        val claudeAdaptive = MODERN_CLAUDE.containsMatchIn(model)
                        if (thinkingType != null || effort != null) {
                            if (claudeAdaptive) {
                                if (thinkingEnabled) putJsonObject("thinking") { put("type", "adaptive"); put("display", "summarized") }
                                if (effort in setOf("low", "medium", "high", "max")) putJsonObject("output_config") { put("effort", effort) }
                            } else if (thinkingEnabled && thinkingType != null) {
                                putJsonObject("thinking") { put("type", "enabled"); put("budget_tokens", minOf(budget, limit - 1)) }
                            }
                        }
                        if (thinkingType == null && effort == null && !claudeAdaptive) temperature?.let { put("temperature", (it / 2).coerceIn(0.0, 1.0)) }
                    }
                }
                url = appendVersion(base, "v1", "messages")
            }
            "gemini" -> {
                if (key.isNotBlank()) headers["x-goog-api-key"] = key
                val geminiEffort = reasoning["thinkingLevel"] ?: effort
                val geminiBody = if (geminiEffort == null) body else JsonObject(body + ("reasoning_effort" to JsonPrimitive(geminiEffort)))
                payload = cloudGeminiBody(geminiBody, modelId)
                val endpoint = appendVersion(base, "v1beta", "models/${modelId.removePrefix("models/")}:streamGenerateContent")
                url = endpoint.toHttpUrl().newBuilder().addQueryParameter("alt", "sse").build().toString()
            }
            "ollama" -> {
                payload = buildJsonObject {
                    put("model", modelId); put("stream", true)
                    if (toolSearch) putJsonArray("tools") { add(openAiWebSearchTool()) }
                    putJsonArray("messages") {
                        messages.forEach { raw ->
                            val message = raw.jsonObject
                            val role = message.string("role")
                            add(buildJsonObject {
                                put("role", role); put("content", contentText(message["content"]))
                                toolNames[message.string("tool_call_id").orEmpty()]?.let { put("tool_name", it) }
                                (message["tool_calls"] as? JsonArray)?.let { calls ->
                                    putJsonArray("tool_calls") {
                                        calls.forEach { element ->
                                            val function = (element as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
                                            add(buildJsonObject {
                                                putJsonObject("function") {
                                                    put("name", function.string("name").orEmpty())
                                                    put("arguments", toolInput(function.string("arguments").orEmpty()))
                                                }
                                            })
                                        }
                                    }
                                }
                                val images = imageParts(message["content"])
                                if (images.isNotEmpty()) putJsonArray("images") { images.forEach { add(JsonPrimitive(it.substringAfter("base64,"))) } }
                            })
                        }
                    }
                    if (temperature != null || body["max_tokens"] != null) putJsonObject("options") {
                        temperature?.let { put("temperature", it) }
                        body["max_tokens"]?.let { put("num_predict", it) }
                    }
                }
                val root = base.removeSuffix("/v1")
                url = if (root.endsWith("/api")) "$root/chat" else "$root/api/chat"
            }
            "responses" -> {
                if (id == "chatgpt") {
                    config["oauthAccountId"]?.takeIf(String::isNotBlank)?.let { headers["ChatGPT-Account-Id"] = it }
                    headers["originator"] = "local-agent-android"; headers["session-id"] = sessionId; headers["version"] = "0.1.0"
                }
                val lite = id == "chatgpt" && model in setOf("gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.6-terra")
                if (lite) headers["x-openai-internal-codex-responses-lite"] = "true"
                payload = buildJsonObject {
                    put("model", modelId); put("stream", stream); put("store", false)
                    if (webSearchEnabled) putJsonArray("tools") { add(buildJsonObject { put("type", "web_search") }) }
                    if (toolSearch) putJsonArray("tools") { add(responsesWebSearchTool()) }
                    if (id != "chatgpt") body["max_tokens"]?.let { put("max_output_tokens", it) }
                    if (systemPrompt.isNotBlank() && !lite) put("instructions", systemPrompt)
                    putJsonArray("input") {
                        if (lite) {
                            add(buildJsonObject { put("role", "developer"); put("type", "additional_tools"); putJsonArray("tools") { if (toolSearch) add(responsesWebSearchTool()) } })
                            if (systemPrompt.isNotBlank()) add(buildJsonObject { put("role", "developer"); put("type", "message"); putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", systemPrompt) }) } })
                        }
                        val originalMessages = history.filter { it.role != "system" }.iterator()
                        messages.forEach { raw ->
                            val message = raw.jsonObject
                            val role = message.string("role")
                            if (role != "system") {
                                val original = originalMessages.next()
                                if (original.provider == providerId && original.model == modelId) {
                                    original.providerContext.forEach { context ->
                                        if (context.string("type") == "reasoning" && context.string("encrypted_content").isNotBlank()) add(context)
                                    }
                                }
                            }
                            if (role == "tool") {
                                add(buildJsonObject {
                                    put("type", "function_call_output")
                                    put("call_id", message.string("tool_call_id").orEmpty())
                                    put("output", contentText(message["content"]))
                                })
                            } else if (role != "system") {
                                (message["tool_calls"] as? JsonArray)?.forEach { element ->
                                    val call = element as? JsonObject ?: return@forEach
                                    val function = call["function"] as? JsonObject ?: return@forEach
                                    add(buildJsonObject {
                                        put("type", "function_call")
                                        put("call_id", call.string("id").orEmpty())
                                        put("name", function.string("name").orEmpty())
                                        put("arguments", function.string("arguments").orEmpty().ifBlank { "{}" })
                                    })
                                }
                                val text = contentText(message["content"])
                                val images = imageParts(message["content"])
                                if (text.isNotEmpty() || images.isNotEmpty()) add(buildJsonObject {
                                    put("role", role); put("type", "message")
                                    putJsonArray("content") {
                                        if (text.isNotEmpty()) add(buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", text) })
                                        images.forEach { data -> add(buildJsonObject { put("type", "input_image"); put("image_url", data) }) }
                                    }
                                })
                            }
                        }
                    }
                    if (effort != null || lite || isOpenAiReasoning(model)) putJsonObject("reasoning") {
                        val resolvedEffort = if (isOpenAiReasoning(model) && model.contains("-pro")) "high" else effort
                        resolvedEffort?.let { put("effort", if (!webSearchEnabled && isOpenAiReasoning(model) && it == "max") "high" else it) }
                        if (lite) put("context", "all_turns")
                        if (isOpenAiReasoning(model) && !model.startsWith("o1") && !model.startsWith("o3-mini")) put("summary", "auto")
                    }
                    if (lite) { put("parallel_tool_calls", false); put("tool_choice", "auto") }
                    if (model.startsWith("computer-use")) put("truncation", "auto")
                    if (id in ENCRYPTED_RESPONSES_PROVIDERS) putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
                    if (!isOpenAiReasoning(model) && id !in setOf("chatgpt", "githubcopilot")) temperature?.let { put("temperature", it) }
                }
                url = apiPath(base, "responses", id)
            }
            else -> {
                val values = body.toMutableMap()
                values.remove("reasoning_effort")
                values["stream"] = JsonPrimitive(stream)
                if (isOpenAiReasoning(model)) {
                    values.remove("temperature")
                    values.remove("max_tokens")?.let { values["max_completion_tokens"] = it }
                    values["messages"] = JsonArray(messages.map { raw ->
                        val message = raw.jsonObject
                        if (message.string("role") != "system") message else JsonObject(message + ("role" to JsonPrimitive(if (model.startsWith("o1-mini") || model.startsWith("o1-preview")) "user" else "developer")))
                    })
                }
                if (effort != null && isOpenAiReasoning(model)) values["reasoning_effort"] = JsonPrimitive(effort)
                if (id == "moonshot" && !kimi) temperature?.let { values["temperature"] = JsonPrimitive((it / 2).coerceIn(0.0, 1.0)) }
                if (kimi && (id == "moonshot" || id == "opencodecodingplan" || id == "opencodezen")) {
                    if (kimiEffort) {
                        values.remove("temperature")
                        if (effort == "max") values["reasoning_effort"] = JsonPrimitive(effort)
                    } else if (kimiToggle) {
                        values["thinking"] = buildJsonObject { put("type", if (thinkingEnabled) "enabled" else "disabled"); if (model.startsWith("kimi-k2.6") && reasoning["preserveThinking"] == "true") put("keep", "all") }
                        values["temperature"] = JsonPrimitive(if (thinkingEnabled) 1.0 else 0.6)
                        values["top_p"] = JsonPrimitive(0.95)
                    }
                }
                if (id == "deepseek" || model.startsWith("deepseek-")) {
                    if (thinkingType != null) values["thinking"] = buildJsonObject { put("type", thinkingType) }
                    if (thinkingEnabled && effort != null) values["reasoning_effort"] = JsonPrimitive(effort)
                    if (thinkingEnabled && (model.contains("reasoner") || model.startsWith("deepseek-v4") || thinkingType == "enabled")) values.remove("temperature")
                }
                if (id == "qwen" || id == "bailiancodingplan") {
                    val forced = model.contains("thinking") || model.startsWith("qvq")
                    if (thinkingType != null || forced) values["enable_thinking"] = JsonPrimitive(forced || thinkingEnabled)
                    val budget = (reasoning["budgetTokens"] ?: reasoning["budget_tokens"])?.toIntOrNull()
                    if (model.startsWith("deepseek-v4")) {
                        values.remove("thinking"); if (thinkingEnabled && effort != null) values["reasoning_effort"] = JsonPrimitive(effort)
                    } else if (model.startsWith("qwen3.8-") && thinkingEnabled && effort != null) values["reasoning_effort"] = JsonPrimitive(effort)
                    else budget?.let { values["thinking_budget"] = JsonPrimitive(it) }
                }
                if (id == "zhipu" || id == "glmcodingplan" || id == "xiaomimimo") {
                    if (thinkingType != null) values["thinking"] = buildJsonObject { put("type", thinkingType) }
                }
                if (id == "zenmux" || id == "openrouter") {
                    if (effort != null || thinkingType != null) values["reasoning"] = buildJsonObject {
                        if (effort != null && effort != "none") put("effort", effort)
                        if (thinkingType != null) put("enabled", thinkingEnabled)
                    }
                }
                payload = compatibleProviderBody(id, modelId, JsonObject(values), history, reasoning)
                url = apiPath(base, "chat/completions", id)
                if (id == "spark" && !config["deploymentName"].isNullOrBlank()) {
                    // Spark deployments can use aliases independent of the selected endpoint family.
                    return PreparedChatRequest(Request.Builder().url(url).post(JsonObject(payload + ("model" to JsonPrimitive(config.getValue("deploymentName")))).toString().toRequestBody("application/json".toMediaType())).apply { headers.forEach { (name, value) -> header(name, value) } }.build(), protocol, webSearchEnabled, toolSearch)
                }
            }
        }
        val request = Request.Builder().url(url).post(payload.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (name, value) -> request.header(name, value) }
        return PreparedChatRequest(request.build(), protocol, webSearchEnabled, toolSearch)
    }

    private suspend fun readAttachment(uri: String): ByteArray {
        val fileRepository = files ?: error("Attachment storage is unavailable")
        val id = uri.substringAfterLast('/').takeIf(String::isNotBlank) ?: uri
        return when (val file = fileRepository.readFile(id)) {
            is AnResult.Ok -> file.value
            is AnResult.Err -> throw IOException(file.error.message, file.error.cause)
        }
    }
}

private fun appendVersion(base: String, version: String, path: String): String = if (base.endsWith("/$version")) "$base/$path" else "$base/$version/$path"
private fun apiPath(base: String, path: String, id: String): String {
    if (base.endsWith("/$path")) return base
    val root = base.toHttpUrl()
    // Preserve custom API prefixes. Root-only OpenAI-compatible configuration historically implies /v1.
    val version = root.encodedPath.trim('/')
    return if (version.isEmpty() && id !in setOf("perplexity", "deepseek", "githubcopilot")) "$base/v1/$path" else "$base/$path"
}
private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun contentText(content: JsonElement?): String = when (content) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
    else -> ""
}
private fun imageParts(content: JsonElement?): List<String> = (content as? JsonArray)?.mapNotNull {
    ((it as? JsonObject)?.get("image_url") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
}.orEmpty()
private fun anthropicMessages(messages: JsonArray, thinking: Boolean): JsonArray {
    val result = ArrayList<JsonObject>(messages.size)
    for (raw in messages) {
        val message = raw.jsonObject
        val role = message.string("role")
        if (role == "system") continue
        if (role == "tool") {
            val block = buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", message.string("tool_call_id").orEmpty())
                put("content", contentText(message["content"]))
            }
            val previous = result.lastOrNull()
            val previousContent = previous?.get("content") as? JsonArray
            if (previous != null && previous.string("role") == "user" &&
                previousContent != null && previousContent.all { (it as? JsonObject)?.string("type") == "tool_result" }) {
                result[result.lastIndex] = JsonObject(previous + ("content" to JsonArray(previousContent + block)))
            } else {
                result += buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(listOf(block)))
                }
            }
            continue
        }
        val assistant = role == "assistant"
        result += buildJsonObject {
            put("role", role)
            putJsonArray("content") {
                if (thinking && assistant) add(buildJsonObject { put("type", "thinking"); put("thinking", message.string("reasoning_content").ifBlank { " " }) })
                val text = contentText(message["content"])
                if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text) })
                imageParts(message["content"]).forEach { data ->
                    add(buildJsonObject { put("type", "image"); putJsonObject("source") { put("type", "base64"); put("media_type", data.substringAfter("data:").substringBefore(';')); put("data", data.substringAfter("base64,")) } })
                }
                if (assistant) (message["tool_calls"] as? JsonArray)?.forEach { element ->
                    val call = element as? JsonObject ?: return@forEach
                    val function = call["function"] as? JsonObject ?: return@forEach
                    add(buildJsonObject {
                        put("type", "tool_use")
                        put("id", call.string("id").orEmpty())
                        put("name", function.string("name").orEmpty())
                        put("input", toolInput(function.string("arguments").orEmpty()))
                    })
                }
            }
        }
    }
    return JsonArray(result)
}
internal fun isOpenAiReasoning(model: String): Boolean = OPENAI_REASONING_MODEL.containsMatchIn(model)
internal fun usesResponses(model: String): Boolean {
    if (model.startsWith("openai/")) return false
    val id = model.removePrefix("codex/")
    if (id.startsWith("o1-pro") || id.startsWith("o3-pro") || id.contains("deep-research") || id.startsWith("codex") || id.startsWith("computer-use")) return true
    val match = GPT_RESPONSES_MODEL.find(id) ?: return false
    if (id.contains("-chat")) return false
    return match.groupValues[1].toInt() >= 6 || (match.groupValues[2].toIntOrNull() ?: 0) >= 2 || id.contains("-codex") || id.contains("-pro") || id.startsWith("gpt-5-mini")
}

private val API_VERSION_SUFFIX = Regex("/v\\d+[a-z]*$")
private val KIMI_K3 = Regex("^kimi-k([3-9]|[1-9][0-9])")
private val KIMI_CODE = Regex("^kimi-k2\\.[7-9].*-code")
private val KIMI_TOGGLE = Regex("^kimi-k2\\.[56](?:$|-)")
private val MODERN_CLAUDE = Regex("claude-(?:opus|sonnet|haiku)-(?:4[-.][6-9]|[5-9])|claude-(?:fable|mythos)-[5-9]")
private val OPENAI_O_SERIES = Regex("^o\\d")
private val KIMI_EFFORTS = setOf("low", "high", "max")
private val DIRECT_OPENAI_ROUTES = setOf("openai", "azure", "custom", "aihubmix", "newapi", "zenmux")
private val ATTRIBUTED_PROVIDERS = setOf("openrouter", "vercelaigateway", "togetherai", "higress")
private val OPENAI_NATIVE_RESPONSES_MODEL = Regex("^(?:gpt-[3-9]|o[134](?:$|-)|codex|computer-use)")
private val OPENAI_CHAT_ONLY_MODEL = Regex("^o1-(?:mini|preview)|-chat(?:-|$)|audio|realtime|search-preview|search-api")
private val ENCRYPTED_RESPONSES_PROVIDERS = setOf("openai", "chatgpt", "xai", "supergrok", "meta")
private val OPENAI_REASONING_MODEL = Regex("^(?:gpt-[5-9](?:\\b|[.-])|o[134](?:$|-)|codex|computer-use)")
private val GPT_RESPONSES_MODEL = Regex("^gpt-([5-9])(?:\\.(\\d+))?(?:$|-)")
private val TOOL_PROTOCOLS = setOf("openai", "responses", "anthropic", "gemini", "ollama", "bedrock")
private const val UNSUPPORTED_TOOL_SEARCH =
    "External web search now runs as a model tool, but this model route cannot call tools. Choose an OpenAI-compatible, " +
        "Anthropic, Gemini, Ollama, or Bedrock chat model, or turn web search off."

// Messages APIs require an explicit budget even for a remotely discovered model without catalog limits.
internal const val DEFAULT_ANTHROPIC_OUTPUT_TOKENS = 8192
