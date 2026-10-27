package cc.jaxy.bob.core.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMetadataTest {
    @Test
    fun `ID-only reasoning families get a name family generation and reasoning ability`() {
        val info = inferModelInfo("deepseek-v4.1-flash")
        assertEquals("DeepSeek V4.1 Flash", info.displayName)
        assertEquals("deepseek", info.family)
        assertEquals("deepseek-v4.1", info.generation)
        assertEquals("deepseek-v4", info.familyVersion)
        assertTrue(info.abilities!!.getValue("reasoning").jsonPrimitive.content == "true")
        assertTrue(info.abilities!!.getValue("functionCall").jsonPrimitive.content == "true")
    }

    @Test
    fun `non-reasoning legacy models are not mislabeled`() {
        val info = inferModelInfo("gpt-4.1")
        assertEquals("gpt", info.family)
        assertFalse(info.abilities!!.containsKey("reasoning"))
        assertTrue(info.abilities!!.containsKey("functionCall"))
    }

    @Test
    fun `merging keeps authoritative scalar values and accumulates abilities`() {
        val catalog = Json.parseToJsonElement(
            """{"contextWindowTokens":1000000,"maxOutput":384000,"abilities":{"reasoning":true,"functionCall":true},"family":"deepseek"}""",
        ).jsonObject
        val parsed = Json.parseToJsonElement(
            """{"displayName":"Provider Name","contextWindowTokens":200000,"abilities":{"reasoning":true}}""",
        ).jsonObject
        val inferred = inferredModelMetadata("deepseek-v4.1-flash")
        val merged = mergeModelMetadata(parsed, catalog, inferred)
        assertEquals("Provider Name", merged.getValue("displayName").jsonPrimitive.content)
        assertEquals("200000", merged.getValue("contextWindowTokens").jsonPrimitive.content)
        assertEquals("384000", merged.getValue("maxOutput").jsonPrimitive.content)
        val abilities = merged.getValue("abilities").jsonObject
        assertEquals("true", abilities.getValue("reasoning").jsonPrimitive.content)
        assertEquals("true", abilities.getValue("functionCall").jsonPrimitive.content)
    }

    @Test
    fun `unknown families do not invent abilities`() {
        val info = inferModelInfo("mystery-model-xl")
        assertNull(info.family)
        assertNull(info.abilities)
        assertEquals("Mystery Model Xl", info.displayName)
    }
}
