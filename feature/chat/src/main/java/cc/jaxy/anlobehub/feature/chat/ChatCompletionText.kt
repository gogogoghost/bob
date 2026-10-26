package cc.jaxy.anlobehub.feature.chat

import cc.jaxy.anlobehub.core.data.chat.ChatCompletionStatus

internal fun ChatCompletionStatus.messageResource(): Int? = when (this) {
    ChatCompletionStatus.COMPLETED -> null
    ChatCompletionStatus.OUTPUT_LIMIT -> R.string.chat_response_output_limit
    ChatCompletionStatus.INTERRUPTED -> R.string.chat_response_interrupted
    ChatCompletionStatus.STOPPED -> R.string.chat_response_stopped
    ChatCompletionStatus.CONTENT_FILTERED -> R.string.chat_response_filtered
    ChatCompletionStatus.TOOL_CALL_REQUIRED -> R.string.chat_response_tool_required
    ChatCompletionStatus.FAILED -> R.string.chat_response_failed
}
