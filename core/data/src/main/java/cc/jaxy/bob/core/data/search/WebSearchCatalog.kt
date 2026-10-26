package cc.jaxy.bob.core.data.search

/** Official full search endpoints; an unconfigured SearXNG instance is deliberately never contacted. */
object WebSearchCatalog {
    val providers: List<WebSearchProvider> = listOf(
        builtin("tavily", "Tavily", WebSearchProtocol.TAVILY, "https://api.tavily.com/search", "https://docs.tavily.com/documentation/api-reference/endpoint/search"),
        builtin("brave", "Brave Search", WebSearchProtocol.BRAVE, "https://api.search.brave.com/res/v1/web/search", "https://api-dashboard.search.brave.com/app/documentation/web-search/get-started"),
        builtin("exa", "Exa", WebSearchProtocol.EXA, "https://api.exa.ai/search", "https://exa.ai/docs/reference/search"),
        builtin("serper", "Serper", WebSearchProtocol.SERPER, "https://google.serper.dev/search", "https://serper.dev/"),
        builtin("serpapi", "SerpApi", WebSearchProtocol.SERPAPI, "https://serpapi.com/search.json", "https://serpapi.com/search-api"),
        builtin("bocha", "Bocha", WebSearchProtocol.BOCHA, "https://api.bochaai.com/v1/web-search", "https://open.bochaai.com/"),
        builtin("firecrawl", "Firecrawl", WebSearchProtocol.FIRECRAWL, "https://api.firecrawl.dev/v2/search", "https://docs.firecrawl.dev/api-reference/endpoint/search"),
        builtin("linkup", "Linkup", WebSearchProtocol.LINKUP, "https://api.linkup.so/v1/search", "https://docs.linkup.so/pages/documentation/endpoints/search/reference"),
        builtin("you", "You.com", WebSearchProtocol.YOU, "https://ydc-index.io/v1/search", "https://you.com/docs/api-reference/search/v1-search"),
        builtin("searxng", "SearXNG", WebSearchProtocol.SEARXNG, "", "https://docs.searxng.org/dev/search_api.html"),
    )

    private fun builtin(id: String, name: String, protocol: WebSearchProtocol, endpoint: String, docsUrl: String) =
        WebSearchProvider(id, name, protocol, endpoint, builtin = true, docsUrl = docsUrl)
}
