package cc.jaxy.bob.core.data.chat

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/** Wire fixtures exercise the same consumer callback as the repository, including metadata-only frames. */
class ProviderWebSearchResponseTest {
    @Test
    fun `OpenAI progress does not end the stream and parallel calls do not finish each other`() = runBlocking {
        val result = read("responses", frames(
            """{"type":"response.web_search_call.in_progress","output_index":0,"item_id":"ws0"}""",
            """{"type":"response.web_search_call.searching","output_index":1,"item_id":"ws1"}""",
            """{"type":"response.web_search_call.completed","output_index":0,"item_id":"ws0"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"id":"ws0","type":"web_search_call","status":"completed","action":{"type":"search","queries":["first query"]}}}""",
            """{"type":"response.output_item.added","output_index":1,"item":{"id":"ws1","type":"web_search_call","status":"in_progress","action":{"type":"open_page","url":"https://example.com/source"}}}""",
            """{"type":"response.web_search_call.completed","output_index":1,"item_id":"ws1"}""",
            """{"type":"response.output_text.delta","output_index":2,"content_index":0,"delta":"Answer after search"}""",
            terminal,
        ))
        assertNull(result.failure)
        assertEquals("Answer after search", result.text)
        val activities = result.chunks.mapNotNull { it.searchActivity }
        assertEquals(listOf(WebSearchStage.SEARCHING, WebSearchStage.SEARCHING, WebSearchStage.READING, WebSearchStage.COMPLETED), activities.map { it.stage })
        assertEquals(emptyList<String>(), activities.first().queries)
        assertEquals(listOf("first query"), activities.last().queries)
        assertEquals(WebCitation("https://example.com/source"), result.citations.single())
        assertEquals(ChatCompletionStatus.COMPLETED, result.completion?.status)
    }

    @Test
    fun `serial search calls can restart after completion and duplicate progress cannot reopen one call`() = runBlocking {
        val result = read("responses", frames(
            """{"type":"response.web_search_call.completed","output_index":0,"item_id":"ws0"}""",
            """{"type":"response.web_search_call.searching","output_index":0,"item_id":"ws0"}""",
            """{"type":"response.web_search_call.searching","output_index":1,"item_id":"ws1"}""",
            """{"type":"response.web_search_call.completed","output_index":1,"item_id":"ws1"}""",
            """{"type":"response.output_text.delta","output_index":2,"delta":"Answer"}""", terminal,
        ))
        assertNull(result.failure)
        assertEquals(listOf(WebSearchStage.COMPLETED, WebSearchStage.SEARCHING, WebSearchStage.COMPLETED), result.chunks.mapNotNull { it.searchActivity?.stage })
    }

