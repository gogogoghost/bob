package cc.jaxy.bob.core.data.chat

import okhttp3.Request

internal data class PreparedChatRequest(
    val request: Request,
    val protocol: String,
    /** Provider-hosted native search (Responses `web_search`). */
    val webSearchEnabled: Boolean = false,
    /** App-executed `web_search` function tool; enables function-call parsing. */
    val toolSearch: Boolean = false,
)
