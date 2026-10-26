package cc.jaxy.bob.core.data.search

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WebSearchMode {
    @SerialName("off") OFF,
    @SerialName("native") NATIVE,
    @SerialName("provider") PROVIDER,
}

/** Explicit routing: native search never silently switches to an external search service. */
@Serializable
data class WebSearchPolicy(
    val mode: WebSearchMode = WebSearchMode.OFF,
    val providerId: String? = null,
) {
    init {
        require(if (mode == WebSearchMode.PROVIDER) !providerId.isNullOrBlank() else providerId == null) {
            "An external search policy requires exactly one search provider."
        }
    }
}

@Serializable
enum class WebSearchProtocol(val displayName: String) {
    TAVILY("Tavily"), BRAVE("Brave Search"), EXA("Exa"), SERPER("Serper"),
    SERPAPI("SerpApi"), BOCHA("Bocha"), FIRECRAWL("Firecrawl"),
    LINKUP("Linkup"), YOU("You.com"), SEARXNG("SearXNG"),
}

@Serializable
data class WebSearchProvider(
    val id: String,
    val name: String,
    val protocol: WebSearchProtocol,
    val endpoint: String,
    val enabled: Boolean = false,
    val builtin: Boolean = false,
    val docsUrl: String = "",
) {
    val requiresApiKey: Boolean get() = protocol != WebSearchProtocol.SEARXNG
}

data class WebSearchResult(val url: String, val title: String, val content: String)

/** Non-secret configuration is backed up; API keys use the existing encrypted no-backup vault. */
interface WebSearchProviderRepository {
    val providers: Flow<List<WebSearchProvider>>
    /** null retains the existing key; an empty string explicitly clears it. Throws on failure. */
    suspend fun save(provider: WebSearchProvider, apiKey: String? = null)
    suspend fun delete(id: String)
    suspend fun apiKey(id: String): String?
}
