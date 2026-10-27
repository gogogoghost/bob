package cc.jaxy.bob.core.data.model

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Best-effort identity for a model that a provider listing exposes only as an ID. */
internal data class InferredModelInfo(
    val displayName: String,
    val family: String?,
    val generation: String?,
    val familyVersion: String?,
    val abilities: JsonObject?,
)

internal fun inferModelInfo(modelId: String): InferredModelInfo {
    val id = modelId.substringAfterLast('/').lowercase(Locale.ROOT)
    val family = modelFamily(id)
    val version = modelVersion(id, family)
    val generation = modelGeneration(family, version)
    val familyVersion = modelFamilyVersion(family, version)
    val reasoning = supportsReasoning(id, family, version)
    val tools = supportsTools(family, version)
    val abilities = buildJsonObject {
        if (reasoning) put("reasoning", true)
        if (tools) put("functionCall", true)
    }
    return InferredModelInfo(
        displayName = prettyModelName(modelId),
        family = family,
        generation = generation,
        familyVersion = familyVersion,
        abilities = abilities.takeIf { it.isNotEmpty() },
    )
}

/** Fields that are safe to infer without provider data; never invents token limits. */
internal fun inferredModelMetadata(modelId: String): JsonObject {
    val info = inferModelInfo(modelId)
    return buildJsonObject {
        put("type", "chat")
        put("displayName", info.displayName)
        info.family?.let { put("family", it) }
        info.generation?.let { put("generation", it) }
        info.abilities?.let { put("abilities", it) }
    }
}

/**
 * Merge metadata sources in priority order. The first non-null value wins for
 * scalar fields; abilities are merged key-by-key so a parsed capability does
 * not erase a catalog one (or vice versa).
 */
internal fun mergeModelMetadata(vararg sources: JsonObject?): JsonObject {
    val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    val abilities = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    for (source in sources) {
        source ?: continue
        for ((key, value) in source) {
            if (key == "abilities" && value is JsonObject) {
                for ((ability, enabled) in value) {
                    if (ability !in abilities) abilities[ability] = enabled
                }
            } else if (key !in fields) {
                fields[key] = value
            }
        }
    }
    if (abilities.isNotEmpty()) fields["abilities"] = JsonObject(abilities)
    return JsonObject(fields)
}

internal fun modelFamilyVersion(modelId: String): String? = inferModelInfo(modelId).familyVersion

/**
 * Family fallbacks describe an older sibling (deepseek-v4 for v4.1), so a
 * persisted inference is more precise than that fallback. Catalogue metadata
 * (identified by its revision marker) and provider-supplied generations win.
 */
internal fun finalizeModelMetadata(
    merged: JsonObject,
    stored: JsonObject,
    inferred: JsonObject,
    providerGeneration: kotlinx.serialization.json.JsonElement? = null,
): JsonObject {
    if (stored["catalogRevision"] != null || providerGeneration != null) return merged
    val generation = inferred["generation"] ?: return merged
    return JsonObject(merged + ("generation" to generation))
}

private fun modelFamily(id: String): String? = when {
    id.startsWith("gpt-") -> "gpt"
    id.matches(Regex("^o\\d.*")) -> "o-series"
    id.startsWith("claude") -> "claude"
    id.startsWith("gemini") -> "gemini"
    id.startsWith("grok") -> "grok"
    id.startsWith("deepseek") -> "deepseek"
    id.startsWith("glm") || id.startsWith("chatglm") -> "glm"
    id.startsWith("kimi") || id.startsWith("moonshot") -> "kimi"
    id.startsWith("qwen") || id.startsWith("qwq") -> "qwen"
    id.startsWith("minimax") -> "minimax"
    id.startsWith("mimo") -> "mimo"
    id.startsWith("hy") || id.startsWith("hunyuan") -> "hunyuan"
    id.startsWith("step") -> "step"
    id.startsWith("muse-spark") -> "muse"
    id.startsWith("longcat") -> "longcat"
    id.startsWith("ernie") -> "ernie"
    id.startsWith("doubao") -> "doubao"
    id.startsWith("llama") -> "llama"
    id.startsWith("mistral") || id.startsWith("magistral") -> "mistral"
    id.startsWith("command") -> "cohere"
    id.startsWith("sonar") -> "perplexity"
    else -> null
}

