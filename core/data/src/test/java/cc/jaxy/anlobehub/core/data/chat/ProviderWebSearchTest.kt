package cc.jaxy.anlobehub.core.data.chat

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderWebSearchTest {
    private val requests = ProviderChatRequests(client = OkHttpClient())

    @Test
    fun `custom sources cannot impersonate the builtin provider`() = runBlocking<Unit> {
        for (source in listOf("custom", "imported", "")) {
            prepareError("openai", "gpt-5.4", source = source)
        }
    }

    @Test
    fun `compatible gateways and model variants do not inherit native search support`() = runBlocking<Unit> {
        for (provider in listOf("opencodezen", "opencodecodingplan", "chatgpt", "azure", "openrouter", "custom", "google")) {
            assertFalse(supportsWebSearch(provider, "gpt-5.4"))
            prepareError(provider, "gpt-5.4")
        }
        for (model in listOf("gpt-5.4-nano", "gpt-5-nano", "gpt-4.1-nano", "o1", "o3-mini", "gpt-4o-search-preview", "gpt-5.2-chat-latest", "gpt-4o-audio-preview", "openai/gpt-5.4", "gpt-5.4-unknown")) {
            assertFalse(supportsWebSearch("openai", model))
            prepareError("openai", model)
        }
    }

    @Test
    fun `search rejects incompatible OpenAI effort instead of increasing reasoning`() = runBlocking<Unit> {
        for ((model, effort) in listOf("gpt-5" to "minimal", "gpt-5-mini" to "none", "gpt-5.4" to "minimal", "gpt-6-astra" to "none", "gpt-6.1-sol" to "minimal")) {
            prepareError("openai", model, reasoning = mapOf("reasoningEffort" to effort))
        }
        prepareError("openai", "gpt-5", reasoning = mapOf("thinkingType" to "disabled"))
    }

    private suspend fun prepareError(
        provider: String,
        model: String,
        source: String = "builtin",
        reasoning: Map<String, String> = emptyMap(),
    ): IllegalArgumentException {
        val error = runCatching {
            requests.prepare(
                providerId = provider,
                source = source,
                config = mapOf("baseURL" to "https://example.invalid/v1", "apiKey" to "not-used", "__sdkType" to "openai"),
                modelId = model,
                systemPrompt = "",
                history = emptyList(),
                temperature = null,
                sessionId = "search-boundary-test",
                reasoning = reasoning,
                webSearchEnabled = true,
            )
        }.exceptionOrNull()
        assertTrue("Expected capability rejection, got $error", error is IllegalArgumentException)
        return error as IllegalArgumentException
    }
}
