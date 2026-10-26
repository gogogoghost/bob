package cc.jaxy.anlobehub.core.data.chat

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatResponseBufferTest {
    @Test
    fun `stopping retains deltas newer than the last rendered snapshot and original model binding`() {
        val buffer = ChatResponseBuffer("original-topic", "original-provider", "original-model", createdAt = 1000)
        buffer.append(ChatStreamChunk(textDelta = "First"), nowMillis = 1001)
        val rendered = buffer.snapshot()
        buffer.append(ChatStreamChunk(textDelta = " tail\n"), nowMillis = 1002)
        buffer.finish(ChatCompletion(ChatCompletionStatus.STOPPED), nowMillis = 1003)
        val stopped = buffer.snapshot()
        assertEquals("First", rendered.content)
        assertEquals("First tail\n", stopped.content)
        assertEquals(rendered.id, stopped.id)
        assertEquals("original-topic", buffer.topicId)
        assertEquals("original-provider", stopped.provider)
        assertEquals("original-model", stopped.model)
        assertEquals(ChatCompletionStatus.STOPPED, stopped.completion?.status)
    }

    @Test
    fun `reasoning only interrupted output survives history serialization with its cause`() {
        val buffer = ChatResponseBuffer("topic", "provider", "model", createdAt = 1000)
        buffer.append(ChatStreamChunk(reasoningDelta = "Unfinished reasoning"), nowMillis = 1001)
        buffer.finish(ChatCompletion(ChatCompletionStatus.OUTPUT_LIMIT, "length"), nowMillis = 1501)
        val restored = Json.decodeFromString<ChatMessage>(Json.encodeToString(buffer.snapshot()))
        assertTrue(buffer.hasContent)
        assertEquals("", restored.content)
        assertEquals("Unfinished reasoning", restored.reasoning)
        assertEquals(0.5, restored.reasoningDuration!!, 0.0)
        assertEquals(ChatCompletion(ChatCompletionStatus.OUTPUT_LIMIT, "length"), restored.completion)
    }

    @Test
    fun `a completed response is not reclassified by a late stop`() {
        val buffer = ChatResponseBuffer("topic", "provider", "model")
        buffer.append(ChatStreamChunk(textDelta = "Complete answer", completion = ChatCompletion(ChatCompletionStatus.COMPLETED, "stop")))
        buffer.finish(ChatCompletion(ChatCompletionStatus.STOPPED))
        assertEquals(ChatCompletion(ChatCompletionStatus.COMPLETED, "stop"), buffer.snapshot().completion)
    }

    @Test
    fun `an unconfirmed buffer never snapshots as a successful answer`() {
        val buffer = ChatResponseBuffer("topic", "provider", "model")
        buffer.append(ChatStreamChunk(textDelta = "Partial answer"))
        assertEquals("Partial answer", buffer.snapshot().content)
        assertEquals(ChatCompletionStatus.INTERRUPTED, buffer.snapshot().completion?.status)
    }

    @Test
    fun `interrupted search activity degrades to failed instead of a permanent spinner`() {
        val buffer = ChatResponseBuffer("topic", "provider", "model")
        buffer.append(ChatStreamChunk(searchActivity = WebSearchActivity(WebSearchStage.SEARCHING, listOf("q"),
            providerId = "tavily", providerName = "Tavily")))
        buffer.finish(ChatCompletion(ChatCompletionStatus.INTERRUPTED))
        val saved = buffer.snapshot()
        assertEquals(WebSearchStage.FAILED, saved.searchActivity?.stage)
        assertEquals("tavily", saved.searchActivity?.providerId)
        assertEquals("Tavily", saved.searchActivity?.providerName)
        assertTrue(buffer.hasContent)
    }

    @Test
    fun `stopped search activity keeps its stage while interrupted degrades`() {
        val stopped = ChatResponseBuffer("topic", "provider", "model")
        stopped.append(ChatStreamChunk(searchActivity = WebSearchActivity(WebSearchStage.READING, listOf("q"),
            providerId = "tavily", providerName = "Tavily")))
        stopped.finish(ChatCompletion(ChatCompletionStatus.STOPPED))
        assertEquals(WebSearchStage.READING, stopped.snapshot().searchActivity?.stage)
        val interrupted = ChatResponseBuffer("topic", "provider", "model")
        interrupted.append(ChatStreamChunk(searchActivity = WebSearchActivity(WebSearchStage.SEARCHING, listOf("q"),
            providerId = "exa", providerName = "Exa")))
        interrupted.finish(ChatCompletion(ChatCompletionStatus.INTERRUPTED))
        assertEquals(WebSearchStage.FAILED, interrupted.snapshot().searchActivity?.stage)
    }
}