private fun modelVersion(id: String, family: String?): List<Int>? {
    val regex = when (family) {
        "gpt" -> Regex("^gpt-([0-9]+(?:[.-][0-9]+)?)")
        "o-series" -> Regex("^o([0-9]+)")
        "claude" -> Regex("^claude-(?:[a-z]+-)?([0-9]+)(?:[.-]([0-9]+))?")
        "gemini" -> Regex("^gemini-([0-9]+(?:[.-][0-9]+)?)")
        "grok" -> Regex("^grok-([0-9]+(?:[.-][0-9]+)?)")
        "deepseek" -> Regex("^deepseek-(?:r|v)([0-9]+(?:[.-][0-9]+)?)")
        "glm" -> Regex("^glm-([0-9]+(?:[.-][0-9]+)?)")
        "kimi" -> Regex("^kimi-k([0-9]+(?:[.-][0-9]+)?)")
        "qwen" -> Regex("^qwen-?([0-9]+(?:[.-][0-9]+)?)")
        "minimax" -> Regex("^minimax-m([0-9]+(?:[.-][0-9]+)?)")
        "mimo" -> Regex("^mimo-v([0-9]+(?:[.-][0-9]+)?)")
        "hunyuan" -> Regex("^(?:hy|hunyuan-)([0-9]+(?:[.-][0-9]+)?)")
        "step" -> Regex("^step-([0-9]+(?:[.-][0-9]+)?)")
        "muse" -> Regex("^muse-spark-([0-9]+(?:[.-][0-9]+)?)")
        "longcat" -> Regex("^longcat-([0-9]+(?:[.-][0-9]+)?)")
        "ernie" -> Regex("^ernie-([0-9]+(?:[.-][0-9]+)?)")
        "doubao" -> Regex("^doubao-(?:seed-)?([0-9]+(?:[.-][0-9]+)?)")
        "llama" -> Regex("^llama-?([0-9]+(?:[.-][0-9]+)?)")
        "mistral" -> Regex("^(?:mistral|magistral)-([0-9]+(?:[.-][0-9]+)?)")
        else -> null
    } ?: return null
    return regex.find(id)?.groupValues?.drop(1)
        ?.flatMap { it.split('.', '-') }
        ?.mapNotNull(String::toIntOrNull)
        ?.takeIf(List<Int>::isNotEmpty)
}

private fun modelGeneration(family: String?, version: List<Int>?): String? {
    if (family == null || version == null) return null
    val value = version.joinToString(".")
    return when (family) {
        "deepseek" -> "deepseek-v$value"
        "glm" -> "glm-$value"
        "kimi" -> "kimi-k$value"
        "qwen" -> "qwen$value"
        "gpt" -> "gpt-$value"
        "gemini" -> "gemini-$value"
        "grok" -> "grok-$value"
        "claude" -> "claude-${version.joinToString("-")}"
        "minimax" -> "minimax-m$value"
        "mimo" -> "mimo-v$value"
        "hunyuan" -> "hunyuan-$value"
        "step" -> "step-$value"
        "muse" -> "muse-spark-$value"
        "longcat" -> "longcat-$value"
        "ernie" -> "ernie-$value"
        "doubao" -> "doubao-$value"
        "llama" -> "llama-$value"
        "mistral" -> "mistral-$value"
        "cohere" -> "command-$value"
        "perplexity" -> "sonar-$value"
        else -> null
    }
}

