package cc.jaxy.anlobehub.feature.chat

import cc.jaxy.anlobehub.core.data.chat.WebCitation
import java.net.URI

/** Source shared by native annotations and legacy Markdown footnotes. */
data class ChatCitation(val index: String, val title: String, val url: String) {
    val host: String get() = runCatching {
        URI(url).host?.removePrefix("www.") ?: url
    }.getOrDefault(url)
}

/** Display-only citations. The original answer remains the copy/persistence source. */
internal data class CitationPresentation(
    val markdown: String,
    val sources: List<ChatCitation>,
)

internal fun isSafeWebUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        url.none { it.isWhitespace() || it.isISOControl() }
}.getOrDefault(false)

internal fun presentCitations(body: String, citations: List<WebCitation>): CitationPresentation {
    val sources = linkedMapOf<String, ChatCitation>()
    citations.forEach { citation ->
        if (!isSafeWebUrl(citation.url)) return@forEach
        val existing = sources[citation.url]
        if (existing == null) {
            sources[citation.url] = ChatCitation(
                index = (sources.size + 1).toString(),
                title = citation.title.ifBlank { citation.url },
                url = citation.url,
            )
        } else if (existing.title == existing.url && citation.title.isNotBlank()) {
            sources[citation.url] = existing.copy(title = citation.title)
        }
    }
    if (sources.isEmpty()) return CitationPresentation(body, emptyList())
    if (citations.none { it.startIndex != null && it.endIndex != null }) {
        return CitationPresentation(body, sources.values.toList())
    }
    val protected = markdownProtectedCharacters(body)
    val insertions = sortedMapOf<Int, LinkedHashSet<String>>()
    citations.forEach { citation ->
        val source = sources[citation.url] ?: return@forEach
        val start = citation.startIndex ?: return@forEach
        val end = citation.endIndex ?: return@forEach
        if (start < 0 || end <= start || end > body.length ||
            !body.isUtf16Boundary(start) || !body.isUtf16Boundary(end)
        ) return@forEach
        // Never place generated Markdown inside code, a link, HTML, or a delimiter.
        if ((start until end).any { protected[it] } ||
            (end < body.length && protected[end]) ||
            body[end - 1] in "*_~\\" ||
            body[end - 1].isWhitespace() ||
            (end < body.length && body[end] in "*_~\\")
        ) return@forEach
        insertions.getOrPut(end) { linkedSetOf() }.add(source.url)
    }
    if (insertions.isEmpty()) return CitationPresentation(body, sources.values.toList())
    val display = buildString {
        var cursor = 0
        insertions.forEach { (end, urls) ->
            append(body, cursor, end)
            urls.forEach { url ->
                append(" [")
                append(sources.getValue(url).index)
                append("](<")
                append(url)
                append(">)")
            }
            cursor = end
        }
        append(body, cursor, body.length)
    }
    return CitationPresentation(display, sources.values.toList())
}

private fun String.isUtf16Boundary(index: Int): Boolean =
    index == 0 || index == length || !(this[index - 1].isHighSurrogate() && this[index].isLowSurrogate())

private val FENCE = Regex("^\\s*(?:>\\s*)*(?:(?:[-+*]|\\d+[.)])\\s+)?(`{3,}|~{3,})(.*)$")
private val REFERENCE_DEFINITION = Regex("^ {0,3}\\[[^]]+]:")

/** Conservative lexical exclusions: unreliable locations remain visible as source cards. */
private fun markdownProtectedCharacters(text: String): BooleanArray {
    val protected = BooleanArray(text.length)
    fun protect(start: Int, end: Int) {
        for (index in start until end.coerceAtMost(text.length)) protected[index] = true
    }
    var fenceChar: Char? = null
    var fenceLength = 0
    var lineStart = 0
    while (lineStart < text.length) {
        val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val end = if (newline < text.length) newline + 1 else newline
        val line = text.substring(lineStart, newline)
        val leading = line.takeWhile { it == ' ' }.length
        val trimmed = line.drop(leading)
        val fence = FENCE.matchEntire(line)
        val delimiter = fence?.groupValues?.get(1).orEmpty()
        val run = delimiter.length
        val marker = delimiter.firstOrNull()
        if (fenceChar != null) {
            protect(lineStart, end)
            if (marker == fenceChar && run >= fenceLength && fence?.groupValues?.get(2).isNullOrBlank()) {
                fenceChar = null
            }
        } else if (marker != null) {
            fenceChar = marker
            fenceLength = run
            protect(lineStart, end)
        } else if (leading >= 4 || line.startsWith('\t') || trimmed.startsWith('>') || trimmed.startsWith('<') ||
            REFERENCE_DEFINITION.containsMatchIn(line)
        ) {
            protect(lineStart, end)
        }
        lineStart = end
    }
    var index = 0
    while (index < text.length) {
        if (protected[index]) {
            index++
            continue
        }
        when (text[index]) {
            '\\' -> {
                protect(index, index + 2)
                index += 2
            }
            '`' -> {
                var count = 1
                while (index + count < text.length && text[index + count] == '`') count++
                val delimiter = "`".repeat(count)
                var close = text.indexOf(delimiter, index + count)
                while (close >= 0 &&
                    ((close > 0 && text[close - 1] == '`') ||
                        (close + count < text.length && text[close + count] == '`'))
                ) close = text.indexOf(delimiter, close + count)
                val end = if (close < 0) text.length else close + count
                protect(index, end)
                index = end
            }
            '[' -> {
                // Protect the whole label and destination, including nested links/images.
                var end = index + 1
                var depth = 1
                while (end < text.length && depth > 0 && text[end] != '\n') {
                    if (text[end] == '\\') end++
                    else if (text[end] == '[') depth++
                    else if (text[end] == ']') depth--
                    end++
                }
                if (end < text.length && text[end] in "([") {
                    val opening = text[end]
                    val closing = if (opening == '(') ')' else ']'
                    depth = 1
                    end++
                    while (end < text.length && depth > 0) {
                        if (text[end] == '\\') end++
                        else if (text[end] == opening) depth++
                        else if (text[end] == closing) depth--
                        end++
                    }
                }
                protect(index, end)
                index = end
            }
            '<' -> {
                val close = text.indexOf('>', index + 1)
                val end = if (close < 0) text.length else close + 1
                protect(index, end)
                index = end
            }
            else -> index++
        }
    }
    return protected
}
