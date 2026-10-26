package cc.jaxy.anlobehub.core.data.chat

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Per-request OpenAI Responses metadata state. Construct only for opted-in native search. */
internal class NativeWebSearchParser {
    private class TextPart {
        val text = StringBuilder()
        val runs = mutableListOf<Run>()
        var reliable = true
    }
    private data class Run(val local: Int, val global: Int, var length: Int)
    private data class Annotation(val part: String, val value: JsonObject)
    private data class SearchCall(var stage: WebSearchStage, var error: String? = null)
    private val parts = mutableMapOf<String, TextPart>()
    private val itemIndices = mutableMapOf<String, Int>()
    private val annotations = linkedSetOf<Annotation>()
    private val calls = linkedMapOf<String, SearchCall>()
    private val queries = linkedSetOf<String>()
    private val sentCitations = linkedSetOf<WebCitation>()
    private val sentContext = linkedSetOf<JsonObject>()
    private var textLength = 0
    private var failure: String? = null
    private var lastActivity: WebSearchActivity? = null
    private var pendingAnchors = false
    private var lastOutputIndex = -1
    private var lastContentIndex = -1
    private var lastPartKey = ""

    fun consume(obj: JsonObject, event: String, complete: Boolean, chunk: ChatStreamChunk?, error: String?): ChatStreamChunk {
        val type = obj.string("type") ?: event
        if (error == null && (type == "response.output_text.delta" || type == "response.refusal.delta")) {
            val key = partKey(itemIndex(obj), obj.int("content_index") ?: 0)
            val delta = obj.string("delta").orEmpty()
            append(key, delta)
            val result = chunk ?: ChatStreamChunk(textDelta = delta)
            return if (pendingAnchors) metadata(result) else result
        }
        val result = responses(obj, event, complete, chunk ?: ChatStreamChunk())
        if (error != null && calls.isNotEmpty()) failure = error
        return metadata(result)
    }

    private fun append(key: String, value: String): String {
        if (value.isEmpty()) return ""
        val part = parts.getOrPut(key, ::TextPart)
        val previous = part.runs.lastOrNull()
        if (previous != null && previous.local + previous.length == part.text.length &&
            previous.global + previous.length == textLength) previous.length += value.length
        else part.runs += Run(part.text.length, textLength, value.length)
        part.text.append(value)
        textLength += value.length
        return value
    }

    private fun snapshot(key: String, text: String): String {
        val part = parts.getOrPut(key, ::TextPart)
        if (text.length < part.text.length || !part.text.indices.all { text[it] == part.text[it] }) {
            // A revised snapshot cannot replace already delivered assistant text.
            part.reliable = false
            return ""
        }
        return append(key, text.substring(part.text.length))
    }

    private fun partKey(outputIndex: Int, contentIndex: Int): String {
        if (lastOutputIndex != outputIndex || lastContentIndex != contentIndex) {
            lastPartKey = "$outputIndex:$contentIndex"
            lastOutputIndex = outputIndex
            lastContentIndex = contentIndex
        }
        return lastPartKey
    }

    private fun itemIndex(obj: JsonObject, item: JsonObject? = null): Int {
        val id = item?.string("id") ?: obj.string("item_id")
        val index = obj.int("output_index") ?: id?.let(itemIndices::get) ?: 0
        if (id != null) itemIndices[id] = index
        return index
    }

    private fun responses(obj: JsonObject, event: String, complete: Boolean, chunk: ChatStreamChunk): ChatStreamChunk {
        val type = obj.string("type") ?: event
        val text = StringBuilder()
        val context = chunk.providerContext.toMutableList()
        val index = itemIndex(obj, obj["item"] as? JsonObject)
        val key = partKey(index, obj.int("content_index") ?: 0)
        when (type) {
            "response.output_text.delta", "response.refusal.delta" -> text.append(append(key, obj.string("delta").orEmpty()))
            "response.output_text.done" -> text.append(snapshot(key, obj.string("text").orEmpty()))
            "response.refusal.done" -> text.append(snapshot(key, obj.string("refusal").orEmpty()))
            "response.output_text.annotation.added" -> (obj["annotation"] as? JsonObject)?.let { annotations += Annotation(key, it) }
            "response.content_part.added", "response.content_part.done" -> (obj["part"] as? JsonObject)?.let { text.append(content(index, obj.int("content_index") ?: 0, it)) }
            "response.output_item.added", "response.output_item.done" -> (obj["item"] as? JsonObject)?.let { text.append(output(index, it)) }
            "response.web_search_call.in_progress", "response.web_search_call.searching", "response.web_search_call.completed" -> {
                val stage = if (type.endsWith(".completed")) WebSearchStage.COMPLETED else WebSearchStage.SEARCHING
                val call = calls[index.toString()]
                // Duplicate or delayed progress must not reopen a finished call.
                if (call == null || call.stage !in setOf(WebSearchStage.COMPLETED, WebSearchStage.FAILED)) calls[index.toString()] = SearchCall(stage)
            }
        }
        if (complete || type in terminalEvents) {
            val response = obj["response"] as? JsonObject ?: obj
            response.array("output")?.forEachIndexed { outputIndex, value ->
                val item = value as? JsonObject ?: return@forEachIndexed
                item.string("id")?.let { itemIndices[it] = outputIndex }
                if (item.string("type") == "reasoning" && !item.string("encrypted_content").isNullOrBlank()) context += item
                text.append(output(outputIndex, item))
            }
        }
        return chunk.copy(textDelta = text.toString(), providerContext = context.filter { sentContext.add(it) })
    }

