package cc.jaxy.bob.core.data.chat

import kotlinx.serialization.Serializable

@Serializable
enum class WebSearchStage { SEARCHING, READING, COMPLETED, FAILED }

/** Provider-reported search activity, never inferred merely from enabling the tool. */
@Serializable
data class WebSearchActivity(
    val stage: WebSearchStage,
    val queries: List<String> = emptyList(),
    val error: String? = null,
    /** Built-in catalog id ("tavily", "searxng", …); null for native search. Old rows decode as null. */
    val providerId: String? = null,
    /** Display name snapshot; survives provider rename/removal for history. */
    val providerName: String? = null,
    /** False for custom services; brand icon falls back to the initial. */
    val providerBuiltin: Boolean = true,
)

/** Offsets refer to the full plain assistant content, in Kotlin UTF-16 indices. */
@Serializable
data class WebCitation(
    val url: String,
    val title: String = "",
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)
