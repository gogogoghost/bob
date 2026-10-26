package cc.jaxy.anlobehub.core.data.search

import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real HTTP consumers: each adapter writes its documented request and decodes source snippets. */
class WebSearchClientTest {
    private val search = WebSearchClient(OkHttpClient())
    private val key = "secret-test-key"
    private val query = "private question & 中文 + /?"

    @Test
    fun `all ten protocols authenticate encode queries and decode their official response shapes`() = runBlocking {
        for (protocol in WebSearchProtocol.entries) {
            Server(fixture(protocol, listOf(hit("https://source.example/article")))).use { server ->
                val results = search.search(provider(protocol, server.root + "/prefix/search"), key, query)
                assertEquals(listOf(WebSearchResult("https://source.example/article", "Source title", "Actual source passage")), results)
                val request = checkNotNull(server.request.get())
                assertEquals("/prefix/search", request.url.encodedPath)
                assertEquals(1, server.requests.get())
                assertEquals("application/json", request.headers["accept"])
                when (protocol) {
                    WebSearchProtocol.BRAVE -> {
                        assertEquals("GET", request.method)
                        assertEquals(query, request.url.queryParameter("q"))
                        assertEquals("5", request.url.queryParameter("count"))
                        assertEquals(key, request.headers["x-subscription-token"])
                    }
                    WebSearchProtocol.SERPAPI -> {
                        assertEquals("GET", request.method)
                        assertEquals(query, request.url.queryParameter("q"))
                        assertEquals(key, request.url.queryParameter("api_key"))
                        assertEquals("google", request.url.queryParameter("engine"))
                        assertEquals("json", request.url.queryParameter("output"))
                    }
                    WebSearchProtocol.SEARXNG -> {
                        assertEquals("POST", request.method)
                        assertTrue(request.headers.getValue("content-type").startsWith("application/x-www-form-urlencoded"))
                        val form = ("http://localhost/?" + request.body).toHttpUrl()
                        assertEquals(query, form.queryParameter("q"))
                        assertEquals("json", form.queryParameter("format"))
                    }
                    else -> {
                        assertEquals("POST", request.method)
                        assertNull(request.url.query)
                        assertTrue(request.headers.getValue("content-type").startsWith("application/json"))
                        val body = Json.parseToJsonElement(request.body).jsonObject
                        val queryField = if (protocol == WebSearchProtocol.SERPER || protocol == WebSearchProtocol.LINKUP) "q" else "query"
                        assertEquals(query, body[queryField]?.jsonPrimitive?.content)
                        when (protocol) {
                            WebSearchProtocol.TAVILY -> {
                                assertEquals(JsonPrimitive(5), body["max_results"])
                                assertEquals(JsonPrimitive(false), body["include_answer"])
                            }
                            WebSearchProtocol.EXA -> {
                                assertEquals(JsonPrimitive(5), body["numResults"])
                                assertEquals(JsonPrimitive(true), body["contents"]?.jsonObject?.get("highlights"))
                            }
                            WebSearchProtocol.SERPER -> assertEquals(JsonPrimitive(5), body["num"])
                            WebSearchProtocol.BOCHA -> {
                                assertEquals(JsonPrimitive(5), body["count"])
                                assertEquals(JsonPrimitive(false), body["summary"])
                            }
                            WebSearchProtocol.FIRECRAWL -> {
                                assertEquals(JsonPrimitive(5), body["limit"])
                                assertEquals(Json.parseToJsonElement("[{\"type\":\"web\"}]"), body["sources"])
                            }
                            WebSearchProtocol.LINKUP -> {
                                assertEquals(JsonPrimitive("searchResults"), body["outputType"])
                                assertEquals(JsonPrimitive("fast"), body["depth"])
                                assertEquals(JsonPrimitive(5), body["maxResults"])
                            }
                            WebSearchProtocol.YOU -> {
                                assertEquals(JsonPrimitive(5), body["count"])
                                assertEquals(JsonPrimitive("highlights"), body["extraction"]?.jsonObject?.get("extraction_mode"))
                            }
                            else -> error("Unexpected JSON protocol")
                        }
                        val header = when (protocol) {
                            WebSearchProtocol.EXA, WebSearchProtocol.SERPER, WebSearchProtocol.YOU -> "x-api-key"
                            else -> "authorization"
                        }
                        val expected = if (header == "authorization") "Bearer $key" else key
                        assertEquals(expected, request.headers[header])
                    }
                }
                if (protocol != WebSearchProtocol.SERPAPI) assertFalse(request.url.toString().contains(key))
                assertFalse(request.body.contains(key))
                if (protocol == WebSearchProtocol.SEARXNG) assertNull(request.headers["authorization"])
            }
        }
    }