private fun modelFamilyVersion(family: String?, version: List<Int>?): String? {
    val major = version?.firstOrNull() ?: return null
    return when (family) {
        "deepseek" -> "deepseek-v$major"
        "glm" -> "glm-$major"
        "kimi" -> "kimi-k$major"
        "qwen" -> "qwen$major"
        "gpt" -> "gpt-$major"
        "gemini" -> "gemini-$major"
        "grok" -> "grok-$major"
        "claude" -> "claude-$major"
        "minimax" -> "minimax-m$major"
        "mimo" -> "mimo-v$major"
        "hunyuan" -> "hunyuan-$major"
        "step" -> "step-$major"
        "muse" -> "muse-spark-$major"
        "longcat" -> "longcat-$major"
        "ernie" -> "ernie-$major"
        "doubao" -> "doubao-$major"
        "llama" -> "llama-$major"
        "mistral" -> "mistral-$major"
        else -> null
    }
}

private fun supportsReasoning(id: String, family: String?, version: List<Int>?): Boolean = when (family) {
    "gpt" -> atLeast(version, 5)
    "o-series" -> true
    "claude" -> atLeast(version, 4) || version == listOf(3, 7)
    "gemini" -> atLeast(version, 2, 5)
    "grok" -> atLeast(version, 3)
    "deepseek" -> id.startsWith("deepseek-r") || atLeast(version, 3, 1)
    "glm" -> atLeast(version, 4, 5)
    "kimi" -> atLeast(version, 2, 5)
    "qwen" -> id.startsWith("qwq") || atLeast(version, 3)
    "minimax" -> atLeast(version, 2, 5)
    "mimo" -> atLeast(version, 2)
    "hunyuan" -> atLeast(version, 3)
    "step" -> atLeast(version, 3, 5)
    "muse" -> atLeast(version, 1, 2)
    "longcat" -> atLeast(version, 2)
    "ernie" -> atLeast(version, 5)
    "doubao" -> atLeast(version, 2)
    else -> false
}

private fun supportsTools(family: String?, version: List<Int>?): Boolean = when (family) {
    "gpt", "o-series", "claude", "gemini", "grok", "deepseek", "glm", "kimi", "qwen",
    "minimax", "mimo", "hunyuan", "step", "muse", "longcat", "ernie", "doubao", "cohere" -> true
    "llama" -> atLeast(version, 3, 1)
    "mistral" -> true
    else -> false
}

private fun atLeast(version: List<Int>?, vararg expected: Int): Boolean {
    if (version == null) return false
    for (index in expected.indices) {
        val actual = version.getOrNull(index) ?: 0
        if (actual != expected[index]) return actual > expected[index]
    }
    return true
}

private fun prettyModelName(modelId: String): String {
    val tokens = modelId.substringAfterLast('/').replace('_', '-').split('-').filter(String::isNotBlank)
    if (tokens.isEmpty()) return modelId
    return tokens.mapIndexed { index, token ->
        if (index == 0) firstToken(token) else restToken(token)
    }.joinToString(" ")
}

private fun firstToken(token: String): String {
    val lower = token.lowercase(Locale.ROOT)
    val known = listOf(
        "deepseek" to "DeepSeek", "minimax" to "MiniMax", "mistral" to "Mistral",
        "magistral" to "Magistral", "longcat" to "LongCat", "hunyuan" to "Hunyuan",
        "gemini" to "Gemini", "claude" to "Claude", "qwen" to "Qwen", "kimi" to "Kimi",
        "mimo" to "MiMo", "step" to "Step", "muse" to "Muse", "llama" to "Llama",
        "ernie" to "Ernie", "doubao" to "Doubao", "command" to "Command", "sonar" to "Sonar",
        "glm" to "GLM", "gpt" to "GPT", "hy" to "HY",
    )
    known.firstOrNull { lower.startsWith(it.first) }?.let { (prefix, display) ->
        return display + token.substring(prefix.length)
    }
    if (lower.matches(Regex("^o\\d.*"))) return token
    return token.replaceFirstChar { it.uppercase() }
}

private fun restToken(token: String): String = when {
    token.matches(Regex("^v\\d.*", RegexOption.IGNORE_CASE)) -> "V" + token.drop(1)
    token.matches(Regex("^\\d+(?:\\.\\d+)*$")) -> token
    token.length <= 4 && token.all { it.isUpperCase() } -> token
    else -> token.replaceFirstChar { it.uppercase() }
}
