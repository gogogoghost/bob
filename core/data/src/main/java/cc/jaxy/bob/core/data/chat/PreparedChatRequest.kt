package cc.jaxy.bob.core.data.chat

import okhttp3.Request

internal data class PreparedChatRequest(
    val request: Request,
    val protocol: String,
    val webSearchEnabled: Boolean = false,
)
