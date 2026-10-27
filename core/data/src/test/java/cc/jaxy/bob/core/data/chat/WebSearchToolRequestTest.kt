package cc.jaxy.bob.core.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider-native request shapes for the model-decided web search tool. */
class WebSearchToolRequestTest {
    private fun requestBody(request: Request): JsonObject {
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    private val history = listOf(
        ChatMessage(role = "user", content = "Latest news?"),
        ChatMessage(role = "assistant", content = "", toolCalls = listOf(ChatToolCall("call_1", "web_search", """{"query":"latest news"}"""))),
        ChatMessage(role = "tool", content = """{"results":[{"url":"https://example.com","snippet":"evidence"}]}""", toolCallId = "call_1"),
    )

    @Test
    fun `OpenAI compatible body carries a function tool, assistant call, and tool result`() = kotlinx.coroutines.runBlocking {
        val prepared = ProviderChatRequests(null, OkHttpClient()).prepare(
            providerId = "custom", source = "custom",
            config = mapOf("baseURL" to "http://127.0.0.1:1234/v1", "__sdkType" to "openai"),
            modelId = "local-model", systemPrompt = "", history = history, temperature = null,
            sessionId = "session", toolSearch = true,
        )
        val body = requestBody(prepared.request)
        val tool = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals("function", tool.getValue("type").jsonPrimitive.content)
        assertEquals("web_search", tool.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "tool"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("call_1", messages[1].getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("call_1", messages[2].getValue("tool_call_id").jsonPrimitive.content)
    }

    @Test
    fun `Anthropic body converts the tool exchange into tool_use and tool_result blocks`() = kotlinx.coroutines.runBlocking {
        val prepared = ProviderChatRequests(null, OkHttpClient()).prepare(
            providerId = "kimicodingplan", source = "builtin",
            config = mapOf("baseURL" to "http://127.0.0.1:1234", "__sdkType" to "anthropic"),
            modelId = "claude-test", systemPrompt = "", history = history, temperature = null,
            sessionId = "session", toolSearch = true,
        )
        val body = requestBody(prepared.request)
        assertEquals("web_search", body.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        val assistant = messages[1].getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("tool_use", assistant.single().getValue("type").jsonPrimitive.content)
        assertEquals("latest news", assistant.single().getValue("input").jsonObject.getValue("query").jsonPrimitive.content)
        val result = messages[2].getValue("content").jsonArray.single().jsonObject
        assertEquals("tool_result", result.getValue("type").jsonPrimitive.content)
        assertEquals("call_1", result.getValue("tool_use_id").jsonPrimitive.content)
    }

    @Test
    fun `Gemini body converts the tool exchange into functionCall and functionResponse parts`() = kotlinx.coroutines.runBlocking {
        val prepared = ProviderChatRequests(null, OkHttpClient()).prepare(
            providerId = "google", source = "builtin",
            config = mapOf("baseURL" to "http://127.0.0.1:1234"),
            modelId = "gemini-3-pro", systemPrompt = "", history = history, temperature = null,
            sessionId = "session", toolSearch = true,
        )
        val body = requestBody(prepared.request)
        assertTrue(body.toString().contains("functionDeclarations"))
        val contents = body.getValue("contents").jsonArray.map { it.jsonObject }
        val model = contents.first { it.getValue("role").jsonPrimitive.content == "model" }
        val callPart = model.getValue("parts").jsonArray.map { it.jsonObject }.first { "functionCall" in it }
        assertEquals("latest news", callPart.getValue("functionCall").jsonObject.getValue("args").jsonObject
            .getValue("query").jsonPrimitive.content)
        val result = contents.last().getValue("parts").jsonArray.single().jsonObject
        assertEquals("functionResponse", result.keys.single())
    }

    @Test
    fun `Responses body replays function_call and function_call_output items`() = kotlinx.coroutines.runBlocking {
        val prepared = ProviderChatRequests(null, OkHttpClient()).prepare(
            providerId = "openai", source = "builtin",
            config = mapOf("baseURL" to "http://127.0.0.1:1234/v1"),
            modelId = "gpt-4.1", systemPrompt = "", history = history, temperature = null,
            sessionId = "session", toolSearch = true,
        )
        val body = requestBody(prepared.request)
        assertEquals("function", body.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        val input = body.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals("function_call", input[1].getValue("type").jsonPrimitive.content)
        assertEquals("call_1", input[1].getValue("call_id").jsonPrimitive.content)
        assertEquals("function_call_output", input[2].getValue("type").jsonPrimitive.content)
        assertEquals("call_1", input[2].getValue("call_id").jsonPrimitive.content)
    }

    @Test
    fun `a route without tool support is rejected explicitly`() = kotlinx.coroutines.runBlocking {
        val failure = runCatching {
            ProviderChatRequests(null, OkHttpClient()).prepare(
                providerId = "openai", source = "builtin",
                config = mapOf("baseURL" to "http://127.0.0.1:1234/v1"),
                modelId = "gpt-3.5-turbo-instruct", systemPrompt = "", history = history, temperature = null,
                sessionId = "session", toolSearch = true,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("cannot call tools") == true)
    }

    @Test
    fun `Bedrock body uses toolConfig, toolUse, and toolResult blocks`() {
        val canonical = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("tool_calls") {
                        add(buildJsonObject {
                            put("id", "call_1")
                            putJsonObject("function") {
                                put("name", "web_search")
                                put("arguments", """{"query":"latest news"}""")
                            }
                        })
                    }
                })
                add(buildJsonObject {
                    put("role", "tool"); put("tool_call_id", "call_1")
                    put("content", """{"results":[]}""")
                })
            }
            putJsonArray("tools") { add(JsonObject(emptyMap())) }
        }
        val body = cloudBedrockBody(canonical, "claude-test")
        assertEquals("web_search", body.getValue("toolConfig").jsonObject.getValue("tools").jsonArray.single().jsonObject
            .getValue("toolSpec").jsonObject.getValue("name").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals("toolUse", messages[0].getValue("content").jsonArray.single().jsonObject.keys.single())
        assertEquals("toolResult", messages[1].getValue("content").jsonArray.single().jsonObject.keys.single())
    }
}
