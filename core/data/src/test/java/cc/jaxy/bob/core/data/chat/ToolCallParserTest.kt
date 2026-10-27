package cc.jaxy.bob.core.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `OpenAI stream fragments accumulate into one complete call`() {
        val parser = ToolCallParser("openai")
        assertTrue(parser.consume(json("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"web_","arguments":"{\"que"}}]}}]}"""), "", false).isEmpty())
        assertTrue(parser.consume(json("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"search","arguments":"ry\":\"latest news\"}"}}]}}]}"""), "", false).isEmpty())
        val calls = parser.consume(json("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""), "", false)
        assertEquals(1, calls.size)
        assertEquals("call_1", calls.single().id)
        assertEquals("web_search", calls.single().name)
        assertEquals("latest news", Json.parseToJsonElement(calls.single().arguments).jsonObject["query"]!!.toString().trim('"'))
    }

    @Test
    fun `Anthropic input_json_delta is not executable until the block stops`() {
        val parser = ToolCallParser("anthropic")
        parser.consume(json("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"web_search","input":{}}}"""), "", false)
        assertTrue(parser.consume(json("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"lat"}}"""), "", false).isEmpty())
        assertTrue(parser.consume(json("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"est news\"}"}}"""), "", false).isEmpty())
        val calls = parser.consume(json("""{"type":"content_block_stop","index":0}"""), "", false)
        assertEquals(1, calls.size)
        assertEquals("toolu_1", calls.single().id)
        assertEquals("web_search", calls.single().name)
        assertTrue(calls.single().arguments.contains("latest news"))
    }

    @Test
    fun `truncated Anthropic arguments are never executed`() {
        val parser = ToolCallParser("anthropic")
        parser.consume(json("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"web_search","input":{}}}"""), "", false)
        parser.consume(json("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"lat"}}"""), "", false)
        assertTrue(parser.consume(json("""{"type":"content_block_stop","index":0}"""), "", false).isEmpty())
        assertTrue(parser.drain().isEmpty())
    }

    @Test
    fun `Responses function_call items are emitted once from item and snapshot`() {
        val parser = ToolCallParser("responses")
        val item = """{"id":"fc_1","type":"function_call","call_id":"call_1","name":"web_search","arguments":"{\"query\":\"news\"}","status":"completed"}"""
        val calls = parser.consume(json("""{"type":"response.output_item.done","item":$item}"""), "", false)
        assertEquals(listOf("call_1"), calls.map { it.id })
        val duplicate = parser.consume(json("""{"type":"response.completed","response":{"status":"completed","output":[$item]}}"""), "", false)
        assertTrue(duplicate.isEmpty())
    }

    @Test
    fun `Gemini functionCall args become a query`() {
        val calls = ToolCallParser("gemini").consume(
            json("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"web_search","args":{"query":"news"}}}]},"finishReason":"STOP"}]}"""),
            "", false,
        )
        assertEquals("web_search", calls.single().name)
        assertTrue(calls.single().arguments.contains("news"))
    }

    @Test
    fun `Ollama object arguments are preserved`() {
        val calls = ToolCallParser("ollama").consume(
            json("""{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"web_search","arguments":{"query":"news"}}}]},"done":false}"""),
            "", false,
        )
        assertEquals("web_search", calls.single().name)
        assertEquals("news", Json.parseToJsonElement(calls.single().arguments).jsonObject["query"]!!.toString().trim('"'))
    }

    @Test
    fun `Bedrock toolUse fragments are emitted on contentBlockStop`() {
        val parser = ToolCallParser("bedrock")
        parser.consume(json("""{"type":"contentBlockStart","contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"tool_1","name":"web_search"}}}"""), "", false)
        assertTrue(parser.consume(json("""{"type":"contentBlockDelta","contentBlockIndex":0,"delta":{"toolUse":{"input":"{\"query\":\"ne"}}}"""), "", false).isEmpty())
        parser.consume(json("""{"type":"contentBlockDelta","contentBlockIndex":0,"delta":{"toolUse":{"input":"ws\"}"}}}"""), "", false)
        val calls = parser.consume(json("""{"type":"contentBlockStop","contentBlockIndex":0}"""), "", false)
        assertEquals("tool_1", calls.single().id)
        assertTrue(calls.single().arguments.contains("news"))
    }

    @Test
    fun `web search query parsing accepts JSON and plain strings`() {
        assertEquals("news", parseWebSearchQuery("""{"query":"news"}"""))
        assertEquals("news", parseWebSearchQuery("\"news\""))
        assertEquals("news", parseWebSearchQuery("news"))
        assertEquals(null, parseWebSearchQuery("{}"))
        assertEquals(null, parseWebSearchQuery("   "))
    }
}
