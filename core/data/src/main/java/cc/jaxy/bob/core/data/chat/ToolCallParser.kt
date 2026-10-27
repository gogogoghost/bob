package cc.jaxy.bob.core.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Accumulates provider streaming function-call fragments into complete, JSON-valid calls.
 * Partial arguments are never emitted, so a caller can safely execute every returned call.
 */
internal class ToolCallParser(private val protocol: String) {
    private class PendingCall(
        val key: String,
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
        var emitted: Boolean = false,
    )

    private val pending = LinkedHashMap<String, PendingCall>()
    private val indexKeys = HashMap<Int, String>()
    private val emittedIds = HashSet<String>()
    private val seenSignatures = HashSet<String>()
    private var generated = 0

    fun consume(obj: JsonObject, event: String, complete: Boolean): List<ChatToolCall> {
        val calls = mutableListOf<ChatToolCall>()
        when (protocol) {
            "anthropic" -> consumeAnthropic(obj, event, complete, calls)
            "gemini" -> consumeGemini(obj, calls)
            "responses" -> consumeResponses(obj, event, complete, calls)
            "ollama" -> consumeOllama(obj, calls)
            "bedrock" -> consumeBedrock(obj, event, calls)
            "completions", "straico" -> Unit
            else -> consumeOpenAi(obj, complete, calls)
        }
        return calls
    }

    /** Emits calls whose argument stream ended without an explicit per-call terminator. */
    fun drain(): List<ChatToolCall> {
        val calls = mutableListOf<ChatToolCall>()
        emitPending(calls)
        return calls
    }

