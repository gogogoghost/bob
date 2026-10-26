package cc.jaxy.bob.core.data.provider

import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.local.CatalogSeeder
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.local.ModelRow
import cc.jaxy.bob.core.data.local.ProviderRow
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRemoteModelDiscoveryTest {
    @Test
    fun `model discovery returns distinct ids and persists local models`() = runBlocking {
        val authorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/models") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                val body = """{"data":[{"id":"alpha"},{"id":"alpha"},{"id":"beta"},{"id":""}]}"""
                    .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val providerId = "custom-local"
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
            val providers = linkedMapOf(
                providerId to ProviderRow(
                    id = providerId,
                    name = "Local endpoint",
                    source = "custom",
                    enabled = true,
                    config = Json.encodeToString(mapOf("baseURL" to baseUrl, "__sdkType" to "openai")),
                ),
            )
            val models = linkedMapOf(
                (providerId to "alpha") to ModelRow(
                    id = "alpha",
                    providerId = providerId,
                    displayName = "Alpha",
                    enabled = false,
                    metadata = """{"type":"chat","chatSupported":true}""",
                ),
            )
            val dao = memoryDao(providers, models)
            val repository = ProviderRepositoryImpl(dao, EmptySecrets, CatalogSeeder(dao) { """{"providers":[],"models":[]}""" })

            val result = repository.fetchRemoteModels(providerId, baseUrl, "local-test-key")

            assertTrue(result is AnResult.Ok)
            assertEquals(listOf("alpha", "beta"), (result as AnResult.Ok).value)
            assertEquals("Bearer local-test-key", authorization.get())
            assertEquals(false, models.getValue(providerId to "alpha").enabled)
            assertEquals(false, models.getValue(providerId to "beta").enabled)
            assertEquals(
                "chat",
                Json.parseToJsonElement(models.getValue(providerId to "beta").metadata)
                    .jsonObject.getValue("type").jsonPrimitive.content,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `connectivity probes selected model rather than model listing`() = runBlocking {
        val body = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/prefix/v1/chat/completions") { exchange ->
                body.set(exchange.requestBody.bufferedReader().use { it.readText() })
                val response = """{"choices":[{"message":{"role":"assistant","content":"Hi"}}]}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val id = "custom-check"
            val providers = linkedMapOf(id to ProviderRow(id, "Local", "custom", true,
                Json.encodeToString(mapOf("baseURL" to "http://127.0.0.1:${server.address.port}/prefix/v1",
                    "__sdkType" to "openai", "__checkModel" to "wrong-default"))))
            val dao = memoryDao(providers, linkedMapOf())
            val repository = ProviderRepositoryImpl(dao, EmptySecrets, CatalogSeeder(dao) { """{"providers":[],"models":[]}""" })

            val result = repository.checkConnectivity(id, "selected-model")

            assertTrue(result is AnResult.Ok && result.value.ok)
            assertEquals("selected-model", Json.parseToJsonElement(requireNotNull(body.get())).jsonObject.getValue("model").jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `connectivity does not claim success for a truncated model answer`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val response = """{"choices":[{"message":{"content":"Partial answer"},"finish_reason":"length"}]}""".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val id = "custom-check"
            val providers = linkedMapOf(id to ProviderRow(id, "Local", "custom", true,
                Json.encodeToString(mapOf("baseURL" to "http://127.0.0.1:${server.address.port}/v1", "__sdkType" to "openai"))))
            val dao = memoryDao(providers, linkedMapOf())
            val repository = ProviderRepositoryImpl(dao, EmptySecrets, CatalogSeeder(dao) { """{"providers":[],"models":[]}""" })
            val result = repository.checkConnectivity(id, "selected-model")
            assertTrue(result is AnResult.Ok && !result.value.ok)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `native discovery uses saved vaults and protocol pagination`() = runBlocking {
        val anthropicKey = AtomicReference<String?>()
        val googleKey = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/anthropic/v1/models") { exchange ->
                anthropicKey.set(exchange.requestHeaders.getFirst("x-api-key"))
                val response = """{"data":[{"id":"claude-test"}],"has_more":false}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/google/v1beta/models") { exchange ->
                googleKey.set(exchange.requestHeaders.getFirst("x-goog-api-key"))
                val response = (if (exchange.requestURI.query == "pageToken=next") {
                    """{"models":[{"name":"models/gemini-second","supportedGenerationMethods":["generateContent"]}]}"""
                } else {
                    """{"models":[{"name":"models/gemini-first","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next"}"""
                }).toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            val providers = linkedMapOf(
                "anthropic" to ProviderRow("anthropic", "Anthropic", "builtin", false,
                    Json.encodeToString(mapOf("baseURL" to "$root/anthropic", "__sdkType" to "anthropic"))),
                "google" to ProviderRow("google", "Google", "builtin", false,
                    Json.encodeToString(mapOf("baseURL" to "$root/google", "__sdkType" to "google"))),
            )
            val dao = memoryDao(providers, linkedMapOf())
            val secrets = object : ProviderSecretStore {
                override suspend fun read(providerId: String) = mapOf("apiKey" to "$providerId-secret")
                override suspend fun write(providerId: String, secrets: Map<String, String>) = Unit
            }
            val repository = ProviderRepositoryImpl(dao, secrets, CatalogSeeder(dao) { """{"providers":[],"models":[]}""" })

            assertEquals(listOf("claude-test"), (repository.fetchRemoteModels("anthropic", "", "") as AnResult.Ok).value)
            assertEquals(listOf("gemini-first", "gemini-second"), (repository.fetchRemoteModels("google", "", "") as AnResult.Ok).value)
            assertEquals("anthropic-secret", anthropicKey.get())
            assertEquals("google-secret", googleKey.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `provider-specific listings retain service paths ids and chat-only model types`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val listings = mapOf(
            "/together/api/models" to """[{"id":"together-chat","type":"chat"},{"id":"image-only","type":"image"}]""",
            "/nebius/v1/models" to """{"data":[{"id":"nebius-chat"}]}""",
            "/mix/api/v1/models" to """{"data":[{"model_id":"mix-chat","types":"llm"},{"model_id":"reranker","types":"rerank"}]}""",
            "/baichuan/v1/models" to """{"data":[{"model":"baichuan-chat"}]}""",
            "/straico/v2/models" to """{"data":[{"id":"straico-chat","model_type":"chat"},{"id":"straico-image","model_type":"image"}]}""",
            "/zen/v1/models" to """{"data":[{"id":"zen-chat"},{"id":"jev-systemone"}]}""",
        )
        listings.forEach { (path, json) ->
            server.createContext(path) { exchange ->
                if (path.startsWith("/nebius")) assertEquals("verbose=true", exchange.requestURI.query)
                val response = json.toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            val endpoints = mapOf(
                "togetherai" to "$root/together/v1", "nebius" to "$root/nebius/v1",
                "aihubmix" to "$root/mix", "baichuan" to "$root/baichuan/v1",
                "straico" to "$root/straico/v2", "opencodezen" to "$root/zen/v1",
            )
            val expected = mapOf("togetherai" to "together-chat", "nebius" to "nebius-chat",
                "aihubmix" to "mix-chat", "baichuan" to "baichuan-chat", "straico" to "straico-chat", "opencodezen" to "zen-chat")
            val providers = endpoints.mapValues { (id, endpoint) ->
                ProviderRow(id, id, "builtin", false, Json.encodeToString(mapOf("baseURL" to endpoint)))
            }.toMutableMap()
            val dao = memoryDao(providers, linkedMapOf())
            val repository = ProviderRepositoryImpl(dao, EmptySecrets, CatalogSeeder(dao) { """{"providers":[],"models":[]}""" })

            expected.forEach { (id, model) ->
                assertEquals(listOf(model), (repository.fetchRemoteModels(id, "", "") as AnResult.Ok).value)
            }
        } finally {
            server.stop(0)
        }
    }

    private fun memoryDao(
        providers: MutableMap<String, ProviderRow>,
        models: MutableMap<Pair<String, String>, ModelRow>,
    ): LocalDao = java.lang.reflect.Proxy.newProxyInstance(
        LocalDao::class.java.classLoader,
        arrayOf(LocalDao::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "providers" -> providers.values.toList()
            "provider" -> providers[args?.firstOrNull() as String]
            "models" -> models.values.toList()
            "model" -> models[args?.get(0) as String to args[1] as String]
            "putProvider" -> {
                val row = args?.firstOrNull() as ProviderRow
                providers[row.id] = row
                Unit
            }
            "putModels" -> {
                @Suppress("UNCHECKED_CAST")
                val rows = args?.firstOrNull() as List<ModelRow>
                rows.forEach { models[it.providerId to it.id] = it }
                Unit
            }
            "putModel" -> {
                val row = args?.firstOrNull() as ModelRow
                models[row.providerId to row.id] = row
                Unit
            }
            "toString" -> "ProviderDiscoveryDaoTestProxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected local DAO call: ${method.name}")
        }
    } as LocalDao

    private object EmptySecrets : ProviderSecretStore {
        override suspend fun read(providerId: String): Map<String, String> = emptyMap()
        override suspend fun write(providerId: String, secrets: Map<String, String>) = Unit
    }
}
