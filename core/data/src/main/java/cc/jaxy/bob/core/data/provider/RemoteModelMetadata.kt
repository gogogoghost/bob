package cc.jaxy.bob.core.data.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class RemoteModelDescriptor(val id: String, val metadata: JsonObject = JsonObject(emptyMap()))

/** Extracts the portable catalogue fields a provider's model-list payload exposes. */
internal fun remoteModelDescriptor(model: JsonObject, id: String): RemoteModelDescriptor {
    val metadata = buildJsonObject {
        put("type", "chat")
        displayName(model, id)?.let { put("displayName", it) }
        model.text("description")?.trim()?.takeIf(String::isNotBlank)?.let { put("description", it) }
        contextWindow(model)?.let { put("contextWindowTokens", it) }
        maxOutput(model)?.let { put("maxOutput", it) }
        knowledgeCutoff(model)?.let { put("knowledgeCutoff", it) }
        family(model)?.let { put("family", it) }
        abilities(model)?.takeIf { it.isNotEmpty() }?.let { put("abilities", it) }
    }
    return RemoteModelDescriptor(id, metadata)
}

private fun displayName(model: JsonObject, id: String): String? =
    model.text("display_name")?.takeIf(String::isNotBlank)
        ?: model.text("displayName")?.takeIf(String::isNotBlank)
        ?: model.text("label")?.takeIf(String::isNotBlank)
        ?: model.text("name")?.takeIf {
            it.isNotBlank() && !it.equals(id, ignoreCase = true) && !it.startsWith("models/")
        }

private fun family(model: JsonObject): String? =
    model.text("family")?.takeIf(String::isNotBlank)
        ?: model.obj("details")?.text("family")?.takeIf(String::isNotBlank)
        ?: model.obj("architecture")?.text("family")?.takeIf(String::isNotBlank)

private fun contextWindow(model: JsonObject): Int? {
    val limits = model.obj("limits") ?: model.obj("capabilities")?.obj("limits")
    return firstPositive(
        model.number("context_length"),
        model.number("context_window"),
        model.number("max_context_length"),
        model.number("max_input_tokens"),
        model.number("max_model_len"),
        model.number("input_token_limit"),
        model.number("inputTokenLimit"),
        limits?.number("max_context_window_tokens"),
        limits?.number("max_input_tokens"),
    )
}

private fun maxOutput(model: JsonObject): Int? {
    val limits = model.obj("limits") ?: model.obj("capabilities")?.obj("limits")
    return firstPositive(
        model.number("max_output_tokens"),
        model.number("output_token_limit"),
        model.number("outputTokenLimit"),
        model.number("max_output"),
        limits?.number("max_output_tokens"),
    )
}

private fun knowledgeCutoff(model: JsonObject): String? =
    model.text("knowledge_cutoff")?.takeIf(String::isNotBlank)
        ?: model.text("knowledgeCutoff")?.takeIf(String::isNotBlank)

private fun abilities(model: JsonObject): JsonObject? {
    val capabilities = model.obj("capabilities")
    val parameters = (model["supported_parameters"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
        .toSet()
    val inputs = modalities(model.obj("architecture")?.get("input_modalities") ?: model["input_modalities"])
    val outputs = modalities(model.obj("architecture")?.get("output_modalities") ?: model["output_modalities"])
    val reasoning = capabilities?.bool("reasoning") == true || model.bool("reasoning") == true ||
        model["thinking"] is JsonObject || parameters.any { it in REASONING_PARAMETERS }
    val tools = capabilities?.bool("tools") == true || capabilities?.bool("function_call") == true ||
        model.bool("tools") == true || parameters.any { it in TOOL_PARAMETERS }
    val vision = capabilities?.bool("vision") == true || model.bool("vision") == true ||
        inputs.any { it in setOf("image", "vision") }
    val files = capabilities?.bool("files") == true || inputs.any { it in setOf("file", "files", "pdf") }
    val audio = capabilities?.bool("audio") == true || model.bool("audio") == true || "audio" in inputs
    val video = capabilities?.bool("video") == true || model.bool("video") == true || "video" in inputs
    val imageOutput = capabilities?.bool("image_output") == true || "image" in outputs
    val structured = capabilities?.bool("structured_output") == true ||
        parameters.any { it in STRUCTURED_PARAMETERS }
    val search = capabilities?.bool("search") == true || parameters.any { it in SEARCH_PARAMETERS }
    if (!reasoning && !tools && !vision && !files && !audio && !video && !imageOutput && !structured && !search) {
        return null
    }
    return buildJsonObject {
        if (vision) put("vision", true)
        if (reasoning) put("reasoning", true)
        if (tools) put("functionCall", true)
        if (files) put("files", true)
        if (audio) put("audio", true)
        if (video) put("video", true)
        if (imageOutput) put("imageOutput", true)
        if (search) put("search", true)
        if (structured) put("structuredOutput", true)
    }
}

private fun modalities(value: kotlinx.serialization.json.JsonElement?): Set<String> = when (value) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.toSet()
    is JsonPrimitive -> value.contentOrNull.orEmpty().split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet()
    else -> emptySet()
}

private fun firstPositive(vararg values: Long?): Int? =
    values.firstOrNull { it != null && it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Long? = text(key)?.trim()?.toLongOrNull()
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private val REASONING_PARAMETERS = setOf(
    "reasoning", "include_reasoning", "reasoning_effort", "reasoning_content", "thinking",
)
private val TOOL_PARAMETERS = setOf(
    "tools", "tool_choice", "function_call", "functions", "parallel_tool_calls",
)
private val STRUCTURED_PARAMETERS = setOf(
    "response_format", "structured_outputs", "structured_output", "json_schema", "json_mode",
)
private val SEARCH_PARAMETERS = setOf(
    "web_search", "web_search_preview", "search",
)