    private fun consumeOpenAi(obj: JsonObject, complete: Boolean, out: MutableList<ChatToolCall>) {
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val holder = (if (complete) choice["message"] else choice["delta"]) as? JsonObject ?: return
        (holder["tool_calls"] as? JsonArray)?.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val index = item.int("index") ?: 0
            val id = item.string("id").orEmpty()
            val function = item["function"] as? JsonObject
            val key = indexKeys.getOrPut(index) { id.ifBlank { "index:$index" } }
            val call = pending.getOrPut(key) { PendingCall(key) }
            if (id.isNotBlank()) call.id = id
            appendName(call, function?.string("name").orEmpty())
            call.arguments.append(function?.string("arguments").orEmpty())
        }
        // Deprecated single-function form still appears on some compatible servers.
        (holder["function_call"] as? JsonObject)?.let { function ->
            val call = pending.getOrPut("legacy") { PendingCall("legacy") }
            appendName(call, function.string("name").orEmpty())
            call.arguments.append(function.string("arguments").orEmpty())
        }
        val reason = choice.string("finish_reason")
        if (complete || reason == "tool_calls" || reason == "function_call") emitPending(out)
    }

    private fun consumeAnthropic(obj: JsonObject, event: String, complete: Boolean, out: MutableList<ChatToolCall>) {
        when (val type = obj.string("type") ?: event) {
            "content_block_start" -> {
                val index = obj.int("index") ?: 0
                val block = obj["content_block"] as? JsonObject ?: return
                if (block.string("type") != "tool_use") return
                val id = block.string("id").orEmpty()
                val key = id.ifBlank { "index:$index" }
                val call = pending.getOrPut(key) { PendingCall(key) }
                call.id = id
                appendName(call, block.string("name").orEmpty())
                // Anthropic normally streams `{}` here and the real input through deltas.
                (block["input"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { call.arguments.append(it.toString()) }
                indexKeys[index] = key
            }
            "content_block_delta" -> {
                val delta = obj["delta"] as? JsonObject ?: return
                if (delta.string("type") != "input_json_delta") return
                val key = indexKeys[obj.int("index") ?: return] ?: return
                pending[key]?.arguments?.append(delta.string("partial_json").orEmpty())
            }
            "content_block_stop" -> indexKeys[obj.int("index") ?: return]?.let { key ->
                emitKey(key, out)
            }
            "message_stop" -> emitPending(out)
            else -> if (complete) {
                (obj["content"] as? JsonArray)?.forEach { element ->
                    val block = element as? JsonObject ?: return@forEach
                    if (block.string("type") != "tool_use") return@forEach
                    val id = block.string("id").orEmpty()
                    val key = id.ifBlank { "index:${out.size}" }
                    val call = pending.getOrPut(key) { PendingCall(key) }
                    call.id = id
                    appendName(call, block.string("name").orEmpty())
                    (block["input"] as? JsonObject)?.let { call.arguments.append(it.toString()) }
                    emitKey(key, out)
                }
            }
        }
    }

    private fun consumeGemini(obj: JsonObject, out: MutableList<ChatToolCall>) {
        val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return
        parts.forEach { element ->
            val function = (element as? JsonObject)?.get("functionCall") as? JsonObject ?: return@forEach
            val name = function.string("name").orEmpty()
            if (name.isBlank()) return@forEach
            val arguments = (function["args"] as? JsonObject)?.toString() ?: "{}"
            if (!seenSignatures.add("$name|$arguments")) return@forEach
            out += ChatToolCall("call_${++generated}", name, arguments)
        }
    }

    private fun consumeResponses(obj: JsonObject, event: String, complete: Boolean, out: MutableList<ChatToolCall>) {
        val type = obj.string("type") ?: event
        if (type == "response.output_item.done") {
            val item = obj["item"] as? JsonObject
            if (item?.string("type") == "function_call") emitResponsesItem(item, out)
        }
        if (complete || type in RESPONSE_END_EVENTS) {
            (obj["output"] as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject
                if (item?.string("type") == "function_call") emitResponsesItem(item, out)
            }
        }
    }

    private fun emitResponsesItem(item: JsonObject, out: MutableList<ChatToolCall>) {
        val name = item.string("name").orEmpty()
        if (name.isBlank()) return
        val id = item.string("call_id").orEmpty().ifBlank { item.string("id").orEmpty() }
        val arguments = item.string("arguments").orEmpty().ifBlank { "{}" }
        val parsed = parseArguments(arguments) ?: return
        if (id.isBlank() && !seenSignatures.add("$name|$parsed")) return
        val callId = id.ifBlank { "call_${++generated}" }
        if (!emittedIds.add(callId)) return
        out += ChatToolCall(callId, name, parsed)
    }

    private fun consumeOllama(obj: JsonObject, out: MutableList<ChatToolCall>) {
        val message = obj["message"] as? JsonObject ?: return
        (message["tool_calls"] as? JsonArray)?.forEach { element ->
            val function = (element as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
            val name = function.string("name").orEmpty()
            if (name.isBlank()) return@forEach
            val arguments = when (val value = function["arguments"]) {
                is JsonObject -> value.toString()
                is JsonPrimitive -> value.contentOrNull.orEmpty()
                else -> "{}"
            }
            val parsed = parseArguments(arguments) ?: return@forEach
            if (!seenSignatures.add("$name|$parsed")) return@forEach
            val id = "call_${++generated}"
            out += ChatToolCall(id, name, parsed)
        }
    }

    private fun consumeBedrock(obj: JsonObject, event: String, out: MutableList<ChatToolCall>) {
        when (val type = obj.string("type") ?: event) {
            "contentBlockStart" -> {
                val block = obj["start"] as? JsonObject ?: return
                val tool = block["toolUse"] as? JsonObject ?: return
                val index = (obj["contentBlockIndex"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                val id = tool.string("toolUseId").orEmpty()
                val key = id.ifBlank { "index:$index" }
                val call = pending.getOrPut(key) { PendingCall(key) }
                call.id = id
                appendName(call, tool.string("name").orEmpty())
                indexKeys[index] = key
            }
            "contentBlockDelta" -> {
                val tool = (obj["delta"] as? JsonObject)?.get("toolUse") as? JsonObject ?: return
                val index = (obj["contentBlockIndex"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
                val key = indexKeys[index] ?: return
                pending[key]?.arguments?.append(tool.string("input").orEmpty())
            }
            "contentBlockStop" -> {
                val index = (obj["contentBlockIndex"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
                indexKeys[index]?.let { key -> emitKey(key, out) }
            }
            "messageStop" -> emitPending(out)
        }
    }

    private fun appendName(call: PendingCall, fragment: String) {
        if (fragment.isBlank()) return
        call.name = when {
            call.name.isEmpty() -> fragment
            fragment.startsWith(call.name) -> fragment
            call.name.startsWith(fragment) -> call.name
            else -> call.name + fragment
        }
    }

    private fun emitKey(key: String, out: MutableList<ChatToolCall>) {
        val call = pending[key] ?: return
        if (call.emitted) return
        if (call.name.isBlank()) return
        val arguments = parseArguments(call.arguments.toString().ifBlank { "{}" }) ?: return
        val id = call.id.ifBlank { "call_${++generated}" }
        if (!emittedIds.add(id)) { call.emitted = true; return }
        call.id = id
        call.emitted = true
        out += ChatToolCall(id, call.name, arguments)
    }

    private fun emitPending(out: MutableList<ChatToolCall>) {
        for (key in pending.keys.toList()) emitKey(key, out)
    }

    /** Only complete JSON objects are executable; a truncated stream must not run a partial query. */
    private fun parseArguments(value: String): String? {
        val trimmed = value.trim().ifEmpty { return "{}" }
        val element: JsonElement = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return when (element) {
            is JsonObject -> element.toString()
            is JsonPrimitive -> if (element.isString) {
                val text = element.contentOrNull.orEmpty()
                if (text.isBlank()) null else JsonObject(mapOf("query" to JsonPrimitive(text))).toString()
            } else null
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    companion object {
        private val RESPONSE_END_EVENTS = setOf("response.completed", "response.incomplete", "response.failed")
    }
}
