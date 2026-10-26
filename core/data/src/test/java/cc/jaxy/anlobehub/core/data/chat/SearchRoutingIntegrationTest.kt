package cc.jaxy.anlobehub.core.data.chat

import cc.jaxy.anlobehub.core.data.files.FileRepository
import cc.jaxy.anlobehub.core.data.local.LocalDao
import cc.jaxy.anlobehub.core.data.local.ModelRow
import cc.jaxy.anlobehub.core.data.local.ProviderRow
import cc.jaxy.anlobehub.core.data.provider.ProviderSecretStore
import cc.jaxy.anlobehub.core.data.search.WebSearchMode
import cc.jaxy.anlobehub.core.data.search.WebSearchPolicy
import cc.jaxy.anlobehub.core.data.search.WebSearchProtocol
import cc.jaxy.anlobehub.core.data.search.WebSearchProvider
import cc.jaxy.anlobehub.core.data.search.WebSearchProviderRepository
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `external selection uses that service even on a native-capable model and leaves saved question unchanged`() = runBlocking {
        RoutingServer().use { server ->
            val question = "Latest Android & agent news?"
            val history = listOf(ChatMessage(role = "user", content = question))
            val chunks = withTimeout(5_000) {
                server.repository().stream("openai", "gpt-4.1", "Answer carefully", history,
                    searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "tavily")).toList()
            }
            assertEquals(listOf("/search", "/v1/responses"), server.requests.map { it.path })
            val search = server.requests.first()
            assertEquals(question, search.body.getValue("query").jsonPrimitive.content)
            assertFalse(search.body.toString().contains("model-secret"))
            assertTrue(search.authorization == "Bearer search-secret" || search.body["api_key"]?.jsonPrimitive?.content == "search-secret")
            val inference = server.requests.last().body
            assertTrue((inference["tools"] as? JsonArray).isNullOrEmpty())
            assertTrue(inference.toString().contains("https://example.com/source"))
            assertEquals(question, history.single().content)
            assertEquals(listOf(WebSearchStage.SEARCHING, WebSearchStage.COMPLETED), chunks.mapNotNull { it.searchActivity?.stage })
            assertEquals(listOf("https://example.com/source"), chunks.flatMap { it.citations }.map { it.url }.distinct())
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
    fun `disabled or removed selected service fails visibly without native fallback or inference`() = runBlocking {
        RoutingServer().use { server ->
            for (providers in listOf(listOf(server.searchProvider.copy(enabled = false)), emptyList())) {
                val chunks = mutableListOf<ChatStreamChunk>()
                val failure = runCatching {
                    server.repository(providers = providers).stream("openai", "gpt-4.1", "", listOf(ChatMessage(role = "user", content = "Latest news")),
                        searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "tavily")).collect { chunks += it }
                }.exceptionOrNull()
                assertTrue(failure is ChatStreamException)
                assertEquals(ChatCompletionStatus.FAILED, (failure as ChatStreamException).completion.status)
                assertEquals(listOf(WebSearchStage.SEARCHING, WebSearchStage.FAILED), chunks.mapNotNull { it.searchActivity?.stage })
                assertTrue(server.requests.isEmpty())
            }
        }
    }

    private data class Request(val path: String, val body: JsonObject, val authorization: String?)

    private class RoutingServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<Request>()
        val searchProvider: WebSearchProvider

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
                requests += Request(path, body, exchange.requestHeaders.getFirst("Authorization"))
                val search = path == "/search"
                val native = (body["tools"] as? JsonArray).orEmpty().any { it.jsonObject["type"]?.jsonPrimitive?.content == "web_search" }
                val searchItem = "{\"id\":\"ws\",\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"query\":\"Latest news\"}}"
                val payload = if (search) {
                    "{\"results\":[{\"url\":\"https://example.com/source\",\"title\":\"Source\",\"content\":\"Verified source evidence\"}]}"
                } else buildString {
                    if (native) append("data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$searchItem}\n\n")
                    append("data: {\"type\":\"response.output_text.delta\",\"output_index\":${if (native) 1 else 0},\"content_index\":0,\"delta\":\"Grounded answer\"}\n\n")
                    append("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[${if (native) "$searchItem," else ""}{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Grounded answer\"}]}]}}\n\n")
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
