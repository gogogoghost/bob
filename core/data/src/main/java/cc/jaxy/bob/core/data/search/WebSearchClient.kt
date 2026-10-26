package cc.jaxy.bob.core.data.search

import cc.jaxy.bob.core.data.chat.normalizeSearxngUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * A single, explicit provider request; never redirects, retries, or changes search services.
 * Protocol references live beside each adapter below. Credentials/query/response bodies are
 * deliberately excluded from diagnostics, including nested exception causes.
 */
class WebSearchClient(client: OkHttpClient) {
    private val client = client.newBuilder().apply {
        // The inference client's interceptors/listeners may log URLs or install model credentials.
        // Keep its TLS/proxy configuration, but do not inherit those observers or authentication.
        interceptors().clear()
        networkInterceptors().clear()
    }
        .eventListener(EventListener.NONE)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!response.isSuccessful) {
                val status = response.code
                response.close()
                // Reject before RetryAndFollowUpInterceptor sees 408/503/421, including GETs.
                // retryOnConnectionFailure(false) alone does not disable HTTP status retries.
                val hint = if (status == 403) " Check credentials, access restrictions, and JSON output settings." else ""
                throw SearchResponseException("Web search returned HTTP $status.$hint")
            }
            response
        }
        .build()

    suspend fun search(provider: WebSearchProvider, apiKey: String?, query: String): List<WebSearchResult> = coroutineScope {
        currentCoroutineContext().ensureActive()
        require(provider.enabled) { "The selected web search service is disabled." }
        require(query.isNotBlank()) { "Web search query must not be blank." }
        val key = apiKey?.trim().orEmpty()
        if (provider.requiresApiKey) {
            require(key.isNotEmpty()) { "Configure an API key for the selected web search service." }
            require(key.all { it.code in 33..126 }) { "The web search API key contains invalid characters." }
        }
        val request = request(provider, key, query)
        val call = client.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        val body = response.body
                        val subtype = body.contentType()?.subtype
                        if (subtype != "json" && subtype?.endsWith("+json") != true) {
                            throw SearchResponseException("Web search did not return JSON. Check the service endpoint and access settings.")
                        }
                        if (body.contentLength() > MAX_RESPONSE_BYTES) {
                            throw SearchResponseException("Web search JSON response is too large.")
                        }
                        val source = body.source()
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) {
                            throw SearchResponseException("Web search JSON response is too large.")
                        }
                        currentCoroutineContext().ensureActive()
                        decode(provider.protocol, source.readUtf8())
                    }
                } catch (error: SearchResponseException) {
                    currentCoroutineContext().ensureActive()
                    throw IOException(error.message)
                } catch (_: IOException) {
                    currentCoroutineContext().ensureActive()
                    // OkHttp failures can include the full URL (SerpApi puts its key in it).
                    // Never preserve the underlying exception, its message, or suppressed errors.
                    throw IOException("Web search request failed. Check connectivity and service configuration.")
                }
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun request(provider: WebSearchProvider, key: String, query: String): Request {
        val endpoint = if (provider.protocol == WebSearchProtocol.SEARXNG) normalizeSearxngUrl(provider.endpoint) else provider.endpoint.trim()
        require(endpoint.isNotEmpty() && HTTP_SCHEME.containsMatchIn(endpoint) && endpoint.none { it.isISOControl() }) {
            "Configure a valid HTTP(S) web search endpoint."
        }
        val url = requireNotNull(endpoint.toHttpUrlOrNull()) { "Web search endpoint is invalid." }
        require(url.username.isEmpty() && url.password.isEmpty() && !endpoint.substringAfter("://").substringBefore('/').contains('@')) {
            "Web search endpoint must not contain credentials."
        }
        require(url.query == null && url.fragment == null) { "Web search endpoint must not contain a query or fragment." }
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        when (provider.protocol) {
            // https://docs.tavily.com/documentation/api-reference/endpoint/search
            WebSearchProtocol.TAVILY -> builder.bearer(key).json(buildJsonObject {
                put("query", query); put("max_results", MAX_RESULTS); put("search_depth", "basic")
                put("include_answer", false); put("include_raw_content", false)
            })
            // https://api-dashboard.search.brave.com/app/documentation/web-search/get-started
            WebSearchProtocol.BRAVE -> builder.header("X-Subscription-Token", key).url(url.newBuilder()
                .addQueryParameter("q", query).addQueryParameter("count", MAX_RESULTS.toString()).build()).get()
            // https://exa.ai/docs/reference/search (request highlights; bare search has no snippets)
            WebSearchProtocol.EXA -> builder.header("x-api-key", key).json(buildJsonObject {
                put("query", query); put("numResults", MAX_RESULTS); put("type", "auto")
                put("contents", buildJsonObject { put("highlights", true) })
            })
            // https://serper.dev/ and its official API playground https://serper.dev/playground
            WebSearchProtocol.SERPER -> builder.header("X-API-KEY", key).json(buildJsonObject {
                put("q", query); put("num", MAX_RESULTS)
            })
            // https://serpapi.com/search-api (the documented API key must be a query parameter)
            WebSearchProtocol.SERPAPI -> builder.url(url.newBuilder().addQueryParameter("engine", "google")
                .addQueryParameter("q", query).addQueryParameter("api_key", key)
                .addQueryParameter("output", "json").build()).get()
            // https://open.bochaai.com/ (official public curl and webPages response example)
            WebSearchProtocol.BOCHA -> builder.bearer(key).json(buildJsonObject {
                put("query", query); put("count", MAX_RESULTS); put("summary", false)
            })
            // https://docs.firecrawl.dev/api-reference/endpoint/search (v2, not v1's data array)
            WebSearchProtocol.FIRECRAWL -> builder.bearer(key).json(buildJsonObject {
                put("query", query); put("limit", MAX_RESULTS)
                put("sources", JsonArray(listOf(buildJsonObject { put("type", "web") })))
            })
            // https://docs.linkup.so/pages/documentation/endpoints/search/reference
            WebSearchProtocol.LINKUP -> builder.bearer(key).json(buildJsonObject {
                put("q", query); put("depth", "fast"); put("outputType", "searchResults"); put("maxResults", MAX_RESULTS)
            })
            // https://you.com/docs/api-reference/search/v1-search (current POST API)
            WebSearchProtocol.YOU -> builder.header("X-API-Key", key).json(buildJsonObject {
                put("query", query); put("count", MAX_RESULTS)
                put("extraction", buildJsonObject {
                    put("extraction_mode", "highlights")
                    put("highlights", buildJsonObject { put("total_max_tokens", 2048) })
                })
            })
            // https://docs.searxng.org/dev/search_api.html (instance must enable JSON output)
            WebSearchProtocol.SEARXNG -> builder.post(FormBody.Builder().add("q", query).add("format", "json").build().oneShot())
        }
        return builder.build()
    }

    private fun decode(protocol: WebSearchProtocol, text: String): List<WebSearchResult> {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            throw SearchResponseException("Web search returned invalid JSON.")
        } ?: throw SearchResponseException("Web search returned an invalid JSON search response.")
        if ((root["error"] != null && root["error"] != JsonNull) || root["success"] == JsonPrimitive(false) ||
            (protocol == WebSearchProtocol.BOCHA && root["code"] != null && root["code"] != JsonPrimitive(200))) {
            throw SearchResponseException("The web search service reported an error. Check credentials, quota, and service settings.")
        }
        val items = when (protocol) {
            WebSearchProtocol.TAVILY, WebSearchProtocol.EXA, WebSearchProtocol.LINKUP, WebSearchProtocol.SEARXNG -> root["results"]
            WebSearchProtocol.BRAVE -> root.obj("web")?.get("results")
            WebSearchProtocol.SERPER -> root["organic"]
            WebSearchProtocol.SERPAPI -> root["organic_results"]
            WebSearchProtocol.BOCHA -> (root.obj("data") ?: root).obj("webPages")?.get("value")
            WebSearchProtocol.FIRECRAWL -> root.obj("data")?.get("web")
            WebSearchProtocol.YOU -> root.obj("results")?.get("web")
        } as? JsonArray ?: throw SearchResponseException("Web search JSON response is missing search results.")
        val hits = ArrayList<WebSearchResult>(MAX_RESULTS)
        val seen = HashSet<String>(MAX_RESULTS)
        for (element in items) {
            val item = element as? JsonObject ?: continue
            val linkKey = if (protocol == WebSearchProtocol.SERPER || protocol == WebSearchProtocol.SERPAPI) "link" else "url"
            val url = item.text(linkKey)?.let(::safeResultUrl) ?: continue
            val content = when (protocol) {
                WebSearchProtocol.TAVILY, WebSearchProtocol.LINKUP, WebSearchProtocol.SEARXNG -> item.text("content")
                WebSearchProtocol.BRAVE, WebSearchProtocol.FIRECRAWL -> item.text("description")
                WebSearchProtocol.EXA -> item.passages("highlights")
                WebSearchProtocol.SERPER, WebSearchProtocol.SERPAPI, WebSearchProtocol.BOCHA -> item.text("snippet")
                WebSearchProtocol.YOU -> item.obj("contents")?.passages("highlights")
            }?.trim()?.takeIf { it.isNotBlank() } ?: continue
            val identity = url.newBuilder().fragment(null).build().toString()
            if (!seen.add(identity)) continue
            val titleKey = if (protocol == WebSearchProtocol.BOCHA || protocol == WebSearchProtocol.LINKUP) "name" else "title"
            hits.add(WebSearchResult(url.toString(), item.text(titleKey).orEmpty().replace(TITLE_HIGHLIGHTS, "").take(500), content.take(4000)))
            if (hits.size == MAX_RESULTS) break
        }
        if (hits.isEmpty()) throw SearchResponseException("Web search returned no usable HTTP(S) search results with source snippets.")
        return hits
    }
}

private class SearchResponseException(message: String) : IOException(message)
private fun Request.Builder.bearer(key: String) = header("Authorization", "Bearer $key")
private fun Request.Builder.json(value: JsonObject) = post(value.toString().toRequestBody(JSON_TYPE).oneShot())
private fun RequestBody.oneShot(): RequestBody {
    val original = this
    return object : RequestBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
    }
}
private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.passages(key: String): String? = (this[key] as? JsonArray)?.mapNotNull {
    (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull?.takeIf { value -> value.isNotBlank() }
}?.joinToString("\n")?.takeIf { it.isNotBlank() }
private fun safeResultUrl(text: String): HttpUrl? {
    if (text != text.trim() || text.any { it.isISOControl() } || !HTTP_SCHEME.containsMatchIn(text)) return null
    val url = text.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || text.substringAfter("://").substringBefore('/').contains('@')) return null
    return url
}
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val HTTP_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
private val TITLE_HIGHLIGHTS = Regex("</?(?:b|strong|em|mark|span)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private const val MAX_RESULTS = 5
private const val MAX_RESPONSE_BYTES = 1_048_576L
