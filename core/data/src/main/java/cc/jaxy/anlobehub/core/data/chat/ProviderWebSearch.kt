package cc.jaxy.anlobehub.core.data.chat

/**
 * Native hosted search capability, not a catalog's generic `search` flag.
 *
 * This deliberately allowlists documented text-chat models and snapshots. Unknown, fine-tuned,
 * chat-only, audio/live, image-generation and specialized research/coding models are not assumed
 * compatible. Account access, billing and regional eligibility remain provider-enforced.
 *
 * Sources (reviewed 2026-10-20):
 * https://developers.openai.com/api/docs/guides/tools-web-search
 * https://developers.openai.com/api/docs/models (individual model tool/snapshot tables)
 */
fun supportsWebSearch(providerId: String?, modelId: String): Boolean = when (providerId?.lowercase()) {
    "openai" -> modelId in OPENAI_SEARCH_MODELS
    else -> false
}

/** Run before attachments, credential refresh or any other potentially network-backed preparation. */
internal fun requireWebSearchSupport(
    providerId: String,
    source: String,
    modelId: String,
    reasoning: Map<String, String>,
) {
    require(source == "builtin" && supportsWebSearch(providerId, modelId)) {
        "Native web search is unavailable for $providerId / $modelId. Choose a supported builtin OpenAI text model or configure independent search."
    }
    val effort = reasoning["reasoningEffort"] ?: reasoning["effort"]
    val allowed = OPENAI_SEARCH_MODELS.getValue(modelId)
    val disabled = (reasoning["thinkingType"] ?: reasoning["type"]) == "disabled"
    require(!disabled || allowed.isEmpty() || effort == "none") {
        "Disabled reasoning is incompatible with web search on $modelId; choose an explicit supported effort or the model default."
    }
    require(effort == null || effort in allowed) {
        "Web search with $modelId does not support reasoning effort '$effort'. Choose ${allowed.joinToString()} or the model default; effort will not be increased automatically."
    }
}

private val BASIC_REASONING_EFFORTS = setOf("low", "medium", "high")
private val NON_REASONING_EFFORTS = emptySet<String>()
private val MODERN_REASONING_EFFORTS = setOf("none", "low", "medium", "high", "xhigh")

// Explicit snapshots avoid accidentally accepting future variants merely sharing a prefix.
private val OPENAI_SEARCH_MODELS: Map<String, Set<String>> = buildMap {
    fun models(efforts: Set<String>, vararg ids: String) { ids.forEach { put(it, efforts) } }
    models(NON_REASONING_EFFORTS,
        "gpt-4.1", "gpt-4.1-2025-04-14", "gpt-4.1-mini", "gpt-4.1-mini-2025-04-14",
        "gpt-4o", "gpt-4o-2024-08-06", "gpt-4o-2024-11-20",
        "gpt-4o-mini", "gpt-4o-mini-2024-07-18",
    )
    // GPT-5's minimal effort cannot use web_search. Older o1/o3-mini and nano are excluded.
    models(BASIC_REASONING_EFFORTS,
        "gpt-5", "gpt-5-2025-08-07", "gpt-5-mini", "gpt-5-mini-2025-08-07",
        "o3", "o3-2025-04-16", "o4-mini", "o4-mini-2025-04-16",
    )
    models(setOf("none", "low", "medium", "high"), "gpt-5.1", "gpt-5.1-2025-11-13")
    models(MODERN_REASONING_EFFORTS,
        "gpt-5.2", "gpt-5.2-2025-12-11", "gpt-5.4", "gpt-5.4-2026-03-05",
        "gpt-5.4-mini", "gpt-5.4-mini-2026-03-17", "gpt-5.5", "gpt-5.5-2026-04-23",
    )
    models(MODERN_REASONING_EFFORTS + "max",
        "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-6-sol", "gpt-6-luna",
    )
    models(BASIC_REASONING_EFFORTS + setOf("xhigh", "max"), "gpt-6-astra", "gpt-6.1-sol")
}
