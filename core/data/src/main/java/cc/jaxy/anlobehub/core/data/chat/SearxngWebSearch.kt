package cc.jaxy.anlobehub.core.data.chat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalizes an explicitly configured instance; never supplies a public search service. */
fun normalizeSearxngUrl(input: String): String {
    val text = input.trim()
    if (text.isEmpty()) return ""
    require(HTTP_SCHEME.containsMatchIn(text) && text.none { it.isISOControl() }) {
        "SearXNG URL must be an HTTP or HTTPS instance URL."
    }
    val url = requireNotNull(text.toHttpUrlOrNull()) { "SearXNG URL is invalid." }
    require(url.username.isEmpty() && url.password.isEmpty() && !text.substringAfter("://").substringBefore('/').contains('@')) {
        "SearXNG URL must not contain a username or password."
    }
    require(url.query == null && url.fragment == null) {
        "SearXNG URL must not contain a query or fragment."
    }
    val root = url.encodedPath.trimEnd('/')
    val path = if (root.endsWith("/search")) root else "$root/search"
    return url.newBuilder().encodedPath(path).build().toString()
}

private val HTTP_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
