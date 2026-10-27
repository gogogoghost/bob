package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.data.files.FileRepository
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.local.ModelRow
import cc.jaxy.bob.core.data.local.ProviderRow
import cc.jaxy.bob.core.data.provider.ProviderSecretStore
import cc.jaxy.bob.core.data.search.WebSearchMode
import cc.jaxy.bob.core.data.search.WebSearchPolicy
import cc.jaxy.bob.core.data.search.WebSearchProtocol
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SearchRoutingIntegrationTest {
    @Test
    fun `external search is a model tool and only runs after the model asks for it`() = runBlocking {
        RoutingServer().use { server ->
            val question = "Latest Android & agent news?"
            val history = listOf(ChatMessage(role = "user", content = question))
            val chunks = withTimeout(5_000) {
                server.repository().stream("openai", "gpt-4.1", "Answer carefully", history,
                    searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "tavily")).toList()
            }
            assertEquals(listOf("/v1/responses", "/search", "/v1/responses"), server.requests.map { it.path })
            val first = server.requests.first().body
            val tool = first.getValue("tools").jsonArray.single().jsonObject
            assertEquals("function", tool.getValue("type").jsonPrimitive.content)
            assertEquals("web_search", tool.getValue("name").jsonPrimitive.content)
            assertEquals(question, first.getValue("input").jsonArray.first().jsonObject
                .getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
            assertFalse(first.toString().contains("https://example.com/source"))
            val search = server.requests.first { it.path == "/search" }
            assertEquals(question, search.body.getValue("query").jsonPrimitive.content)
            assertFalse(search.body.toString().contains("model-secret"))
            assertTrue(search.authorization == "Bearer search-secret" || search.body["api_key"]?.jsonPrimitive?.content == "search-secret")
            val second = server.requests.last().body.toString()
            assertTrue(second.contains("function_call_output"))
            assertTrue(second.contains("https://example.com/source"))
            assertTrue(second.contains("Verified source evidence"))
            assertEquals(question, history.single().content)
            assertEquals(listOf(WebSearchStage.SEARCHING, WebSearchStage.COMPLETED), chunks.mapNotNull { it.searchActivity?.stage })
            assertEquals(listOf("https://example.com/source"), chunks.flatMap { it.citations }.map { it.url }.distinct())
            assertEquals("Grounded answer", chunks.joinToString("") { it.textDelta })
            assertEquals(ChatCompletionStatus.COMPLETED, chunks.last().completion?.status)
        }
    }

    @Test
    fun `a model that does not need a search never contacts the search service`() = runBlocking {
        RoutingServer(toolCalls = 0).use { server ->
            val chunks = withTimeout(5_000) {
                server.repository().stream("openai", "gpt-4.1", "", listOf(ChatMessage(role = "user", content = "Say hello")),
                    searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "tavily")).toList()
            }
            assertEquals(listOf("/v1/responses"), server.requests.map { it.path })
            assertTrue(chunks.none { it.searchActivity != null || it.citations.isNotEmpty() })
            assertEquals("Direct answer", chunks.joinToString("") { it.textDelta })
            assertEquals(ChatCompletionStatus.COMPLETED, chunks.last().completion?.status)
        }
    }

    @Test
    fun `the model can search twice before answering`() = runBlocking {
        RoutingServer(toolCalls = 2).use { server ->
            val chunks = withTimeout(5_000) {
                server.repository().stream("openai", "gpt-4.1", "", listOf(ChatMessage(role = "user", content = "Compare news")),
                    searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "tavily")).toList()
            }
            assertEquals(5, server.requests.size)
            assertEquals(2, server.requests.count { it.path == "/search" })
            assertEquals("Grounded answer", chunks.joinToString("") { it.textDelta })
            assertEquals(ChatCompletionStatus.COMPLETED, chunks.last().completion?.status)
            assertEquals(2, chunks.count { it.searchActivity?.stage == WebSearchStage.COMPLETED })
            val lastInput = server.requests.last().body.getValue("input").jsonArray.map { it.jsonObject }
            assertEquals(2, lastInput.count { it["type"]?.jsonPrimitive?.content == "function_call_output" })
        }
    }

    @Test
    fun `malformed tool arguments are returned to the model instead of executing a search`() = runBlocking {
        RoutingServer(malformedArguments = true).use { server ->
            val chunks = withTimeout(5_000) {
                server.repository().stream("openai", "gpt-4.1", "", listOf(ChatMessage(role = "user", content = "Latest news")),
                    searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "tavily")).toList()
            }
            assertEquals(listOf("/v1/responses", "/v1/responses"), server.requests.map { it.path })
            assertTrue(server.requests.last().body.toString().contains("requires a non-empty query"))
            assertTrue(chunks.none { it.searchActivity != null })
            assertEquals("Grounded answer", chunks.joinToString("") { it.textDelta })
            assertEquals(ChatCompletionStatus.COMPLETED, chunks.last().completion?.status)
        }
    }

    @Test
    fun `native selection invokes only the model tool even when an external service is enabled`() = runBlocking {
        RoutingServer().use { server ->
            val chunks = withTimeout(5_000) {
                server.repository().stream("openai", "gpt-4.1", "", listOf(ChatMessage(role = "user", content = "Latest news")),
                    searchPolicy = WebSearchPolicy(WebSearchMode.NATIVE)).toList()
            }
            assertEquals(listOf("/v1/responses"), server.requests.map { it.path })
            assertEquals("web_search", server.requests.single().body.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(WebSearchStage.COMPLETED, chunks.mapNotNull { it.searchActivity }.last().stage)
            assertEquals("Grounded answer", chunks.joinToString("") { it.textDelta })
            assertEquals(ChatCompletionStatus.COMPLETED, chunks.last().completion?.status)
        }
    }

    @Test
    fun `unsupported native selection cannot silently use a configured external service`() = runBlocking {
        RoutingServer().use { server ->
            val failure = runCatching {
                server.repository(source = "custom").stream("openai", "gpt-4.1", "", listOf(ChatMessage(role = "user", content = "Latest news")),
                    searchPolicy = WebSearchPolicy(WebSearchMode.NATIVE)).toList()
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `pure prompt skips external search without injection or failure`() = runBlocking {
        RoutingServer().use { server ->
            val chunks = server.repository().stream("openai", "gpt-4.1", "Custom instructions",
                listOf(ChatMessage(role = "user", content = "Latest news")),
                searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "tavily"), purePrompt = true).toList()
            assertTrue(chunks.none { it.searchActivity != null || it.citations.isNotEmpty() })
            assertEquals(ChatCompletionStatus.COMPLETED, chunks.last().completion?.status)
            // Inference runs once; the search endpoint is never hit.
            assertTrue(server.requests.none { it.path == "/search" })
            val body = server.requests.single { it.path == "/v1/responses" }.body
            assertFalse(body.containsKey("tools"))
            assertFalse(body.toString().contains("web_search"))
            assertEquals("Custom instructions", body.getValue("instructions").jsonPrimitive.content)
            assertTrue("untrusted" !in body.toString())
        }
    }

    @Test
    fun `disabled or removed selected service fails before inference without a search`() = runBlocking {
        RoutingServer().use { server ->
            for (providers in listOf(listOf(server.searchProvider.copy(enabled = false)), emptyList())) {
                val chunks = mutableListOf<ChatStreamChunk>()
                val failure = runCatching {
                    server.repository(providers = providers).stream("openai", "gpt-4.1", "", listOf(ChatMessage(role = "user", content = "Latest news")),
                        searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "tavily")).collect { chunks += it }
                }.exceptionOrNull()
                assertTrue(failure is ChatStreamException)
                assertEquals(ChatCompletionStatus.FAILED, (failure as ChatStreamException).completion.status)
                assertTrue(chunks.none { it.searchActivity != null })
            }
            assertTrue(server.requests.isEmpty())
        }
    }

    private data class Request(val path: String, val body: JsonObject, val authorization: String?)

    private class RoutingServer(private val toolCalls: Int = 1, private val malformedArguments: Boolean = false) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val inferenceCount = AtomicInteger()
        val requests = CopyOnWriteArrayList<Request>()
        val searchProvider: WebSearchProvider

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
                requests += Request(path, body, exchange.requestHeaders.getFirst("Authorization"))
                val search = path == "/search"
                val tools = (body["tools"] as? JsonArray).orEmpty().map { it.jsonObject }
                val native = tools.any { it["type"]?.jsonPrimitive?.content == "web_search" }
                val functionTool = tools.any {
                    it["type"]?.jsonPrimitive?.content == "function" && it["name"]?.jsonPrimitive?.content == "web_search"
                }
                val payload = when {
                    search -> """{"results":[{"url":"https://example.com/source","title":"Source","content":"Verified source evidence"}]}"""
                    native -> nativePayload()
                    else -> {
                        val round = inferenceCount.getAndIncrement()
                        if (functionTool && round < toolCalls) toolCallPayload(round)
                        else textPayload(if (functionTool && toolCalls > 0) "Grounded answer" else "Direct answer")
                    }
                }
                val bytes = payload.toByteArray()
                exchange.responseHeaders.add("Content-Type", if (search) "application/json" else "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            searchProvider = WebSearchProvider("tavily", "Tavily", WebSearchProtocol.TAVILY,
                "http://127.0.0.1:${server.address.port}/search", enabled = true, builtin = true)
        }

        private fun toolCallPayload(round: Int): String {
            val item = buildJsonObject {
                put("id", "fc_$round")
                put("type", "function_call")
                put("call_id", "call_$round")
                put("name", "web_search")
                put("arguments", if (malformedArguments) "{}" else """{"query":"Latest Android & agent news?"}""")
                put("status", "completed")
            }
            val done = buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", 0)
                put("item", item)
            }
            val completed = buildJsonObject {
                put("type", "response.completed")
                putJsonObject("response") {
                    put("status", "completed")
                    putJsonArray("output") { add(item) }
                }
            }
            return "data: $done\n\ndata: $completed\n\n"
        }

        private fun textPayload(text: String): String {
            val item = buildJsonObject {
                put("type", "message")
                putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", text) }) }
            }
            val delta = buildJsonObject {
                put("type", "response.output_text.delta")
                put("output_index", 0); put("content_index", 0); put("delta", text)
            }
            val completed = buildJsonObject {
                put("type", "response.completed")
                putJsonObject("response") {
                    put("status", "completed")
                    putJsonArray("output") { add(item) }
                }
            }
            return "data: $delta\n\ndata: $completed\n\n"
        }

        private fun nativePayload(): String {
            val searchItem = buildJsonObject {
                put("id", "ws")
                put("type", "web_search_call")
                put("status", "completed")
                putJsonObject("action") { put("type", "search"); put("query", "Latest news") }
            }
            val textItem = buildJsonObject {
                put("type", "message")
                putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", "Grounded answer") }) }
            }
            val done = buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", 0)
                put("item", searchItem)
            }
            val delta = buildJsonObject {
                put("type", "response.output_text.delta")
                put("output_index", 1); put("content_index", 0); put("delta", "Grounded answer")
            }
            val completed = buildJsonObject {
                put("type", "response.completed")
                putJsonObject("response") {
                    put("status", "completed")
                    putJsonArray("output") { add(searchItem); add(textItem) }
                }
            }
            return "data: $done\n\ndata: $delta\n\ndata: $completed\n\n"
        }

        fun repository(source: String = "builtin", providers: List<WebSearchProvider> = listOf(searchProvider)): DirectChatCompletionRepository {
            val modelProvider = ProviderRow("openai", "Local model", source, true,
                buildJsonObject { put("baseURL", "http://127.0.0.1:${server.address.port}/v1") }.toString())
            val dao = requireNotNull(LocalDao::class.java.cast(Proxy.newProxyInstance(LocalDao::class.java.classLoader, arrayOf(LocalDao::class.java)) { _, method, _ ->
                when (method.name) {
                    "provider" -> modelProvider
                    "model" -> ModelRow("gpt-4.1", "openai", "GPT-4.1", true, "{\"type\":\"chat\",\"maxOutput\":4096}")
                    "reasoning" -> null
                    else -> error("Unexpected DAO operation ${method.name}")
                }
            }))
            val secrets = object : ProviderSecretStore {
                override suspend fun read(providerId: String) = mapOf("apiKey" to "model-secret")
                override suspend fun write(providerId: String, secrets: Map<String, String>) = error("Unexpected credential write")
            }
            val files = object : FileRepository {
                override suspend fun uploadFile(name: String, mime: String, bytes: ByteArray) = error("Unexpected attachment")
                override suspend fun readFile(id: String) = error("Unexpected attachment")
            }
            val searches = object : WebSearchProviderRepository {
                override val providers = MutableStateFlow(providers)
                override suspend fun apiKey(id: String) = "search-secret"
                override suspend fun save(provider: WebSearchProvider, apiKey: String?) = error("Chat must not write configuration")
                override suspend fun delete(id: String) = error("Chat must not delete configuration")
            }
            return DirectChatCompletionRepository(dao, secrets, files, searches)
        }

        override fun close() { server.stop(0) }
    }
}
