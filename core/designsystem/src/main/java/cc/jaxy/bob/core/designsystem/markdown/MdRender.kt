package cc.jaxy.bob.core.designsystem.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cc.jaxy.bob.core.designsystem.theme.spacing

/**
 * Synchronous markdown renderer. Blocks arrive pre-parsed ([MdBlock]),
 * so first composition already has full height — no async pop-in.
 */
@Composable
fun MdDocument(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = modifier.fillMaxWidth(),
            // Breathing room between block-level elements (paragraphs,
            // headings, code, quotes, lists, tables).
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            blocks.forEach { MdBlockView(it) }
        }
    }
    if (selectable) SelectionContainer { content() } else content()
}

@Composable
private fun MdBlockView(block: MdBlock) {
    when (block) {
        is MdBlock.Paragraph -> MdRichText(runs = block.runs, style = MaterialTheme.typography.bodyLarge)
        is MdBlock.Heading -> MdRichText(
            runs = block.runs,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }.copy(fontWeight = FontWeight.Bold),
        )
        is MdBlock.Code -> MdCodeBlock(code = block)
        is MdBlock.Quote -> MdQuote(blocks = block.blocks)
        is MdBlock.Ordered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${block.start + index}.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        item.forEach { MdBlockView(it) }
                    }
                }
            }
        }
        is MdBlock.Unordered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        item.forEach { MdBlockView(it) }
                    }
                }
            }
        }
        is MdBlock.Table -> MdTable(block)
        MdBlock.Divider -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun MdRichText(
    runs: List<MdRun>,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val text = remember(runs, style, primary, codeBg) {
        buildAnnotatedString {
            runs.forEach { run ->
                val span = SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    textDecoration = if (run.strike) TextDecoration.LineThrough else null,
                    color = if (run.link != null) primary else androidx.compose.ui.graphics.Color.Unspecified,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) codeBg.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Unspecified,
                )
                if (run.link != null) {
                    // Explicit styles: LinkAnnotation.Url defaults to underlined.
                    val linkStyles = androidx.compose.ui.text.TextLinkStyles(
                        style = span.copy(textDecoration = null),
                    )
                    withLink(LinkAnnotation.Url(run.link, styles = linkStyles)) {
                        withStyle(span) { append(run.text) }
                    }
                } else {
                    withStyle(span) { append(run.text) }
                }
            }
        }
    }
    // LinkAnnotation.Url clicks route through LocalUriHandler automatically.
    Text(
        text = text,
        style = style,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun MdCodeBlock(code: MdBlock.Code) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(spacing.m),
    ) {
        code.language?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = code.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            softWrap = false,
        )
    }
}

@Composable
private fun MdQuote(blocks: List<MdBlock>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, bottom = 2.dp, end = 10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
                .width(4.dp)
                .fillMaxHeight(),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            blocks.forEach { MdBlockView(it) }
        }
    }
}

@Composable
private fun MdTable(table: MdBlock.Table) {
    val headerStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
    val cellStyle = MaterialTheme.typography.bodyMedium
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (table.headers.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                table.headers.forEach { MdRichText(runs = it, style = headerStyle) }
            }
            HorizontalDivider()
        }
        table.rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                row.forEach { MdRichText(runs = it, style = cellStyle) }
            }
        }
    }
}
