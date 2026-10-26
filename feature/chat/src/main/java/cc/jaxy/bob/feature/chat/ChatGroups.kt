package cc.jaxy.bob.feature.chat

import cc.jaxy.bob.core.data.chat.ChatMessage

sealed interface ChatTurn {
    data class User(val message: ChatMessage) : ChatTurn
    data class AssistantGroup(val answer: ChatMessage?) : ChatTurn
}

fun List<ChatMessage>.toTurns(): List<ChatTurn> = mapNotNull { message ->
    when (message.role) {
        "user" -> ChatTurn.User(message)
        "assistant" -> ChatTurn.AssistantGroup(message)
        else -> null
    }
}
