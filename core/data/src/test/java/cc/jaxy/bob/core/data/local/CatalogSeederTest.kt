package cc.jaxy.bob.core.data.local

import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.chat.ModelRepositoryImpl
import cc.jaxy.bob.core.data.chat.AIModel
import cc.jaxy.bob.core.data.chat.ModelCatalogMetadata
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSeederTest {
    @Test
    fun `catalog refresh preserves local settings and prevents unsupported models from activation`() = runBlocking {
        val providerRows = linkedMapOf(
            "openai" to ProviderRow(
                id = "openai",
                name = "Old OpenAI name",
                source = "builtin",
                enabled = false,
                config = Json.encodeToString(
                    mapOf(
                        "baseURL" to "http://user.example/v1",
                        "customHeader" to "keep-me",
                        "__sdkType" to "openai",
                    ),
                ),
            ),
            "comfyui" to ProviderRow(
                id = "comfyui",
                name = "Old ComfyUI name",
                source = "builtin",
                enabled = true,
                config = Json.encodeToString(mapOf("__sdkType" to "openai")),
            ),
        )
        val modelRows = linkedMapOf(
            ("openai" to "gpt-test") to ModelRow("gpt-test", "openai", "Old label", false),
            ("comfyui" to "workflow") to ModelRow("workflow", "comfyui", "Workflow", true),
        )
        val dao = memoryDao(providerRows, modelRows)
        val seeder = CatalogSeeder(dao) { testCatalog }

        seeder.ensureSeeded()

        val openai = providerRows.getValue("openai")
        val openaiConfig = Json.decodeFromString<Map<String, String>>(openai.config)
        assertEquals("OpenAI", openai.name)
        assertFalse(openai.enabled)
        assertTrue(providerRows.getValue("comfyui").enabled)
        assertEquals("keep-me", openaiConfig["customHeader"])
        assertEquals("openai", openaiConfig["__sdkType"])
        assertEquals("fixture-revision", openaiConfig["__catalogRevision"])
        assertEquals("https://platform.example/keys", openaiConfig["__catalogApiKeyUrl"])

        val comfyuiConfig = Json.decodeFromString<Map<String, String>>(providerRows.getValue("comfyui").config)
        assertFalse(comfyuiConfig.containsKey("__sdkType"))
        assertEquals("false", comfyuiConfig["__catalogChatSupported"])

        val oldModel = modelRows.getValue("openai" to "gpt-test")
        assertFalse(oldModel.enabled)
        val oldMetadata = Json.parseToJsonElement(oldModel.metadata).jsonObject
        assertEquals("chat", oldMetadata["type"]?.jsonPrimitive?.content)
        assertEquals("128000", oldMetadata["contextWindowTokens"]?.jsonPrimitive?.content)
        assertEquals("fixture-revision", oldMetadata["catalogRevision"]?.jsonPrimitive?.content)

        val unsupportedModel = modelRows.getValue("comfyui" to "workflow")
        assertFalse(unsupportedModel.enabled)
        assertEquals("false", Json.parseToJsonElement(unsupportedModel.metadata).jsonObject["chatSupported"]?.jsonPrimitive?.content)
        assertFalse(modelRows.getValue("openai" to "gpt-new").enabled)
        assertFalse(modelRows.getValue("openai" to "dall-e").enabled)

        val modelRepository = ModelRepositoryImpl(dao, seeder)
        val mappedModels = (modelRepository.listModels() as AnResult.Ok).value.associateBy { it.id }
        assertEquals("GPT Test", mappedModels.getValue("gpt-test").displayName)
        assertEquals("GPT", mappedModels.getValue("gpt-test").family)
        assertTrue(mappedModels.getValue("gpt-test").chatSupported)
        assertFalse(mappedModels.getValue("gpt-test").enabled == true)
        assertFalse(mappedModels.getValue("gpt-test").providerEnabled)
        assertTrue(modelRepository.toggleModel("gpt-new", "openai", true) is AnResult.Ok)
        assertTrue(modelRows.getValue("openai" to "gpt-new").enabled)
        dao.putProvider(providerRows.getValue("openai").copy(enabled = true))
        val enabledModel = (modelRepository.listModels() as AnResult.Ok).value.first { it.id == "gpt-new" }
        assertTrue(enabledModel.enabled == true)
        assertTrue(enabledModel.providerEnabled)
        assertTrue(modelRepository.toggleModel("workflow", "comfyui", true) is AnResult.Err)
        assertFalse(modelRows.getValue("comfyui" to "workflow").enabled)


        val providerSnapshot = providerRows.toMap()
        val modelSnapshot = modelRows.toMap()
        CatalogSeeder(dao) { testCatalog }.ensureSeeded()
        assertEquals(providerSnapshot, providerRows.toMap())
        assertEquals(modelSnapshot, modelRows.toMap())
    }

    @Test
    fun `new catalog providers and models default disabled until selected`() = runBlocking {
        val providerRows = linkedMapOf<String, ProviderRow>()
        val modelRows = linkedMapOf<Pair<String, String>, ModelRow>()
        val dao = memoryDao(providerRows, modelRows)

        CatalogSeeder(dao) { testCatalog }.ensureSeeded()

        assertTrue(providerRows.isNotEmpty())
        assertTrue(providerRows.values.none { it.enabled })
        assertTrue(modelRows.isNotEmpty())
        assertTrue(modelRows.values.none { it.enabled })

        val modelRepository = ModelRepositoryImpl(dao, CatalogSeeder(dao) { testCatalog })
        assertTrue(modelRepository.toggleModel("gpt-test", "openai", true) is AnResult.Ok)
        dao.putProvider(providerRows.getValue("openai").copy(enabled = true))

        val selected = (modelRepository.listModels() as AnResult.Ok).value.first { it.id == "gpt-test" }
        assertTrue(selected.enabled == true)
        assertTrue(selected.providerEnabled)
        CatalogSeeder(dao) { testCatalog }.ensureSeeded()
        assertTrue(providerRows.getValue("openai").enabled)
        assertTrue(modelRows.getValue("openai" to "gpt-test").enabled)
    }

    @Test
    fun `adapter migration preserves unversioned provider and model activation`() = runBlocking {
        val providerRows = linkedMapOf(
            "openai" to ProviderRow("openai", "OpenAI", "builtin", true,
                Json.encodeToString(mapOf("baseURL" to "https://proxy.example/prefix/v1", "__checkModel" to "gpt-test"))),
            "custom-local" to ProviderRow("custom-local", "My provider", "custom", true,
                Json.encodeToString(mapOf("baseURL" to "http://localhost:1234/v1", "__sdkType" to "openai"))),
        )
        val modelRows = linkedMapOf(
            ("openai" to "gpt-test") to ModelRow("gpt-test", "openai", "Local", true),
            ("custom-local" to "my-chat") to ModelRow("my-chat", "custom-local", "My chat", true),
        )
        val dao = memoryDao(providerRows, modelRows)

        CatalogSeeder(dao) { testCatalog }.ensureSeeded()

        assertTrue(providerRows.getValue("openai").enabled)
        assertTrue(providerRows.getValue("custom-local").enabled)
        assertTrue(modelRows.getValue("openai" to "gpt-test").enabled)
        assertTrue(modelRows.getValue("custom-local" to "my-chat").enabled)
        val config = Json.decodeFromString<Map<String, String>>(providerRows.getValue("openai").config)
        assertEquals("https://proxy.example/prefix/v1", config["baseURL"])
        assertEquals("gpt-test", config["__checkModel"])
    }
    @Test
    fun `chat protocols include cloud and OAuth implementations but exclude image models`() = runBlocking {
        val providerRows = linkedMapOf<String, ProviderRow>()
        val modelRows = linkedMapOf<Pair<String, String>, ModelRow>()
        val dao = memoryDao(providerRows, modelRows)
        val catalog = """
            {
              "source": { "commit": "compat-fixture" },
              "providers": [
                { "id": "openai-like", "name": "OpenAI Compatible", "protocol": "openai", "chatSupported": false, "adapterType": null },
                { "id": "anthropic-like", "name": "Anthropic Compatible", "protocol": "anthropic", "chatSupported": false, "adapterType": null },
                { "id": "router-like", "name": "Router", "protocol": "router", "chatSupported": false, "adapterType": null },
                { "id": "chatgpt", "name": "OAuth Provider", "protocol": "openai", "authType": "oauthDeviceFlow", "chatSupported": false, "adapterType": null },
                { "id": "azure", "name": "Azure", "protocol": "azure", "chatSupported": false, "adapterType": null }
              ],
              "models": [
                { "id": "oa-chat", "providerId": "openai-like", "type": "chat", "chatSupported": false },
                { "id": "anthropic-chat", "providerId": "anthropic-like", "type": "chat", "chatSupported": false },
                { "id": "router-chat", "providerId": "router-like", "type": "chat", "chatSupported": false },
                { "id": "oauth-chat", "providerId": "chatgpt", "type": "chat", "chatSupported": false },
                { "id": "azure-chat", "providerId": "azure", "type": "chat", "chatSupported": false },
                { "id": "oa-image", "providerId": "openai-like", "type": "image", "chatSupported": true }
              ]
            }
        """.trimIndent()

        CatalogSeeder(dao) { catalog }.ensureSeeded()

        fun config(id: String) = Json.decodeFromString<Map<String, String>>(providerRows.getValue(id).config)
        assertEquals("openai", config("openai-like")["__sdkType"])
        assertEquals("anthropic", config("anthropic-like")["__sdkType"])
        assertEquals("router", config("router-like")["__sdkType"])
        assertEquals("true", config("openai-like")["__catalogChatSupported"])
        assertEquals("true", config("chatgpt")["__catalogChatSupported"])
        assertEquals("chatgpt", config("chatgpt")["__sdkType"])
        assertEquals("azure", config("azure")["__sdkType"])

        val repository = ModelRepositoryImpl(dao, CatalogSeeder(dao) { catalog })
        val models = (repository.listModels() as AnResult.Ok).value.associateBy { it.id }
        assertTrue(models.getValue("oa-chat").chatSupported)
        assertTrue(models.getValue("anthropic-chat").chatSupported)
        assertTrue(models.getValue("router-chat").chatSupported)
        assertTrue(models.getValue("oauth-chat").chatSupported)
        assertTrue(models.getValue("azure-chat").chatSupported)
        assertFalse(models.getValue("oa-image").chatSupported)
        assertTrue(repository.toggleModel("oa-chat", "openai-like", true) is AnResult.Ok)
        assertTrue(repository.toggleModel("anthropic-chat", "anthropic-like", true) is AnResult.Ok)
        assertTrue(repository.toggleModel("router-chat", "router-like", true) is AnResult.Ok)
        assertTrue(repository.toggleModel("oauth-chat", "chatgpt", true) is AnResult.Ok)
    }

    @Test
    fun `endpoint migration changes only exact official legacy endpoints`() = runBlocking {
        val cases = listOf(
            Triple("opencodecodingplan", "https://opencode.ai/zen/go/v1", "https://opencode.ai/inference/go/v1"),
            Triple("opencodezen", "https://opencode.ai/zen/v1/", "https://opencode.ai/inference/v1"),
            Triple("opencodecodingplan", "https://proxy.example/prefix/v1", "https://proxy.example/prefix/v1"),
            Triple("opencodezen", "https://proxy.example/zen/v1", "https://proxy.example/zen/v1"),
            Triple("straico", "https://api.straico.com/v0", "https://api.straico.com/v2"),
            Triple("straico", "https://api.straico.com/v1/", "https://api.straico.com/v2"),
            Triple("straico", "https://proxy.example/straico/v1", "https://proxy.example/straico/v1"),
        )
        for ((id, endpoint, expected) in cases) {
            val providers = linkedMapOf(id to ProviderRow(id, id, "builtin", true,
                Json.encodeToString(mapOf("baseURL" to endpoint))))
            val dao = memoryDao(providers, linkedMapOf())
            CatalogSeeder(dao) { """{"providers":[{"id":"$id","name":"$id","protocol":"openai"}],"models":[]}""" }.ensureSeeded()

            assertEquals(expected, Json.decodeFromString<Map<String, String>>(providers.getValue(id).config)["baseURL"])
            assertTrue(providers.getValue(id).enabled)
        }
    }

    @Test
    fun `Kimi current aliases supplement rather than replace saved model bindings`() = runBlocking {
        val id = "kimicodingplan"
        val providers = linkedMapOf(id to ProviderRow(id, "Kimi", "builtin", true,
            Json.encodeToString(mapOf("__checkModel" to "kimi-k2.5"))))
        val models = linkedMapOf((id to "kimi-k2.5") to ModelRow("kimi-k2.5", id, "Saved model", true))
        val dao = memoryDao(providers, models)
        val catalog = """{
            "providers":[{"id":"kimicodingplan","name":"Kimi","protocol":"anthropic","checkModel":"kimi-k2.5","modelCount":2}],
            "models":[{"id":"kimi-k2.5","providerId":"kimicodingplan","type":"chat"},{"id":"kimi-k2-thinking","providerId":"kimicodingplan","type":"chat"}]
        }"""

        CatalogSeeder(dao) { catalog }.ensureSeeded()

        assertEquals(6, models.size)
        assertTrue(models.getValue(id to "kimi-k2.5").enabled)
        listOf("k3", "k3-256k", "kimi-for-coding", "kimi-for-coding-highspeed").forEach { model ->
            val row = models.getValue(id to model)
            assertFalse(row.enabled)
            assertEquals("true", Json.parseToJsonElement(row.metadata).jsonObject.getValue("chatSupported").jsonPrimitive.content)
        }
        val config = Json.decodeFromString<Map<String, String>>(providers.getValue(id).config)
        assertEquals("kimi-k2.5", config["__checkModel"])
        assertEquals("kimi-for-coding", config["__catalogCheckModel"])
        assertEquals("6", config["__catalogModelCount"])
    }

    @Test
    fun `legacy metadata infers chat support from model type`() {
        assertFalse(Json.decodeFromString<ModelCatalogMetadata>("""{"type":"image"}""").chatSupported)
        assertTrue(Json.decodeFromString<ModelCatalogMetadata>("""{"type":"chat"}""").chatSupported)
        assertFalse(AIModel(type = "image").chatSupported)
        assertTrue(AIModel().chatSupported)
    }


    private fun memoryDao(
        providers: MutableMap<String, ProviderRow>,
        models: MutableMap<Pair<String, String>, ModelRow>,
    ): LocalDao = Proxy.newProxyInstance(
        LocalDao::class.java.classLoader,
        arrayOf(LocalDao::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "providers" -> providers.values.toList()
            "provider" -> providers[args?.firstOrNull() as String]
            "putProvider" -> {
                val row = args?.firstOrNull() as ProviderRow
                providers[row.id] = row
                Unit
            }
            "models" -> models.values.toList()
            "modelsFor" -> models.values.filter { it.providerId == args?.firstOrNull() }
            "model" -> {
                val providerId = args?.getOrNull(0) as String
                val modelId = args?.getOrNull(1) as String
                models[providerId to modelId]
            }
            "putModel" -> {
                val row = args?.firstOrNull() as ModelRow
                models[row.providerId to row.id] = row
                Unit
            }
            "putModels" -> {
                @Suppress("UNCHECKED_CAST")
                val rows = args?.firstOrNull() as List<ModelRow>
                rows.forEach { models[it.providerId to it.id] = it }
                Unit
            }
            "toString" -> "CatalogSeederTestDao"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected DAO call: ${method.name}")
        }
    } as LocalDao

    private companion object {
        val testCatalog = """
            {
              "source": { "commit": "fixture-revision" },
              "providers": [
                {
                  "id": "openai", "name": "OpenAI", "description": "Provider description",
                  "url": "https://provider.example", "apiKeyUrl": "https://platform.example/keys",
                  "modelsUrl": "https://platform.example/models", "checkModel": "gpt-test",
                  "proxyPlaceholder": "https://api.example/v1", "showApiKey": true,
                  "authType": null, "protocol": null, "showChecker": true,
                  "showModelFetcher": true, "chatSupported": true, "adapterType": "openai",
                  "modelCount": 2
                },
                {
                  "id": "comfyui", "name": "ComfyUI", "description": "Workflow provider",
                  "showApiKey": true, "showChecker": false, "showModelFetcher": false,
                  "chatSupported": false, "adapterType": null, "modelCount": 1
                }
              ],
              "models": [
                {
                  "id": "gpt-test", "providerId": "openai", "displayName": "GPT Test",
                  "enabled": true, "type": "chat", "chatSupported": true,
                  "family": "GPT", "contextWindowTokens": 128000,
                  "abilities": { "reasoning": true }
                },
                {
                  "id": "gpt-new", "providerId": "openai", "displayName": "GPT New",
                  "enabled": false, "type": "chat", "chatSupported": true
                },
                {
                  "id": "workflow", "providerId": "comfyui", "displayName": "Workflow",
                  "enabled": true, "type": "chat", "chatSupported": false
                },
                {
                  "id": "dall-e", "providerId": "openai", "displayName": "DALL-E",
                  "enabled": true, "type": "image", "chatSupported": false
                }
              ]
            }
        """.trimIndent()
    }
}