    @Test
    fun `all protocols filter unsafe blank and duplicate sources and cap meaningful results at five`() = runBlocking {
        val items = listOf(
            hit("javascript:alert(1)"), hit("file:///private"), hit("https://user:password@source.example/"),
            hit(" https://source.example/leading"), hit("https://source.example/control\n"),
            hit("https://source.example/empty", " "), hit("https://source.example/typed", typedContent = true),
            hit("HTTPS://SOURCE.EXAMPLE:443/a", title = "Source <b>title</b>"),
            hit("https://source.example/a#duplicate"), hit("https://source.example/b"),
            hit("https://source.example/c"), hit("https://source.example/d"), hit("https://source.example/e"), hit("https://source.example/f"),
        )
        for (protocol in WebSearchProtocol.entries) {
            Server(fixture(protocol, items)).use { server ->
                val results = search.search(provider(protocol, server.root + "/search"), key, query)
                assertEquals(protocol.name, 5, results.size)
                assertEquals("https://source.example/a", results.first().url)
                assertEquals("Source title", results.first().title)
                assertEquals("Actual source passage", results.first().content)
                assertEquals("https://source.example/e", results.last().url)
            }
        }
    }

    @Test
    fun `highlight protocols preserve real passages and ignore non-string fragments`() = runBlocking {
        val passages = JsonArray(listOf(JsonPrimitive("First source passage"), JsonPrimitive(17), JsonPrimitive(" "), JsonPrimitive("Second source passage")))
        val item = JsonObject(hit("https://source.example/").toMutableMap().apply {
            put("highlights", passages)
            put("contents", buildJsonObject { put("highlights", passages) })
        })
        for (protocol in listOf(WebSearchProtocol.EXA, WebSearchProtocol.YOU)) {
            Server(fixture(protocol, listOf(item))).use { server ->
                assertEquals("First source passage\nSecond source passage", search.search(provider(protocol, server.root + "/search"), key, query).single().content)
            }
        }
    }

    @Test
    fun `individual titles and source snippets stay bounded for every protocol`() = runBlocking {
        for (protocol in WebSearchProtocol.entries) {
            Server(fixture(protocol, listOf(hit("https://source.example/", "s".repeat(4_001), "t".repeat(501))))).use { server ->
                val result = search.search(provider(protocol, server.root + "/search"), key, query).single()
                assertEquals("t".repeat(500), result.title)
                assertEquals("s".repeat(4_000), result.content)
            }
        }
    }

    @Test
    fun `SearXNG normalizes deployment roots and needs no credential`() = runBlocking {
        Server(fixture(WebSearchProtocol.SEARXNG, listOf(hit("https://source.example/")))).use { server ->
            search.search(provider(WebSearchProtocol.SEARXNG, server.root + "/private/searx/"), null, query)
            assertEquals("/private/searx/search", server.request.get()?.url?.encodedPath)
        }
    }

    @Test
    fun `blocked redirects authentication and retryable statuses never replay any protocol`() = runBlocking {
        for (protocol in WebSearchProtocol.entries) {
            for (status in listOf(301, 302, 307, 308, 401, 403, 407, 408, 421, 429, 503)) {
                Server("$key $query", status = status, redirect = true).use { server ->
                    val error = runCatching { search.search(provider(protocol, server.root + "/search"), key, query) }.exceptionOrNull()
                    assertTrue("$protocol HTTP $status: $error", error is IOException)
                    assertEquals("$protocol HTTP $status replayed", 1, server.requests.get())
                    assertTrue(error?.message.orEmpty().contains(status.toString()))
                    assertPrivate(error)
                }
            }
        }
    }

    @Test
    fun `non JSON malformed missing empty and API error responses fail without exposing bodies`() = runBlocking {
        for (protocol in WebSearchProtocol.entries) {
            val responses = listOf(
                "not-json $key $query" to "application/json",
                "[]" to "application/json",
                "{}" to "application/json",
                fixture(protocol, emptyList()) to "application/json",
                fixture(protocol, listOf(hit("file:///not-a-source"))) to "application/json",
                "{\"error\":\"$key\"}" to "application/json",
                fixture(protocol, listOf(hit("https://source.example/"))) to "text/html",
            )
            for ((body, type) in responses) {
                Server(body, type = type).use { server ->
                    val error = runCatching { search.search(provider(protocol, server.root + "/search"), key, query) }.exceptionOrNull()
                    assertTrue("$protocol $type: $error", error is IOException)
                    assertEquals(1, server.requests.get())
                    assertPrivate(error)
                }
            }
        }
    }

