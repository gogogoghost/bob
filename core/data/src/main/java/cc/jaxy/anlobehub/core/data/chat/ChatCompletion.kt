package cc.jaxy.anlobehub.core.data.chat

import java.io.IOException
import kotlinx.serialization.Serializable

@Serializable
enum class ChatCompletionStatus {
    COMPLETED,
    OUTPUT_LIMIT,
    INTERRUPTED,
    STOPPED,
    CONTENT_FILTERED,
    TOOL_CALL_REQUIRED,
    FAILED,
}

/** A provider's generation result, separate from its text and from HTTP transport success. */
@Serializable
data class ChatCompletion(
    val status: ChatCompletionStatus,
    val finishReason: String? = null,
)

class ChatStreamException(
    val completion: ChatCompletion,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
