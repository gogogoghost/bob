package cc.jaxy.anlobehub.core.data.chat

import okhttp3.HttpUrl.Companion.toHttpUrl

/** OpenCode Console's documented protocol matrix, not the model-list endpoint's API root. */
internal fun openCodeRoute(providerId: String, base: String, model: String): Pair<String, String> {
    require(!model.startsWith("jev-")) { "Jev uses SystemOne state/questions decisions, not conversational text generation" }
    val go = providerId == "opencodecodingplan"
    val protocol = when {
        model.startsWith("claude-") -> "anthropic"
        model.startsWith("gemini-") -> "gemini"
        model.startsWith("gpt-") || model.startsWith("grok-") || model.startsWith("muse-spark-") || OPENAI_O_MODEL.containsMatchIn(model) -> "responses"
        model.startsWith("qwen") && (go || model != "qwen3.8-max") -> "anthropic"
        go && model.startsWith("minimax") && !model.endsWith("-free") -> "anthropic"
        else -> "openai"
    }
    val url = base.toHttpUrl()
    val modern = url.encodedPath.contains("/inference") || url.host == "opencode.ai"
    if (!modern) return protocol to base // A user-owned gateway may intentionally expose one shared API root.
    val root = if (url.host == "opencode.ai" && url.encodedPath.startsWith("/zen")) {
        if (go) "https://opencode.ai/inference/go" else "https://opencode.ai/inference"
    } else base.replace(OPENCODE_PROTOCOL_SUFFIX, "").removeSuffix("/v1").removeSuffix("/v1beta")
    val suffix = when (protocol) {
        "anthropic" -> "anthropic/v1"
        "gemini" -> "google/v1beta"
        else -> "openai/v1"
    }
    return protocol to "$root/$suffix"
}

private val OPENAI_O_MODEL = Regex("^o\\d")
private val OPENCODE_PROTOCOL_SUFFIX = Regex("/(?:openai|anthropic|google)/(?:v1|v1beta)$")
