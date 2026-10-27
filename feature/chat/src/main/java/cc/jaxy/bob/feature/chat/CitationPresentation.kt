package cc.jaxy.bob.feature.chat

import cc.jaxy.bob.core.data.chat.WebCitation
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
    /** Linked sources in first-use order, renumbered from 1 for display. */
    val usedSources: List<ChatCitation> = sources,
)

internal fun isSafeWebUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        url.none { it.isWhitespace() || it.isISOControl() }
}.getOrDefault(false)

/** Model-written citation variants rewritten to the link emoji, resolved by source number. */
private val CITATION_MARK = Regex("""\[\s*citations?\s*:\s*(\d+)\s*\]|【\s*(\d+)\s*】""", RegexOption.IGNORE_CASE)

/**
 * Numbering word a model may append right after the emoji link: bare 🔗 only.
 * Only explicit numbering shapes are eaten (`来源2`, `source 2`, `[2]`, `(2)`,
 * `:2`); a bare prose number (`🔗 2 people`) is kept — it cannot be told
 * apart from content without a marker word or brackets.
 */
private val TRAILING_NUMBER = Regex(
    """\s*(?:\[\s*\d+\s*\]|\(\s*\d+\s*\)|[:：．.、]\s*\d+|(?:sources?|来源|出处|参考|參照|source)\s*\d+)""",
    RegexOption.IGNORE_CASE,
)

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
    if (sources.isEmpty()) return CitationPresentation(body, emptyList(), emptyList())
    val byIndex = sources.values.associateBy { it.index }
    // Normalize first: [citation:3] / [citations:3] / 【3】 -> [🔗](url) or dropped.
    // Runs before anchored insertion so stray marks never render as raw text,
    // and inside code/links they stay untouched (same guard as anchored marks).
    val normalized = normalizeCitationMarks(body, byIndex)
    if (citations.none { it.startIndex != null && it.endIndex != null }) {
        return withUsed(normalized, sources.values.toList())
    }
    val base = normalized
    if (base == body) return insertAnchoredCitations(body, citations, sources)
    // Mixed content: anchored offsets refer to the ORIGINAL body; merging both
    // passes would misplace marks. Prefer the explicit model marks; anchored
    // sources remain available as cards below.
    return withUsed(base, sources.values.toList())
}

/** Rewrites model-written `[citation:N]`-family marks to link-emoji links. */
private fun normalizeCitationMarks(body: String, byIndex: Map<String, ChatCitation>): String {
    if (!CITATION_MARK.containsMatchIn(body)) return body
    // Code-only guard: `[citation:N]` shapes are rewrite targets, never native
    // Markdown links, so the shared `[`-label protection must not apply here.
    // Only fenced blocks, code spans, autolinks/HTML, and reference-definition
    // lines block the rewrite.
    val codeGuard = markdownCodeProtectedCharacters(body)
    val out = StringBuilder()
    var cursor = 0
    for (match in CITATION_MARK.findAll(body)) {
        val start = match.range.first
        val end = match.range.last + 1
        if ((start until end).any { codeGuard[it] } || (end < body.length && codeGuard[end])) continue
        val number = match.groupValues[1].ifBlank { match.groupValues[2] }
        val source = byIndex[number.trimStart('0').ifBlank { "0" }] ?: byIndex[number]
        out.append(body, cursor, start)
        if (source != null) {
            out.append("[\uD83D\uDD17](<")
            out.append(source.url)
            out.append(">)")
        }
        // else: drop the dangling mark entirely (no dead text, no dead link).
        cursor = end
        // Swallow one adjacent numbering word the model appended itself
        // (e.g. `🔗来源2`, `🔗 source 2`, `🔗[2]`). A space separator is kept
        // when prose follows (`🔗 来源2.` -> `🔗 .`), matching the post-pass.
        TRAILING_NUMBER.matchAt(body, cursor)?.value?.let { eaten ->
            val rest = body.substring(cursor + eaten.length)
            if (eaten.isNotEmpty() && eaten[0].isWhitespace() && rest.isNotEmpty() && !rest[0].isWhitespace()) {
                out.append(' ')
            }
            cursor += eaten.length
        }
    }
    out.append(body, cursor, body.length)
    return out.toString()
}

/**
 * Post-pass over rendered markdown, model-habit agnostic:
 * - `[🔗](url)` + numbering word -> bare link (`🔗来源2` collapses);
 * - bare `🔗https://...` (emoji glued to a raw URL, never a real Markdown
 *   link) -> a single `[🔗](url)` link. Link targets are untouched.
 * A single separating space is kept when the gap was space-separated.
 */
