package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.data.files.FileRepository
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.local.ProviderRow
import cc.jaxy.bob.core.data.local.ModelRow
import cc.jaxy.bob.core.data.provider.ProviderSecretStore
import cc.jaxy.bob.core.data.search.WebSearchMode
import cc.jaxy.bob.core.data.search.WebSearchPolicy
import cc.jaxy.bob.core.data.search.WebSearchProtocol
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectChatCompletionRepositoryTest {
    @Test
    fun `custom provider streams from its configured local endpoint`() = runBlocking {
        LocalSseServer().use { server ->
            val provider = ProviderRow(
                id = "custom-local-test",
                name = "Local test endpoint",
                source = "custom",
                enabled = true,
                config = Json.encodeToString(
                    mapOf("baseURL" to server.baseUrl, "__sdkType" to "openai"),
                ),
            )
            val repository = DirectChatCompletionRepository(
                dao = providerDao(provider, modelId = "test-model"),
                secrets = EmptySecrets,
                files = UnusedFiles,
                searchProviders = SearchProviders(),
            )

            val chunks = withTimeout(5_000) {
                repository.stream(
                    providerId = provider.id,
                    modelId = "test-model",
                    systemPrompt = "You are local.",
                    history = listOf(ChatMessage(role = "user", content = "Hello")),
                ).toList()
            }

            assertEquals(listOf("Local reply"), chunks.mapNotNull { it.textDelta.takeIf(String::isNotEmpty) })
            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
            val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
            assertEquals("You are local.", messages[0]["content"]?.jsonPrimitive?.content)
            assertEquals("Hello", messages[1]["content"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `builtin OpenAI provider sends chat completions request and streams text`() = runBlocking {
        LocalSseServer(
            responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"OpenAI reply\"}}]}\n\ndata: [DONE]\n\n",
        ).use { server ->
            val provider = ProviderRow(
                id = "openai",
                name = "OpenAI",
                source = "builtin",
                enabled = true,
                config = Json.encodeToString(
                    mapOf("baseURL" to "${server.baseUrl}/v1", "__sdkType" to "openai"),
                ),
            )
            val repository = DirectChatCompletionRepository(
                dao = providerDao(provider, modelId = "gpt-test"),
                secrets = FixedSecrets(mapOf("apiKey" to "local-openai-key")),
                files = UnusedFiles,
                searchProviders = SearchProviders(),
            )

            val chunks = repository.stream(
                providerId = provider.id,
                modelId = "gpt-test",
                systemPrompt = "You are an agent.",
                history = listOf(ChatMessage(role = "user", content = "Hello")),
            ).toList()

            assertEquals(listOf("OpenAI reply"), chunks.mapNotNull { it.textDelta.takeIf(String::isNotEmpty) })
            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer local-openai-key", request.authorization)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("gpt-test", body.getValue("model").jsonPrimitive.content)
            assertEquals("true", body.getValue("stream").jsonPrimitive.content)
            val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
            assertEquals("You are an agent.", messages[0].getValue("content").jsonPrimitive.content)
        }
    }

    @Test
    fun `OpenAI-compatible provider preserves an explicit API path prefix`() = runBlocking {
        LocalSseServer(requestPath = "/v3/openai/chat/completions").use { server ->
            val provider = ProviderRow(
                id = "novita",
                name = "Novita",
                source = "builtin",
                enabled = true,
                config = Json.encodeToString(
                    mapOf("baseURL" to "${server.baseUrl}/v3/openai", "__sdkType" to "openai"),
                ),
            )
            val repository = DirectChatCompletionRepository(
                dao = providerDao(provider, modelId = "model-test"),
                secrets = EmptySecrets,
                files = UnusedFiles,
                searchProviders = SearchProviders(),
            )

            val chunks = repository.stream(
                providerId = provider.id,
                modelId = "model-test",
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "Hello")),
            ).toList()

            assertEquals(listOf("Local reply"), chunks.mapNotNull { it.textDelta.takeIf(String::isNotEmpty) })
            assertEquals("/v3/openai/chat/completions", server.awaitRequest().path)
        }
    }

    @Test
    fun `Anthropic-compatible provider uses its adapter metadata to send Messages requests`() = runBlocking {
        val response = """event: message_start
data: {"type":"message_start"}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Claude reply"}}

event: message_stop
data: {"type":"message_stop"}
"""
        LocalSseServer(requestPath = "/v1/messages", responseBody = response).use { server ->
            val provider = ProviderRow(
                id = "kimicodingplan",
                name = "Kimi Code",
                source = "builtin",
                enabled = true,
                config = Json.encodeToString(
                    mapOf("baseURL" to server.baseUrl, "__sdkType" to "anthropic"),
                ),
            )
            val repository = DirectChatCompletionRepository(
                dao = providerDao(provider, modelId = "claude-test"),
                secrets = FixedSecrets(mapOf("apiKey" to "local-anthropic-key")),
                files = UnusedFiles,
                searchProviders = SearchProviders(),
            )

            val chunks = repository.stream(
                providerId = provider.id,
                modelId = "claude-test",
                systemPrompt = "You are an agent.",
                history = listOf(ChatMessage(role = "user", content = "Hello")),
            ).toList()

            assertEquals(listOf("Claude reply"), chunks.mapNotNull { it.textDelta.takeIf(String::isNotEmpty) })
            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/messages", request.path)
            assertEquals("local-anthropic-key", request.apiKey)
            assertEquals("2023-06-01", request.anthropicVersion)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("claude-test", body.getValue("model").jsonPrimitive.content)
            assertEquals("true", body.getValue("stream").jsonPrimitive.content)
            assertEquals("You are an agent.", body.getValue("system").jsonPrimitive.content)
            val message = body.getValue("messages").jsonArray.single().jsonObject
            assertEquals("user", message.getValue("role").jsonPrimitive.content)
            assertEquals("Hello", message.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        }
    }

    @Test
    fun `unsupported builtin provider is rejected instead of falling back to OpenAI`() = runBlocking {
        val provider = ProviderRow(
            id = "fal",
            name = "fal",
            source = "builtin",
            enabled = true,
            config = Json.encodeToString(mapOf("baseURL" to "http://127.0.0.1:1/v1")),
        )
        val repository = DirectChatCompletionRepository(
            dao = providerDao(provider, modelId = "fal-ai/flux"),
            secrets = EmptySecrets,
            files = UnusedFiles,
            searchProviders = SearchProviders(),
        )

        val failure = runCatching {
            repository.stream(
                providerId = provider.id,
                modelId = "fal-ai/flux",
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "Hello")),
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }


    @Test
    fun `search failure metadata reaches consumer before terminal exception`() = runBlocking {
        val paths = AtomicReference<List<String>>(emptyList())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths.updateAndGet { it + exchange.requestURI.path }
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val provider = ProviderRow("custom-search", "Search test", "custom", true,
                Json.encodeToString(mapOf("baseURL" to "$base/v1")))
            val search = WebSearchProvider("searxng", "SearXNG", WebSearchProtocol.SEARXNG, "$base/search", enabled = true)
            val repository = DirectChatCompletionRepository(providerDao(provider, "local-model"), EmptySecrets, UnusedFiles, SearchProviders(listOf(search)))
            val chunks = mutableListOf<ChatStreamChunk>()
            val failure = runCatching {
                withTimeout(5_000) {
                    repository.stream(provider.id, "local-model", "", listOf(ChatMessage(role = "user", content = "Latest news")),
                        searchPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")).collect { chunks += it }
                }
            }.exceptionOrNull()
            assertTrue(failure is ChatStreamException)
            assertEquals(ChatCompletionStatus.FAILED, (failure as ChatStreamException).completion.status)
            assertEquals(listOf(WebSearchStage.SEARCHING, WebSearchStage.FAILED), chunks.mapNotNull { it.searchActivity?.stage })
            assertEquals(listOf("/search"), paths.get())
        } finally {
            server.stop(0)
        }
    }

    private fun providerDao(provider: ProviderRow, modelId: String, modelEnabled: Boolean = true): LocalDao =
        java.lang.reflect.Proxy.newProxyInstance(
            LocalDao::class.java.classLoader,
            arrayOf(LocalDao::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "provider" -> provider.takeIf { it.id == args?.firstOrNull() }
                "reasoning" -> null
                "model" -> if (args?.getOrNull(0) == provider.id && args.getOrNull(1) == modelId) {
                    ModelRow(
                        id = modelId,
                        providerId = provider.id,
                        displayName = modelId,
                        enabled = modelEnabled,
                        metadata = """{"type":"chat","chatSupported":true}""",
                    )
                } else null
                "toString" -> "ProviderDaoTestProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected local DAO call: ${method.name}")
            }
        } as LocalDao

    private class SearchProviders(initial: List<WebSearchProvider> = emptyList()) : WebSearchProviderRepository {
        override val providers = MutableStateFlow(initial)
        override suspend fun apiKey(id: String): String? = null
        override suspend fun save(provider: WebSearchProvider, apiKey: String?) = error("Chat must not edit search providers")
        override suspend fun delete(id: String) = error("Chat must not delete search providers")
    }

    private object EmptySecrets : ProviderSecretStore {
        override suspend fun read(providerId: String): Map<String, String> = emptyMap()
        override suspend fun write(providerId: String, secrets: Map<String, String>) = Unit
    }

    private class FixedSecrets(private val values: Map<String, String>) : ProviderSecretStore {
        override suspend fun read(providerId: String): Map<String, String> = values
        override suspend fun write(providerId: String, secrets: Map<String, String>) = Unit
    }

    private object UnusedFiles : FileRepository {
        override suspend fun uploadFile(name: String, mime: String, bytes: ByteArray) =
            error("No attachment expected")

        override suspend fun readFile(id: String) = error("No attachment expected")
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val body: String,
        val authorization: String?,
        val apiKey: String?,
        val anthropicVersion: String?,
    )

    private class LocalSseServer(
        requestPath: String = "/v1/chat/completions",
        private val responseBody: String = "data: {\"choices\":[{\"delta\":{\"content\":\"Local reply\"}}]}\n\ndata: [DONE]\n\n",
    ) : AutoCloseable {
        private val request = AtomicReference<CapturedRequest?>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(requestPath) { exchange ->
                request.set(
                    CapturedRequest(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.path,
                        body = exchange.requestBody.use { String(it.readBytes(), StandardCharsets.UTF_8) },
                        authorization = exchange.requestHeaders.getFirst("Authorization"),
                        apiKey = exchange.requestHeaders.getFirst("x-api-key"),
                        anthropicVersion = exchange.requestHeaders.getFirst("anthropic-version"),
                    ),
                )
                val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        fun awaitRequest(): CapturedRequest =
            checkNotNull(request.get()) { "Local test server did not receive a request" }

        override fun close() {
            server.stop(0)
        }
    }

}