    private fun output(index: Int, item: JsonObject): String {
        if (item.string("type") == "web_search_call") {
            val action = item["action"] as? JsonObject
            action?.string("query")?.takeIf(String::isNotBlank)?.let(queries::add)
            action?.array("queries")?.forEach { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(queries::add) }
            val previous = calls[index.toString()]
            val error = (item["error"] as? JsonObject)?.string("message") ?: item.string("error")
            val stage = when (item.string("status")) {
                "failed", "incomplete" -> WebSearchStage.FAILED
                "completed" -> WebSearchStage.COMPLETED
                else -> if (previous?.stage in setOf(WebSearchStage.COMPLETED, WebSearchStage.FAILED)) previous!!.stage
                    else if (action?.string("type") in setOf("open_page", "find_in_page")) WebSearchStage.READING else WebSearchStage.SEARCHING
            }
            calls[index.toString()] = SearchCall(stage, error)
            action?.array("sources")?.forEach { element ->
                val value = element as? JsonObject ?: return@forEach
                source(value.string("url"), value.string("title"))?.let { sourceCitations += it }
            }
            // open_page is itself a real source, even when it is not cited in the answer.
            if (action?.string("type") in setOf("open_page", "find_in_page")) source(action?.string("url"), null)?.let { sourceCitations += it }
        }
        val text = StringBuilder()
        item.array("content")?.forEachIndexed { contentIndex, value ->
            (value as? JsonObject)?.let { text.append(content(index, contentIndex, it)) }
        }
        return text.toString()
    }

    private fun content(index: Int, contentIndex: Int, value: JsonObject): String {
        val key = partKey(index, contentIndex)
        val text = value.string("text") ?: value.string("refusal")
        val delta = text?.let { snapshot(key, it) }.orEmpty()
        value.array("annotations")?.forEach { (it as? JsonObject)?.let { annotation -> annotations += Annotation(key, annotation) } }
        return delta
    }

    private val sourceCitations = linkedSetOf<WebCitation>()

    private fun metadata(chunk: ChatStreamChunk): ChatStreamChunk {
        val citations = linkedSetOf<WebCitation>()
        citations += sourceCitations
        pendingAnchors = false
        for ((key, value) in annotations) {
            if (value.string("type") != "url_citation") continue
            val source = source(value.string("url"), value.string("title")) ?: continue
            val part = parts[key]
            val start = part?.text?.codePointIndex(value.int("start_index"))
            val end = part?.text?.codePointIndex(value.int("end_index"))
            val span = anchor(part, start, end)
            val rawStart = value.int("start_index")
            val rawEnd = value.int("end_index")
            if (span == null && part?.reliable != false && rawStart != null && rawEnd != null &&
                rawStart >= 0 && rawEnd > rawStart && end == null) pendingAnchors = true
            if (span != null) citations += source.copy(startIndex = span.first, endIndex = span.second)
            else citations += source
        }
        val activity = activity()
        val changedActivity = activity?.takeIf { it != lastActivity }
        if (activity != null) lastActivity = activity
        return chunk.copy(searchActivity = changedActivity, citations = citations.filter { sentCitations.add(it) })
    }

    private fun activity(): WebSearchActivity? {
        if (calls.isEmpty()) return null
        val active = calls.values.map { it.stage }
        val stage = when {
            failure != null -> WebSearchStage.FAILED
            WebSearchStage.READING in active -> WebSearchStage.READING
            WebSearchStage.SEARCHING in active -> WebSearchStage.SEARCHING
            WebSearchStage.FAILED in active -> WebSearchStage.FAILED
            else -> WebSearchStage.COMPLETED
        }
        return WebSearchActivity(stage, queries.toList(), failure ?: calls.values.firstOrNull { it.stage == WebSearchStage.FAILED }?.error)
    }

    private fun anchor(part: TextPart?, start: Int?, end: Int?): Pair<Int, Int>? {
        if (part == null || !part.reliable || start == null || end == null || start < 0 || end <= start || end > part.text.length) return null
        var position: Int = start
        var globalStart: Int? = null
        for (run in part.runs) {
            if (position < run.local || position >= run.local + run.length) continue
            val global = run.global + position - run.local
            if (globalStart == null) globalStart = global
            if (global != globalStart + position - start) return null
            position = minOf(end, run.local + run.length)
            if (position == end) return globalStart to (globalStart + end - start)
        }
        return null
    }

    private fun source(url: String?, title: String?): WebCitation? {
        if (url == null || url.toHttpUrlOrNull() == null) return null
        return WebCitation(url, title.orEmpty())
    }

    private fun CharSequence.codePointIndex(index: Int?): Int? {
        if (index == null || index < 0) return null
        var codePoints = 0
        var offset = 0
        while (offset < length && codePoints < index) {
            offset += if (Character.isHighSurrogate(this[offset]) && offset + 1 < length && Character.isLowSurrogate(this[offset + 1])) 2 else 1
            codePoints++
        }
        return offset.takeIf { codePoints == index }
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.array(key: String) = get(key) as? JsonArray
    private companion object {
        val terminalEvents = setOf("response.completed", "response.incomplete", "response.failed")
    }
}