    @Test
    fun `explicit unsuccessful envelopes are not treated as successful source results`() = runBlocking {
        val cases = listOf(
            WebSearchProtocol.FIRECRAWL to "{\"success\":false,\"data\":{\"web\":[${hit("https://source.example/")}]}}",
            WebSearchProtocol.BOCHA to "{\"code\":401,\"data\":{\"webPages\":{\"value\":[${hit("https://source.example/")}]}}}",
        )
        for ((protocol, body) in cases) {
            Server(body).use { server ->
                assertTrue(runCatching { search.search(provider(protocol, server.root + "/search"), key, query) }.exceptionOrNull() is IOException)
            }
        }
        val bocha = "{\"code\":200,\"data\":${fixture(WebSearchProtocol.BOCHA, listOf(hit("https://source.example/")))}}"
        Server(bocha).use { server ->
            assertEquals(1, search.search(provider(WebSearchProtocol.BOCHA, server.root + "/search"), key, query).size)
        }
    }

    @Test
    fun `response bound applies to fixed chunked and decompressed bytes and allows the exact limit`() = runBlocking {
        val protocol = WebSearchProtocol.TAVILY
        val valid = fixture(protocol, listOf(hit("https://source.example/")))
        val exact = valid + " ".repeat(1_048_576 - valid.toByteArray().size)
        Server(exact).use { server ->
            assertEquals(1, search.search(provider(protocol, server.root + "/search"), key, query).size)
        }
        for ((chunked, gzip) in listOf(false to false, true to false, true to true)) {
            Server(exact + " ", chunked = chunked, gzip = gzip).use { server ->
                val error = runCatching { search.search(provider(protocol, server.root + "/search"), key, query) }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("too large"))
            }
        }
    }

    @Test
    fun `disabled missing credentials blank queries and unsafe endpoints never reach HTTP`() = runBlocking {
        Server("{}").use { server ->
            val valid = provider(WebSearchProtocol.TAVILY, server.root + "/search")
            val cases = listOf(
                Triple(valid.copy(enabled = false), key, query), Triple(valid, null, query),
                Triple(valid, "", query), Triple(valid, "key\r\nsecret", query), Triple(valid, key, " "),
            ) + listOf("file:///search", "search.example", "https://user:secret@search.example/", "https://@search.example/", "${server.root}/search?q=private", "${server.root}/search#fragment", "${server.root}/se\narch").map {
                Triple(valid.copy(endpoint = it), key, query)
            }
            for ((service, credential, question) in cases) {
                val error = runCatching { search.search(service, credential, question) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertPrivate(error)
            }
            assertEquals(0, server.requests.get())
        }
    }

    @Test
    fun `inference observers credentials cookies and authenticators are not inherited`() = runBlocking {
        val observed = AtomicInteger()
        val inferenceClient = OkHttpClient.Builder()
            .addInterceptor { chain -> observed.incrementAndGet(); chain.proceed(chain.request().newBuilder().header("Authorization", "model-secret").build()) }
            .addNetworkInterceptor { chain -> observed.incrementAndGet(); chain.proceed(chain.request()) }
            .eventListener(object : EventListener() { override fun callStart(call: okhttp3.Call) { observed.incrementAndGet() } })
            .authenticator(Authenticator { _, _ -> observed.incrementAndGet(); null })
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("secret").value("model-cookie").domain("127.0.0.1").build())
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { observed.incrementAndGet() }
            }).build()
        Server(fixture(WebSearchProtocol.SEARXNG, listOf(hit("https://source.example/")))).use { server ->
            WebSearchClient(inferenceClient).search(provider(WebSearchProtocol.SEARXNG, server.root), null, query)
            assertEquals(0, observed.get())
            assertNull(server.request.get()?.headers?.get("authorization"))
            assertNull(server.request.get()?.headers?.get("cookie"))
        }
    }

    @Test
    fun `network errors discard secret-bearing messages and exception causes`() = runBlocking {
        val transportFailure = UnknownHostException("$key $query").apply {
            initCause(IOException("$query $key"))
            addSuppressed(IOException("$key $query"))
        }
        val dnsCalls = AtomicInteger()
        val failing = WebSearchClient(OkHttpClient.Builder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                dnsCalls.incrementAndGet()
                throw transportFailure
            }
        }).build())
        val error = runCatching { failing.search(provider(WebSearchProtocol.SERPAPI, "https://unreachable.invalid/search"), key, query) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertPrivate(error)
        assertEquals(1, dnsCalls.get())
        // Coroutine stack-trace recovery may chain copies of an already-sanitized IOException.
        // Reject the original transport exception graph, not harmless recovery copies.
        fun assertDetached(cause: Throwable) {
            assertFalse(cause === transportFailure)
            assertFalse(cause is UnknownHostException)
            assertTrue(cause.suppressed.isEmpty())
            cause.cause?.let(::assertDetached)
        }
        assertDetached(checkNotNull(error))
    }

    @Test
    fun `cancellation interrupts a blocked body read and closes the actual HTTP socket`() = runBlocking {
        val received = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "search-cancellation-test").apply { isDaemon = true } }
        worker.submit {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = BufferedInputStream(socket.getInputStream())
                    var contentLength = 0
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toInt()
                    }
                    repeat(contentLength) { check(input.read() >= 0) }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{".toByteArray())
                        flush()
                    }
                    received.countDown()
                    if (input.read() == -1) disconnected.countDown()
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }
        try {
            val pending = async { search.search(provider(WebSearchProtocol.TAVILY, "http://127.0.0.1:${listener.localPort}/search"), key, query) }
            assertTrue(withContext(Dispatchers.IO) { received.await(3, TimeUnit.SECONDS) })
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertTrue("Client cancellation left the socket open: ${serverFailure.get()}", withContext(Dispatchers.IO) { disconnected.await(2, TimeUnit.SECONDS) })
            assertNull(serverFailure.get())
        } finally {
            listener.close()
            worker.shutdownNow()
        }
    }

    private fun provider(protocol: WebSearchProtocol, endpoint: String) = WebSearchProvider("test", "Test search", protocol, endpoint, enabled = true)

    private fun assertPrivate(error: Throwable?) {
        checkNotNull(error)
        assertFalse(error.toString().contains(key))
        assertFalse(error.toString().contains(query))
        error.cause?.let(::assertPrivate)
        error.suppressed.forEach(::assertPrivate)
    }

    private fun hit(url: String, content: String = "Actual source passage", title: String = "Source title", typedContent: Boolean = false): JsonObject = buildJsonObject {
        put("url", url); put("link", url); put("title", title); put("name", title)
        val snippet = if (typedContent) JsonPrimitive(42) else JsonPrimitive(content)
        put("content", snippet); put("description", snippet); put("snippet", snippet)
        put("highlights", JsonArray(listOf(snippet)))
        put("contents", buildJsonObject { put("highlights", JsonArray(listOf(snippet))) })
    }

    private fun fixture(protocol: WebSearchProtocol, hits: List<JsonObject>): String {
        val fields = when (protocol) {
            WebSearchProtocol.TAVILY, WebSearchProtocol.SEARXNG -> setOf("url", "title", "content")
            WebSearchProtocol.BRAVE, WebSearchProtocol.FIRECRAWL -> setOf("url", "title", "description")
            WebSearchProtocol.EXA -> setOf("url", "title", "highlights")
            WebSearchProtocol.SERPER, WebSearchProtocol.SERPAPI -> setOf("link", "title", "snippet")
            WebSearchProtocol.BOCHA -> setOf("url", "name", "snippet")
            WebSearchProtocol.LINKUP -> setOf("url", "name", "content")
            WebSearchProtocol.YOU -> setOf("url", "title", "contents")
        }
        val items = JsonArray(hits.map { item -> JsonObject(item.filterKeys { it in fields }) })
        return buildJsonObject {
            when (protocol) {
                WebSearchProtocol.TAVILY, WebSearchProtocol.EXA, WebSearchProtocol.LINKUP, WebSearchProtocol.SEARXNG -> put("results", items)
                WebSearchProtocol.BRAVE -> put("web", buildJsonObject { put("results", items) })
                WebSearchProtocol.SERPER -> put("organic", items)
                WebSearchProtocol.SERPAPI -> put("organic_results", items)
                WebSearchProtocol.BOCHA -> put("webPages", buildJsonObject { put("value", items) })
                WebSearchProtocol.FIRECRAWL -> { put("success", true); put("data", buildJsonObject { put("web", items) }) }
                WebSearchProtocol.YOU -> put("results", buildJsonObject { put("web", items) })
            }
        }.toString()
    }

    private data class CapturedRequest(val method: String, val url: HttpUrl, val headers: Map<String, String>, val body: String)

    private class Server(
        body: String,
        status: Int = 200,
        type: String = "application/json",
        redirect: Boolean = false,
        chunked: Boolean = false,
        gzip: Boolean = false,
    ) : AutoCloseable {
        val request = AtomicReference<CapturedRequest?>()
        val requests = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                request.set(CapturedRequest(exchange.requestMethod, ("http://127.0.0.1:${address.port}" + exchange.requestURI).toHttpUrl(),
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                    exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }))
                val raw = body.toByteArray()
                val bytes = if (gzip) ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(raw) } }.toByteArray() else raw
                exchange.responseHeaders.add("Content-Type", type)
                exchange.responseHeaders.add("Retry-After", "0")
                if (redirect) exchange.responseHeaders.add("Location", "/redirect-target")
                if (gzip) exchange.responseHeaders.add("Content-Encoding", "gzip")
                try {
                    exchange.sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: IOException) {
                    // Oversized response tests intentionally close the body before the server finishes.
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        val root: String get() = "http://127.0.0.1:${server.address.port}"
        override fun close() { server.stop(0) }
    }

    private fun readLine(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            check(next >= 0) { "HTTP client disconnected before sending headers" }
            if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            bytes.write(next)
        }
    }
}
