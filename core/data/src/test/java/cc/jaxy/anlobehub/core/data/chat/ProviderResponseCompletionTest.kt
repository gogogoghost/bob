package cc.jaxy.anlobehub.core.data.chat

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class ProviderResponseCompletionTest {
    @Test
    fun `the final text delta is retained when stop arrives in the same event`() = runBlocking {
        val result = read("openai", sse("""{"choices":[{"delta":{"content":"Complete answer"},"finish_reason":"stop"}]}"""))
        assertNull(result.failure)
        assertEquals("Complete answer", result.text)
        assertEquals(ChatCompletion(ChatCompletionStatus.COMPLETED, "stop"), result.completion)
    }

    @Test
    fun `cancelling a blocked HTTP stream does not escape as a socket failure`() = runBlocking {
        supervisorScope {
            val prefixArrived = CompletableDeferred<Unit>()
            val releaseConnection = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use {
                    it.write(sse("""{"choices":[{"delta":{"content":"Partial answer"}}]}""").toByteArray())
                    it.flush()
                    releaseConnection.await(5, TimeUnit.SECONDS)
                }
                exchange.close()
            }
            server.start()
            val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val chunks = mutableListOf<ChatStreamChunk>()
            val stream = async {
                val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()
                executeProviderRequest(client, PreparedChatRequest(request, "openai")) {
                    chunks += it
                    if (it.textDelta.isNotEmpty()) prefixArrived.complete(Unit)
                }
            }
            try {
                withTimeout(5_000) { prefixArrived.await() }
                stream.cancel()
                withTimeout(2_000) { stream.join() }
                val failure = runCatching { stream.await() }.exceptionOrNull()
                assertTrue("Cancellation became $failure", failure is CancellationException)
                assertEquals("Partial answer", chunks.joinToString("") { it.textDelta })
                assertTrue(chunks.none { it.completion != null })
            } finally {
                releaseConnection.countDown()
                stream.cancelAndJoin()
                server.stop(0)
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `Ollama errors after done are not silently discarded`() = runBlocking {
        val result = read("ollama", """{"message":{"content":"Partial answer"},"done":true}""" + "\n" +
            """{"error":"Stream failed"}""" + "\n", "application/x-ndjson")
        assertEquals("Partial answer", result.text)
        assertEquals(ChatCompletionStatus.FAILED, (result.failure as ChatStreamException).completion.status)
        assertNull(result.completion)
    }

    @Test
    fun `Bedrock transport trailers remain validated after messageStop`() = runBlocking {
        val trailer = frame("metadata", """{"usage":{"outputTokens":4}}""").also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val result = readBytes("bedrock", frame("contentBlockDelta", """{"delta":{"text":"Partial answer"}}""") +
            frame("messageStop", """{"stopReason":"end_turn"}""") + trailer, "application/vnd.amazon.eventstream")
        assertEquals("Partial answer", result.text)
        assertTrue(result.failure is java.io.IOException)
        assertTrue(result.failure?.message.orEmpty().contains("checksum"))
        assertNull(result.completion)
    }

    @Test
    fun `an early finish reason cannot discard later text or the actual final reason`() = runBlocking {
        val result = read("openai", sse("""{"choices":[{"delta":{"content":"First "},"finish_reason":"stop"}]}""") +
            sse("""{"choices":[{"delta":{"content":"second"},"finish_reason":"length"}]}""") +
            sse("""{"choices":[],"usage":{"completion_tokens":20}}""") + "data: [DONE]\n\n")
        assertNull(result.failure)
        assertEquals("First second", result.text)
        assertEquals(ChatCompletion(ChatCompletionStatus.OUTPUT_LIMIT, "length"), result.completion)
    }

    @Test
    fun `a reasoning finish reason cannot prevent the assistant answer from arriving`() = runBlocking {
        val result = read("openai", sse("""{"choices":[{"delta":{"reasoning_content":"Thinking"},"finish_reason":"stop"}]}""") +
            sse("""{"choices":[{"delta":{"content":"Final answer"}}]}""") + "data: [DONE]\n\n")
        assertNull(result.failure)
        assertEquals("Final answer", result.text)
        assertEquals("Thinking", result.chunks.mapNotNull { it.reasoningDelta }.joinToString(""))
        assertEquals(ChatCompletionStatus.COMPLETED, result.completion?.status)
    }

    @Test
    fun `content after an early finish reason needs a fresh completion signal`() = runBlocking {
        for (wire in listOf(
            Wire("openai", sse("""{"choices":[{"delta":{"content":"First "},"finish_reason":"stop"}]}""") +
                sse("""{"choices":[{"delta":{"content":"unfinished"}}]}""")),
            Wire("gemini", sse("""{"candidates":[{"content":{"parts":[{"text":"First "}]},"finishReason":"STOP"}]}""") +
                sse("""{"candidates":[{"content":{"parts":[{"text":"unfinished"}]}}]}""")),
        )) {
            val result = read(wire.protocol, wire.body)
            assertEquals(wire.protocol, "First unfinished", result.text)
            assertEquals(wire.protocol, ChatCompletionStatus.INTERRUPTED, (result.failure as ChatStreamException).completion.status)
            assertNull(wire.protocol, result.completion)
        }
    }

    @Test
    fun `errors following a finish reason are not hidden as successful completion`() = runBlocking {
        val result = read("openai", sse("""{"choices":[{"delta":{"content":"Partial answer"},"finish_reason":"stop"}]}""") +
            sse("""{"error":{"message":"Upstream disconnected"}}"""))
        assertEquals("Partial answer", result.text)
        assertEquals(ChatCompletionStatus.FAILED, (result.failure as ChatStreamException).completion.status)
        assertEquals("Upstream disconnected", result.failure.message)
    }

    @Test
    fun `each native protocol reports output exhaustion without discarding the partial answer`() = runBlocking {
        val cases = listOf(
            Wire("openai", sse("""{"choices":[{"delta":{"content":"Partial answer"},"finish_reason":"length"}]}""") + "data: [DONE]\n\n"),
            Wire("anthropic", sse("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"Partial answer"}}""") +
                sse("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""") + sse("""{"type":"message_stop"}""")),
            Wire("responses", sse("""{"type":"response.output_text.delta","delta":"Partial answer"}""") +
                sse("""{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","content":[{"type":"output_text","text":"Partial answer"}]}]}}""")),
            Wire("gemini", sse("""{"candidates":[{"content":{"parts":[{"text":"Partial answer"}]},"finishReason":"MAX_TOKENS"}]}""")),
            Wire("ollama", """{"message":{"content":"Partial answer"},"done":true,"done_reason":"length"}""" + "\n", "application/x-ndjson"),
            Wire("straico", """{"choices":[{"message":{"content":"Partial answer"},"finish_reason":"length"}]}""", "application/json"),
        )
        for (wire in cases) {
            val result = read(wire.protocol, wire.body, wire.contentType)
            assertNull(wire.protocol, result.failure)
            assertEquals(wire.protocol, "Partial answer", result.text)
            assertEquals(wire.protocol, ChatCompletionStatus.OUTPUT_LIMIT, result.completion?.status)
        }
        val bedrock = readBytes("bedrock", frame("contentBlockDelta", """{"delta":{"text":"Partial answer"}}""") +
            frame("messageStop", """{"stopReason":"max_tokens"}"""), "application/vnd.amazon.eventstream")
        assertNull(bedrock.failure)
        assertEquals("Partial answer", bedrock.text)
        assertEquals(ChatCompletionStatus.OUTPUT_LIMIT, bedrock.completion?.status)
    }

    @Test
    fun `clean HTTP EOF without a protocol completion is an interruption even after text`() = runBlocking {
        val cases = listOf(
            Wire("openai", sse("""{"choices":[{"delta":{"content":"Partial answer"}}]}""")),
            Wire("anthropic", sse("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"Partial answer"}}""") +
                sse("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")),
            Wire("responses", sse("""{"type":"response.output_text.delta","delta":"Partial answer"}""")),
            Wire("gemini", sse("""{"candidates":[{"content":{"parts":[{"text":"Partial answer"}]}}]}""")),
            Wire("ollama", """{"message":{"content":"Partial answer"},"done":false}""" + "\n", "application/x-ndjson"),
        )
        for (wire in cases) {
            val result = read(wire.protocol, wire.body, wire.contentType)
            assertEquals(wire.protocol, "Partial answer", result.text)
            assertTrue(wire.protocol, result.failure is ChatStreamException)
            assertEquals(wire.protocol, ChatCompletionStatus.INTERRUPTED, (result.failure as ChatStreamException).completion.status)
            assertNull(wire.protocol, result.completion)
        }
        val result = readBytes("bedrock", frame("contentBlockDelta", """{"delta":{"text":"Partial answer"}}"""), "application/vnd.amazon.eventstream")
        assertEquals("Partial answer", result.text)
        assertEquals(ChatCompletionStatus.INTERRUPTED, (result.failure as ChatStreamException).completion.status)
    }

    @Test
    fun `Responses nonstream incomplete output is still returned to the consumer`() = runBlocking {
        val result = read("responses", """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","content":[{"type":"output_text","text":"Partial answer"}]}]}""", "application/json")
        assertNull(result.failure)
        assertEquals("Partial answer", result.text)
        assertEquals(ChatCompletionStatus.OUTPUT_LIMIT, result.completion?.status)
    }

    @Test
    fun `Responses terminal snapshots recover text after reasoning without duplicating it`() = runBlocking {
        val result = read("responses", sse("""{"type":"response.reasoning_summary_text.delta","delta":"Thinking"}""") +
            sse("""{"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","summary":[{"text":"Thinking"}]},{"type":"message","content":[{"type":"output_text","text":"Final answer"}]}]}}"""))
        assertNull(result.failure)
        assertEquals("Final answer", result.text)
        assertEquals("Thinking", result.chunks.mapNotNull { it.reasoningDelta }.joinToString(""))
        assertEquals(ChatCompletionStatus.COMPLETED, result.completion?.status)
    }

    @Test
    fun `filtered prompts with no text are reported as filtered rather than an empty success`() = runBlocking {
        val result = read("gemini", sse("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertNull(result.failure)
        assertEquals("", result.text)
        assertEquals(ChatCompletion(ChatCompletionStatus.CONTENT_FILTERED, "SAFETY"), result.completion)
    }

    @Test
    fun `reasoning only output exhaustion keeps the reasoning and terminal cause`() = runBlocking {
        val result = read("openai", sse("""{"choices":[{"delta":{"reasoning_content":"Thinking"},"finish_reason":"length"}]}"""))
        assertNull(result.failure)
        assertEquals("", result.text)
        assertEquals("Thinking", result.chunks.mapNotNull { it.reasoningDelta }.joinToString(""))
        assertEquals(ChatCompletionStatus.OUTPUT_LIMIT, result.completion?.status)
    }

    @Test
    fun `tool requests and unknown finish reasons are not marked as completed answers`() = runBlocking {
        for ((reason, status) in listOf("tool_calls" to ChatCompletionStatus.TOOL_CALL_REQUIRED, "insufficient_system_resource" to ChatCompletionStatus.FAILED)) {
            val result = read("openai", sse("""{"choices":[{"delta":{"content":"Partial answer"},"finish_reason":"$reason"}]}"""))
            assertNull(result.failure)
            assertEquals("Partial answer", result.text)
            assertEquals(ChatCompletion(status, reason), result.completion)
        }
    }

    @Test
    fun `a provider error after a delta preserves partial content and reports failure`() = runBlocking {
        val result = read("openai", sse("""{"choices":[{"delta":{"content":"Partial answer"}}]}""") +
            sse("""{"error":{"message":"Quota exhausted"}}"""))
        assertEquals("Partial answer", result.text)
        assertEquals(ChatCompletionStatus.FAILED, (result.failure as ChatStreamException).completion.status)
        assertEquals("Quota exhausted", result.failure.message)
    }

    private data class Wire(val protocol: String, val body: String, val contentType: String = "text/event-stream")
    private data class Result(val chunks: List<ChatStreamChunk>, val failure: Throwable?) {
        val text: String get() = chunks.joinToString("") { it.textDelta }
        val completion: ChatCompletion? get() = chunks.lastOrNull()?.completion
    }
    private fun sse(data: String) = "data: $data\n\n"
    private suspend fun read(protocol: String, body: String, contentType: String = "text/event-stream") = readBytes(protocol, body.toByteArray(), contentType)
    private suspend fun readBytes(protocol: String, body: ByteArray, contentType: String): Result {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = OkHttpClient()
        val chunks = mutableListOf<ChatStreamChunk>()
        return try {
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()
            val failure = runCatching { withTimeout(5_000) { executeProviderRequest(client, PreparedChatRequest(request, protocol)) { chunks += it } } }.exceptionOrNull()
            Result(chunks, failure)
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
        }
    }

    private fun frame(event: String, payload: String): ByteArray {
        val headers = ByteArrayOutputStream().also { bytes ->
            val data = DataOutputStream(bytes)
            for ((name, value) in listOf(":message-type" to "event", ":event-type" to event)) {
                val key = name.toByteArray(); val text = value.toByteArray()
                data.writeByte(key.size); data.write(key); data.writeByte(7); data.writeShort(text.size); data.write(text)
            }
        }.toByteArray()
        val bytes = payload.toByteArray()
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.writeInt(16 + headers.size + bytes.size); data.writeInt(headers.size)
        data.writeInt(CRC32().apply { update(output.toByteArray()) }.value.toInt())
        data.write(headers); data.write(bytes)
        data.writeInt(CRC32().apply { update(output.toByteArray()) }.value.toInt())
        return output.toByteArray()
    }
}
