package cc.jaxy.bob.core.common.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Builds the OpenAI-compatible models endpoint from a provider base URL.
 *
 * Accepts a bare domain or a `.../v1`-style prefix: trims whitespace and trailing
 * slashes, then appends `/models`.
 */
fun buildModelsUrl(baseURL: String): String {
    val trimmed = baseURL.trim()
    require(trimmed.isNotBlank()) { "baseURL is blank" }
    return trimmed.trimEnd('/') + "/models"
}

/**
 * Parses OpenAI-compatible `GET {baseURL}/models` bodies.
 *
 * Accepts `{ "data": [{ "id": ... }] }` and tolerates a bare array `[{ "id": ... }]`.
 * Blank ids are dropped.
 */
fun parseModelIds(body: JsonElement): List<String> {
    val items: List<JsonElement> = when (body) {
        is JsonArray -> body
        is JsonNull -> return emptyList()
        is JsonObject -> (body["data"] as? JsonArray) ?: return emptyList()
        else -> return emptyList()
    }
    return items.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
