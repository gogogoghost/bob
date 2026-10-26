package cc.jaxy.bob.core.data.chat

import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** One generation's complete buffer. UI throttling must never become the persistence source of truth. */
class ChatResponseBuffer(
    val topicId: String,
    private val providerId: String,
    private val modelId: String,
    private val createdAt: Long = System.currentTimeMillis(),
) {
    private val id = "assistant-${UUID.randomUUID()}"
    private val textBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val context = mutableListOf<JsonObject>()
    private var reasoningStartedAt: Long? = null
    private var reasoningEndedAt: Long? = null
    private var sourceCitations: MutableList<WebCitation>? = null
    var searchActivity: WebSearchActivity? = null
        private set
    var completion: ChatCompletion? = null
        private set

    val text: String get() = textBuffer.toString()
    val reasoning: String get() = reasoningBuffer.toString()
    val citations: List<WebCitation> get() = sourceCitations?.toList().orEmpty()
    val hasContent: Boolean get() = textBuffer.isNotEmpty() || reasoningBuffer.isNotEmpty() || context.isNotEmpty() ||
        searchActivity != null || !sourceCitations.isNullOrEmpty()

    fun append(chunk: ChatStreamChunk, nowMillis: Long = System.currentTimeMillis()) {
        if (!chunk.reasoningDelta.isNullOrEmpty()) {
            if (reasoningStartedAt == null) reasoningStartedAt = nowMillis
            reasoningBuffer.append(chunk.reasoningDelta)
        }
        if (chunk.textDelta.isNotEmpty()) {
            if (reasoningStartedAt != null && reasoningEndedAt == null) reasoningEndedAt = nowMillis
            textBuffer.append(chunk.textDelta)
        }
        context.addAll(chunk.providerContext)
        chunk.searchActivity?.let { activity ->
            val previous = searchActivity?.queries.orEmpty()
            val queries = when {
                previous.isEmpty() -> activity.queries
                activity.queries.isEmpty() -> previous
                else -> (previous + activity.queries).distinct()
            }
            searchActivity = if (queries == activity.queries) activity else activity.copy(queries = queries)
        }
        if (chunk.citations.isNotEmpty()) {
            val collected = sourceCitations ?: ArrayList<WebCitation>(chunk.citations.size).also { sourceCitations = it }
            for (citation in chunk.citations) {
                val anchored = citation.endIndex != null
                if (!anchored && collected.any { it.url == citation.url && it.endIndex != null }) continue
                if (anchored) collected.removeAll { it.url == citation.url && it.endIndex == null }
                val index = collected.indexOfFirst {
                    it.url == citation.url && it.startIndex == citation.startIndex && it.endIndex == citation.endIndex
                }
                if (index < 0) collected += citation
                else if (citation.title.isNotBlank() && collected[index].title != citation.title) collected[index] = citation
            }
        }
        chunk.completion?.let { finish(it, nowMillis) }
    }

    fun finish(result: ChatCompletion, nowMillis: Long = System.currentTimeMillis()) {
        if (completion != null) return
        completion = result
        if (reasoningStartedAt != null && reasoningEndedAt == null) reasoningEndedAt = nowMillis
    }

    fun reasoningDurationSeconds(nowMillis: Long = System.currentTimeMillis()): Double? =
        reasoningStartedAt?.let { ((reasoningEndedAt ?: nowMillis) - it).coerceAtLeast(0) / 1000.0 }

    fun snapshot(error: String? = null): ChatMessage = ChatMessage(
        id = id,
        role = "assistant",
        content = textBuffer.toString(),
        reasoning = reasoningBuffer.toString().takeIf(String::isNotEmpty),
        reasoningDuration = reasoningDurationSeconds(),
        createdAt = createdAt,
        provider = providerId,
        model = modelId,
        providerContext = context.toList(),
        completion = completion ?: ChatCompletion(ChatCompletionStatus.INTERRUPTED),
        error = error,
        searchActivity = searchActivity?.let { activity ->
            // An interrupted/failed search never reports a terminal stage;
            // persisting SEARCHING/READING would render a permanent spinner in
            // history. STOPPED keeps its stage: the STOPPED completion already
            // tells the user this was an explicit stop, not a silent stall.
            if (completion?.status == ChatCompletionStatus.INTERRUPTED ||
                completion?.status == ChatCompletionStatus.FAILED
            ) {
                when (activity.stage) {
                    WebSearchStage.SEARCHING, WebSearchStage.READING -> activity.copy(stage = WebSearchStage.FAILED)
                    else -> activity
                }
            } else activity
        },
        citations = citations,
    )
}
