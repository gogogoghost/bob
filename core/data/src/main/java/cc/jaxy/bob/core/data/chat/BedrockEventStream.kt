package cc.jaxy.bob.core.data.chat

import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.BufferedSource

/** Decodes ConverseStream's AWS binary framing, not SSE. The caller owns and closes [source]. */
internal fun readBedrockEvents(source: BufferedSource): Sequence<JsonObject> = sequence {
    val json = Json { ignoreUnknownKeys = true }
    while (!source.exhausted()) {
        val prelude = source.readByteArray(12)
        val fields = ByteBuffer.wrap(prelude)
        val length = fields.int
        val headersLength = fields.int
        val preludeCrc = fields.int.toLong() and 0xffffffffL
        val checksum = CRC32().apply { update(prelude, 0, 8) }
        if (checksum.value != preludeCrc) throw IOException("Invalid Bedrock event prelude checksum")
        // Smithy permits 24 MiB payloads and 128 KiB headers, plus the 16-byte framing.
        if (headersLength !in 0..131072 || length < 16 || headersLength > length - 16 || length - 16 - headersLength > 25165824) {
            throw IOException("Invalid Bedrock event frame length")
        }
        val remaining = source.readByteArray((length - 12).toLong())
        checksum.reset()
        checksum.update(prelude)
        checksum.update(remaining, 0, remaining.size - 4)
        val messageCrc = ByteBuffer.wrap(remaining, remaining.size - 4, 4).int.toLong() and 0xffffffffL
        if (checksum.value != messageCrc) throw IOException("Invalid Bedrock event checksum")
        val headers = ByteBuffer.wrap(remaining, 0, headersLength).slice()
        val strings = mutableMapOf<String, String>()
        try {
            while (headers.hasRemaining()) {
                val nameBytes = ByteArray(headers.get().toInt() and 0xff)
                headers.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                when (val type = headers.get().toInt() and 0xff) {
                    0, 1 -> Unit
                    2 -> headers.get()
                    3 -> headers.short
                    4 -> headers.int
                    5, 8 -> headers.long
                    6, 7 -> {
                        val size = headers.short.toInt() and 0xffff
                        if (size > headers.remaining()) throw IOException("Truncated Bedrock event header")
                        if (type == 7) {
                            val bytes = ByteArray(size)
                            headers.get(bytes)
                            strings[name] = String(bytes, Charsets.UTF_8)
                        } else headers.position(headers.position() + size)
                    }
                    9 -> headers.position(headers.position() + 16)
                    else -> throw IOException("Unknown Bedrock event header type $type")
                }
            }
        } catch (error: java.nio.BufferUnderflowException) {
            throw IOException("Truncated Bedrock event headers", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid Bedrock event headers", error)
        }
        val payload = String(remaining, headersLength, length - headersLength - 16, Charsets.UTF_8)
        val messageType = strings[":message-type"]
        if (messageType == "exception" || messageType == "error") {
            throw IOException("Bedrock ${strings[":exception-type"] ?: strings[":error-code"] ?: "stream error"}: ${strings[":error-message"] ?: payload}")
        }
        if (payload.isNotBlank()) {
            val event = json.parseToJsonElement(payload) as? JsonObject
                ?: throw IOException("Invalid Bedrock event JSON")
            yield(JsonObject(event + ("type" to JsonPrimitive(strings[":event-type"].orEmpty()))))
        }
    }
}
