package cc.jaxy.bob.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration

/**
 * Self-rendered markdown: CommonMark parses synchronously (sub-millisecond
 * for chat sizes), so first composition already has full height — no async
 * pop-in on scroll. `streaming` only disables selection (recomposition
 * cost); the same components render both phases, so stream-to-settled
 * swaps without flashing.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    if (markdown.isEmpty()) return
    val cited = remember(markdown) { markdown.resolveFootnotes() }
    // Sync parse on first composition; growth appends re-parse cheaply
    // (CommonMark parses ~100KB/ms; chat messages are single-digit KB).
    val blocks = remember(cited) {
        cc.jaxy.bob.core.designsystem.markdown.parseMarkdown(cited)
    }
    cc.jaxy.bob.core.designsystem.markdown.MdDocument(
        blocks = blocks,
        modifier = modifier,
        selectable = !streaming,
    )
}

/**
 * Citation markers -> `[🔗](url)`.
 *
 * Recognized (case-insensitive): `[^n]` (+ trailing `[^n]: url` definitions),
 * `[citation:n]`, `[citations:n]`, and `【n】`. Unknown refs degrade to plain
 * text, never to a dead link. The emoji is the only inline affordance; the
 * tap-confirm dialog owns the actual navigation.
 */
private fun String.resolveFootnotes(): String {
    var body = this
    // Definition lines first: [^n]: <url> | [^n]: [title](url)
    val defs = mutableMapOf<String, String>()
    val defPattern = Regex("""(?m)^\[\^(\d+)\]:\s*(.+)$""")
    body = defPattern.replace(body) { match ->
        val url = Regex("""\((https?://[^)]+)\)""").find(match.groupValues[2])
            ?.groupValues?.getOrNull(1)
            ?: match.groupValues[2].trim().split(Regex("""\\s+""")).firstOrNull().orEmpty()
        if (url.startsWith("http")) defs[match.groupValues[1]] = url
        ""
    }
    body = Regex("""\[\^(\d+)\]""").replace(body) { match ->
        val url = defs[match.groupValues[1]]
        if (url != null) "[🔗]($url)" else match.groupValues[1]
    }
    return body.replace(Regex("\n{3,}"), "\n\n").trim()
}