    @Test
    fun `OpenAI annotation events and repeated snapshots preserve distinct Unicode part spans`() = runBlocking {
        val annotation = """{"type":"url_citation","url":"https://example.com","title":"Source","start_index":2,"end_index":3}"""
        val secondAnnotation = """{"type":"url_citation","url":"https://example.com","title":"Source","start_index":0,"end_index":1}"""
        val result = read("responses", frames(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"item_id":"msg","delta":"😀 A"}""",
            """{"type":"response.output_text.annotation.added","output_index":0,"content_index":0,"item_id":"msg","annotation":$annotation}""",
            """{"type":"response.content_part.done","output_index":0,"content_index":0,"part":{"type":"output_text","text":"😀 A","annotations":[$annotation]}}""",
            """{"type":"response.output_text.delta","output_index":0,"content_index":1,"item_id":"msg","delta":"中B"}""",
            """{"type":"response.output_text.annotation.added","output_index":0,"content_index":1,"item_id":"msg","annotation":$secondAnnotation}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"id":"msg","type":"message","content":[{"type":"output_text","text":"😀 A","annotations":[$annotation]},{"type":"output_text","text":"中B","annotations":[$secondAnnotation]}]}]}}""",
        ))
        assertNull(result.failure)
        assertEquals("😀 A中B", result.text)
        assertEquals(listOf("A", "中"), result.citations.map { result.text.substring(it.startIndex!!, it.endIndex!!) })
        assertEquals(listOf(3, 4), result.citations.map { it.startIndex })
        assertEquals(2, result.citations.size)
    }

    @Test
    fun `OpenAI terminal snapshots recover missing suffix and new parts without duplicating opaque context`() = runBlocking {
        val reasoning = """{"id":"r","type":"reasoning","encrypted_content":"opaque","summary":[{"text":"Thinking"}]}"""
        val result = read("responses", frames(
            """{"type":"response.reasoning_summary_text.delta","delta":"Thinking"}""",
            """{"type":"response.output_item.done","output_index":0,"item":$reasoning}""",
            """{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"First"}""",
            """{"type":"response.completed","response":{"status":"completed","output":[$reasoning,{"type":"message","content":[{"type":"output_text","text":"First suffix"},{"type":"output_text","text":" second","annotations":[{"type":"url_citation","url":"https://example.com","start_index":1,"end_index":7}]}]},{"id":"r2","type":"reasoning","encrypted_content":"another"}]}}""",
        ))
        assertNull(result.failure)
        assertEquals("First suffix second", result.text)
        assertEquals("Thinking", result.chunks.mapNotNull { it.reasoningDelta }.joinToString(""))
        assertEquals(listOf("opaque", "another"), result.chunks.flatMap { it.providerContext }.map { it["encrypted_content"].toString().trim('"') })
        val citation = result.citations.single()
        assertEquals("second", result.text.substring(citation.startIndex!!, citation.endIndex!!))
    }

    @Test
    fun `snapshot-only OpenAI answer includes all parts and anchored citations`() = runBlocking {
        val result = read("responses", frames(
            """{"type":"response.web_search_call.completed","output_index":0,"item_id":"ws"}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"web_search_call","status":"completed","action":{"type":"search","query":"current facts"}},{"type":"message","content":[{"type":"output_text","text":"前言 "},{"type":"output_text","text":"😀 fact","annotations":[{"type":"url_citation","url":"https://example.com","start_index":2,"end_index":6}]}]}]}}""",
        ))
        assertNull(result.failure)
        assertEquals("前言 😀 fact", result.text)
        val citation = result.citations.single()
        assertEquals("fact", result.text.substring(citation.startIndex!!, citation.endIndex!!))
        assertEquals(listOf("current facts"), result.chunks.mapNotNull { it.searchActivity }.last().queries)
    }

    @Test
    fun `invalid citation spans and non HTTP URLs never fabricate anchors`() = runBlocking {
        val result = read("responses", frames(
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"é中","annotations":[{"type":"url_citation","url":"https://example.com/boundary","start_index":-1,"end_index":1},{"type":"url_citation","url":"https://example.com/mismatch","start_index":0,"end_index":99},{"type":"url_citation","url":"javascript:alert(1)","start_index":0,"end_index":1}]}]}]}}""",
        ))
        assertNull(result.failure)
        assertEquals(listOf(WebCitation("https://example.com/boundary"), WebCitation("https://example.com/mismatch")), result.citations)
    }

    @Test
    fun `enabled tools with no native search activity do not claim a search happened`() = runBlocking {
        val result = read("responses", frames("""{"type":"response.output_text.delta","delta":"No search needed"}""", terminal))
        assertNull(result.failure)
        assertEquals("No search needed", result.text)
        assertTrue(result.chunks.all { it.searchActivity == null && it.citations.isEmpty() })
    }

    @Test
    fun `failed search is not marked completed even when the model returns an answer`() = runBlocking {
        for (status in listOf("failed", "incomplete")) {
            val result = read("responses", frames(
                """{"type":"response.output_item.done","output_index":0,"item":{"id":"ws0","type":"web_search_call","status":"$status"}}""",
                """{"type":"response.output_text.delta","output_index":1,"delta":"I could not search"}""", terminal,
            ))
            assertNull(result.failure)
            assertEquals("I could not search", result.text)
            val activity = result.chunks.mapNotNull { it.searchActivity }.last()
            assertEquals(WebSearchStage.FAILED, activity.stage)
            assertNull(activity.error)
        }
    }

    @Test
    fun `provider failure keeps partial answer and turns actual search activity into failed`() = runBlocking {
        val result = read("responses", frames(
            """{"type":"response.web_search_call.searching","output_index":0,"item_id":"ws"}""",
            """{"type":"response.output_text.delta","output_index":1,"delta":"Partial"}""",
            """{"type":"response.failed","response":{"status":"failed","error":{"message":"Quota exhausted"}}}""",
        ))
        assertEquals("Partial", result.text)
        assertEquals(ChatCompletionStatus.FAILED, (result.failure as ChatStreamException).completion.status)
        assertEquals(WebSearchStage.FAILED, result.chunks.mapNotNull { it.searchActivity }.last().stage)
        assertEquals("Quota exhausted", result.chunks.mapNotNull { it.searchActivity }.last().error)
        assertNull(result.completion)
    }

    @Test
    fun `search progress survives interrupted transport and metadata alone cannot be a successful answer`() = runBlocking {
        val progress = """{"type":"response.web_search_call.searching","output_index":0,"item_id":"ws"}"""
        val interrupted = read("responses", frames(progress))
        assertEquals(WebSearchStage.SEARCHING, interrupted.chunks.single().searchActivity?.stage)
        assertEquals(ChatCompletionStatus.INTERRUPTED, (interrupted.failure as ChatStreamException).completion.status)
        val empty = read("responses", frames(progress, """{"type":"response.web_search_call.completed","output_index":0,"item_id":"ws"}""", terminal))
        assertEquals(ChatCompletionStatus.FAILED, (empty.failure as ChatStreamException).completion.status)
        assertNull(empty.completion)
    }

    @Test
    fun `annotations before text remain sources on interruption and upgrade to anchors when text arrives`() = runBlocking {
        val annotation = """{"type":"response.output_text.annotation.added","output_index":0,"content_index":0,"annotation":{"type":"url_citation","url":"https://example.com","start_index":0,"end_index":4}}"""
        val interrupted = read("responses", frames(annotation))
        assertEquals(ChatCompletionStatus.INTERRUPTED, (interrupted.failure as ChatStreamException).completion.status)
        assertEquals(listOf(WebCitation("https://example.com")), interrupted.chunks.first().citations)
        val answered = read("responses", frames(annotation,
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Fact"}""", terminal))
        assertNull(answered.failure)
        val buffer = ChatResponseBuffer("search-topic", "openai", "gpt-4.1")
        answered.chunks.forEach { buffer.append(it) }
        assertEquals("Fact", buffer.text)
        assertEquals(listOf(WebCitation("https://example.com", startIndex = 0, endIndex = 4)), buffer.citations)
    }

    @Test
    fun `cancelling during native search preserves metadata and never becomes a socket failure`() = runBlocking {
        supervisorScope {
            val arrived = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use {
                    it.write(frames(
                        """{"type":"response.web_search_call.searching","output_index":0,"item_id":"ws"}""",
                        """{"type":"response.output_text.annotation.added","output_index":1,"content_index":0,"annotation":{"type":"url_citation","url":"https://example.com","start_index":0,"end_index":4}}""",
                    ).toByteArray(Charsets.UTF_8))
                    it.flush()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            server.start()
            val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val buffer = ChatResponseBuffer("search-topic", "openai", "gpt-4.1")
            val stream = async {
                val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()
                executeProviderRequest(client, PreparedChatRequest(request, "responses", webSearchEnabled = true)) {
                    buffer.append(it)
                    if (it.citations.isNotEmpty()) arrived.complete(Unit)
                }
            }
            try {
                withTimeout(5_000) { arrived.await() }
                stream.cancel()
                withTimeout(2_000) { stream.join() }
                assertTrue(runCatching { stream.await() }.exceptionOrNull() is CancellationException)
                assertEquals("", buffer.text)
                assertEquals(WebSearchStage.SEARCHING, buffer.searchActivity?.stage)
                assertEquals(listOf(WebCitation("https://example.com")), buffer.citations)
                assertNull(buffer.completion)
            } finally {
                release.countDown()
                stream.cancelAndJoin()
                server.stop(0)
                client.connectionPool.evictAll()
            }
        }
    }

    private data class Result(val chunks: List<ChatStreamChunk>, val failure: Throwable?) {
        val text get() = chunks.joinToString("") { it.textDelta }
        val citations get() = chunks.flatMap { it.citations }
        val completion get() = chunks.lastOrNull()?.completion
    }
    private fun frames(vararg events: String) = events.joinToString("") { "data: $it\n\n" }
    private val terminal = """{"type":"response.completed","response":{"status":"completed","output":[]}}"""
    private suspend fun read(protocol: String, wire: String): Result {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bytes = wire.toByteArray(Charsets.UTF_8)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = OkHttpClient()
        val chunks = mutableListOf<ChatStreamChunk>()
        return try {
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()
            val failure = runCatching {
                withTimeout(5_000) {
                    executeProviderRequest(client, PreparedChatRequest(request, protocol, webSearchEnabled = true)) { chunks += it }
                }
            }.exceptionOrNull()
            Result(chunks, failure)
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
        }
    }
}
