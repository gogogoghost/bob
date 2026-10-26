package cc.jaxy.bob.core.data.local

import cc.jaxy.bob.core.common.util.providerAdapterType
import cc.jaxy.bob.core.common.util.providerDefaultEndpoint
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledProviderCatalogTest {
    @Test
    fun `bundled catalog snapshot retains upstream provider and model coverage`() {
        val catalogFile = File("src/main/assets/provider_catalog.json")
        assertTrue("Bundled provider catalog is missing", catalogFile.isFile)
        val root = Json.parseToJsonElement(catalogFile.readText()).jsonObject
        val source = root.getValue("source").jsonObject
        val providers = root.getValue("providers").jsonArray.map { it.jsonObject }
        val models = root.getValue("models").jsonArray.map { it.jsonObject }
        val providerIds = providers.map { it.getValue("id").jsonPrimitive.content }
        val modelKeys = models.map {
            "${it.getValue("providerId").jsonPrimitive.content}/${it.getValue("id").jsonPrimitive.content}"
        }

        val providersById = providers.associateBy { it.getValue("id").jsonPrimitive.content }
        assertTrue(providersById.getValue("openai").getValue("chatSupported").jsonPrimitive.booleanOrNull == true)
        assertEquals("openai", providersById.getValue("openai").getValue("adapterType").jsonPrimitive.content)
        assertTrue(providersById.getValue("anthropic").getValue("chatSupported").jsonPrimitive.booleanOrNull == true)
        assertEquals("anthropic", providersById.getValue("anthropic").getValue("adapterType").jsonPrimitive.content)
        assertEquals("6c7a6fb033e443bc27bf34cbd5509ab3db7f22f3", source.getValue("commit").jsonPrimitive.content)
        assertEquals(85, providers.size)
        assertEquals(providers.size, providerIds.toSet().size)
        assertFalse("Hosted LobeHub is not a local inference provider", "lobehub" in providerIds)
        assertEquals(1_886, models.size)
        assertEquals(models.size, modelKeys.toSet().size)
        assertEquals(0, models.count { it.getValue("providerId").jsonPrimitive.content !in providerIds })
        assertEquals(21, providers.count { it.getValue("chatSupported").jsonPrimitive.booleanOrNull == true })
        assertEquals(585, models.count { it.getValue("chatSupported").jsonPrimitive.booleanOrNull == true })
        val adapters = providers.associate { provider ->
            val id = provider.getValue("id").jsonPrimitive.content
            id to providerAdapterType(id, provider["protocol"]?.jsonPrimitive?.content, provider["adapterType"]?.jsonPrimitive?.content)
        }
        assertEquals(81, adapters.values.count { it != null })
        assertEquals(setOf("fal", "comfyui", "bfl", "replicate"), adapters.filterValues { it == null }.keys)
        assertEquals(1_661, models.count {
            it.getValue("type").jsonPrimitive.content == "chat" && adapters[it.getValue("providerId").jsonPrimitive.content] != null
        })
        val configuredEndpoints = setOf("azure", "azureai", "bedrock", "cloudflare", "vertexai", "newapi", "higress")
        adapters.filterValues { it != null }.keys.filterNot { it in configuredEndpoints }.forEach {
            assertTrue("Missing real default endpoint for $it", !providerDefaultEndpoint(it).isNullOrBlank())
        }
        assertTrue(providers.none { (it["apiKeyUrl"]?.jsonPrimitive?.content ?: "").contains("lobehub") })
    }
}
