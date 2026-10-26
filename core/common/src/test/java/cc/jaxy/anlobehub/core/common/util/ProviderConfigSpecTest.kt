package cc.jaxy.anlobehub.core.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigSpecTest {
    @Test
    fun `provider forms resolve endpoint and authentication behavior`() {
        val openAi = providerConfigSpec("openai", "builtin")
        assertEquals("https://api.openai.com/v1", openAi.endpoint?.placeholder)
        assertTrue(openAi.showApiKey)

        val ollama = providerConfigSpec("ollama", "builtin")
        assertEquals("http://127.0.0.1:11434", ollama.endpoint?.placeholder)
        assertFalse(ollama.showApiKey)

        val oauth = providerConfigSpec("githubcopilot", "builtin")
        assertTrue(oauth.isOAuth)
        assertFalse(oauth.showApiKey)

        assertTrue(providerConfigSpec("my-proxy", "custom").showApiKey)
        assertEquals("https://api.ai21.com/studio/v1", providerConfigSpec("ai21", "builtin").endpoint?.placeholder)
        assertEquals("https://api.ppinfra.com/v3/openai", providerDefaultEndpoint("ppio"))
        assertEquals("https://ai-gateway.vercel.sh/v1", providerDefaultEndpoint("vercelaigateway"))
        assertEquals("https://opencode.ai/inference/go/v1", providerDefaultEndpoint("opencodecodingplan"))
        assertEquals("https://opencode.ai/inference/v1", providerDefaultEndpoint("opencodezen"))
        assertEquals("https://api.straico.com/v2", providerDefaultEndpoint("straico"))
        assertEquals(null, providerDefaultEndpoint("newapi"))
        assertEquals(null, providerDefaultEndpoint("azure"))
        assertEquals("chatgpt", providerAdapterType("chatgpt", "openai"))
        assertEquals("githubcopilot", providerAdapterType("githubcopilot", "openai"))
        assertEquals("supergrok", providerAdapterType("supergrok", "openai"))
        assertEquals("openai", providerAdapterType("lmstudio"))
        assertEquals("openai", providerAdapterType("unsloth"))
        assertEquals("ollama", providerAdapterType("ollamacloud", "openai"))
        assertEquals("straico", providerAdapterType("straico", "openai"))
        listOf("fal", "comfyui", "bfl", "replicate").forEach {
            assertEquals(null, providerAdapterType(it, "openai", "openai"))
        }
    }

    @Test
    fun `provider-specific credential modes identify secret fields`() {
        val bedrock = requireNotNull(specialFieldsFor("bedrock"))
        assertEquals("region", bedrock.apiKeyItems.single().key)
        assertEquals(FieldType.SELECT, bedrock.apiKeyItems.single().type)
        val bedrockSecrets = bedrock.authModes.flatMap { it.fields }
            .filter { it.type == FieldType.SECRET }
            .map { it.key }
            .toSet()
        assertEquals(setOf(VAULT_API_KEY, VAULT_SECRET_ACCESS_KEY, VAULT_SESSION_TOKEN), bedrockSecrets)
        assertEquals("apiKey", inferBedrockAuthMode(mapOf("authType" to "apiKey", "accessKeyId" to "old-key")))
        assertEquals("awsCredentials", inferBedrockAuthMode(mapOf("accessKeyId" to "key")))

        val comfy = requireNotNull(specialFieldsFor("comfyui"))
        val basicAuthSecrets = comfy.authModes.single { it.id == "basic" }.fields
            .filter { it.type == FieldType.SECRET }
            .map { it.key }
        assertEquals(listOf(VAULT_PASSWORD), basicAuthSecrets)
    }
}