private fun String.stripTrailingNumbersAfterEmojiLinks(allowed: Set<String> = emptySet()): String {
    // Fold first: emoji glued to a raw URL is one link, not text + autolink.
    // Only allowlisted source URLs fold; anything else stays visible text so a
    // model cannot mint clickable links to arbitrary destinations.
    val folded = BARE_EMOJI_URL.replace(this) { match ->
        // The URL class also eats a glued numbering tail (CJK has no spaces):
        // back off to the longest allowlisted prefix so the tail survives for
        // the numbering eater below. Unknown URLs never fold.
        var url = match.groupValues[1]
        while (url.isNotEmpty() && url !in allowed) url = url.dropLast(1)
        if (url.isEmpty() || !isSafeWebUrl(url)) match.value else "[🔗](<$url>)" + match.groupValues[1].substring(url.length)
    }
    val link = Regex("""\[🔗\]\(<https?://[^)\s>]+>\)|\[🔗\]\(https?://[^)\s]+\)""")
    val out = StringBuilder()
    var cursor = 0
    for (match in link.findAll(folded)) {
        out.append(folded, cursor, match.range.last + 1)
        cursor = match.range.last + 1
        // Swallow repeat numbering words (`🔗来源2[3]`): loop until clean.
        while (true) {
            val eat = TRAILING_NUMBER.matchAt(folded, cursor)?.value.orEmpty()
            if (eat.isEmpty()) break
            // Keep one space only if the gap was space-separated AND more text follows.
            val rest = folded.substring(cursor + eat.length)
            val keepSpace = eat[0].isWhitespace() && rest.isNotEmpty() && !rest[0].isWhitespace()
            cursor += eat.length
            if (keepSpace) {
                out.append(' ')
                break
            }
        }
    }
    out.append(folded, cursor, folded.length)
    return out.toString()
}

/** Emoji glued to a raw URL (optional intervening spaces): not a Markdown link yet. */
private val BARE_EMOJI_URL = Regex("""🔗\s*(https?://[^\s<>\)\]]+)""")

private fun insertAnchoredCitations(
    body: String,
    citations: List<WebCitation>,
    sources: LinkedHashMap<String, ChatCitation>,
): CitationPresentation {
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
    if (insertions.isEmpty()) return withUsed(body, sources.values.toList())
    val display = buildString {
        var cursor = 0
        insertions.forEach { (end, urls) ->
            append(body, cursor, end)
            urls.forEach { url ->
                append(" [\uD83D\uDD17](<")
                append(url)
                append(">)")
            }
            cursor = end
        }
        append(body, cursor, body.length)
    }
    return withUsed(display, sources.values.toList())
}

/** Keeps the full source list for lookup, but cards only show linked URLs. */
private fun withUsed(markdown: String, sources: List<ChatCitation>): CitationPresentation {
    val allowed = sources.map { it.url }.toSet()
    val cleaned = markdown.stripTrailingNumbersAfterEmojiLinks(allowed)
    val ordered = linkedUrls(cleaned)
    if (ordered.isEmpty()) return CitationPresentation(markdown, sources, emptyList())
    val byUrl = sources.associateBy { it.url }
    // Only linked sources become cards. Renumber after filtering so the cards
    // always read 1..n instead of showing gaps for uncited search hits.
    val used = ordered.mapNotNull { byUrl[it] }
        .mapIndexed { position, citation -> citation.copy(index = (position + 1).toString()) }
    return CitationPresentation(cleaned, sources, used)
}

/** URLs linked from the rendered markdown (`[🔗](<url>)` / `[🔗](url)`), in first-use order. */
private val LINK_TARGET = Regex("""\[[^\]]*\]\(<?(https?://[^)\s>]+)>?\)""")

private fun linkedUrls(markdown: String): List<String> {
    val seen = linkedSetOf<String>()
    for (match in LINK_TARGET.findAll(markdown)) {
        val url = match.groupValues[1]
        if (isSafeWebUrl(url)) seen += url
    }
    return seen.toList()
}

private fun String.isUtf16Boundary(index: Int): Boolean =
    index == 0 || index == length || !(this[index - 1].isHighSurrogate() && this[index].isLowSurrogate())

private val FENCE = Regex("^\\s*(?:>\\s*)*(?:(?:[-+*]|\\d+[.)])\\s+)?(`{3,}|~{3,})(.*)$")
private val REFERENCE_DEFINITION = Regex("^ {0,3}\\[[^]]+]:")

/** Code/HTML/reference-definition mask for citation-mark rewrites (no `[`-label rule). */
private fun markdownCodeProtectedCharacters(text: String): BooleanArray {
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
