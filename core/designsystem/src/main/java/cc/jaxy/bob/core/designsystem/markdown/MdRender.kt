package cc.jaxy.bob.core.designsystem.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
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
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val text = remember(runs, primary, codeBg) { mdAnnotatedText(runs, primary, codeBg) }
    // LinkAnnotation.Url clicks route through LocalUriHandler automatically.
    Text(
        text = text,
        style = style,
        modifier = modifier.fillMaxWidth(),
        textAlign = textAlign,
    )
}

/** Shared with table measurement so measured and painted widths use the same spans. */
private fun mdAnnotatedText(runs: List<MdRun>, linkColor: Color, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        runs.forEach { run ->
            val span = SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                textDecoration = if (run.strike) TextDecoration.LineThrough else null,
                color = if (run.link != null) linkColor else Color.Unspecified,
                fontFamily = if (run.code) FontFamily.Monospace else null,
                background = if (run.code) codeBackground.copy(alpha = 0.6f) else Color.Unspecified,
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

private val TableCellPadding = 10.dp
private val TableMinColumnWidth = 48.dp
private val TableMaxColumnWidth = 240.dp
private val TableLineWidth = 1.dp
private val TableHeaderTint = 0.4f

/** Column widths from measured cell content: widest cell per column, clamped to sane bounds. */
internal fun tableColumnWidths(measured: List<List<Int>>, minWidth: Int, maxWidth: Int): List<Int> {
    val columns = measured.maxOfOrNull { it.size } ?: 0
    return (0 until columns).map { column ->
        val widest = measured.maxOfOrNull { row -> row.getOrNull(column) ?: 0 } ?: 0
        widest.coerceIn(minWidth, maxWidth)
    }
}

internal fun MdAlign.toTextAlign(): TextAlign = when (this) {
    MdAlign.START -> TextAlign.Start
    MdAlign.CENTER -> TextAlign.Center
    MdAlign.END -> TextAlign.End
}

/**
 * A real grid: every row shares measured column widths, so text lines up; borders are drawn per
 * row (verticals span the row height) and by the outer frame. Cells wrap at [TableMaxColumnWidth]
 * instead of stretching the table, and the whole grid scrolls horizontally when it overflows.
 */
@Composable
private fun MdTable(table: MdBlock.Table) {
    val columnCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return
    val headerCells = (0 until columnCount).map { table.headers.getOrNull(it).orEmpty() }
    val bodyRows = table.rows.map { row -> (0 until columnCount).map { row.getOrNull(it).orEmpty() } }
    val headerStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
    val cellStyle = MaterialTheme.typography.bodyMedium
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TableHeaderTint)
    val shape = RoundedCornerShape(10.dp)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val paddingPx = with(density) { TableCellPadding.roundToPx() }
    val minWidthPx = with(density) { TableMinColumnWidth.roundToPx() } + paddingPx * 2
    val maxWidthPx = with(density) { TableMaxColumnWidth.roundToPx() } + paddingPx * 2
    val widths = remember(
        headerCells, bodyRows, headerStyle, cellStyle, linkColor, codeBackground,
        measurer, minWidthPx, maxWidthPx,
    ) {
        fun measure(runs: List<MdRun>, style: TextStyle): Int = measurer.measure(
            text = mdAnnotatedText(runs, linkColor, codeBackground),
            style = style,
            constraints = Constraints(maxWidth = maxWidthPx - paddingPx * 2),
        ).size.width + paddingPx * 2
        tableColumnWidths(
            measured = listOf(headerCells.map { measure(it, headerStyle) }) +
                bodyRows.map { row -> row.map { measure(it, cellStyle) } },
            minWidth = minWidthPx,
            maxWidth = maxWidthPx,
        )
    }
    val tableWidth = with(density) { widths.sum().toDp() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .width(tableWidth)
                .clip(shape)
                .border(BorderStroke(1.dp, lineColor), shape),
        ) {
            MdTableRow(headerCells, widths, headerStyle, table.alignments, paddingPx, lineColor,
                background = headerBackground, drawBottom = bodyRows.isNotEmpty())
            bodyRows.forEachIndexed { index, row ->
                MdTableRow(row, widths, cellStyle, table.alignments, paddingPx, lineColor,
                    background = null, drawBottom = index != bodyRows.lastIndex)
            }
        }
    }
}

@Composable
private fun MdTableRow(
    cells: List<List<MdRun>>,
    widths: List<Int>,
    style: TextStyle,
    alignments: List<MdAlign>,
    paddingPx: Int,
    lineColor: Color,
    background: Color?,
    drawBottom: Boolean,
) {
    val density = LocalDensity.current
    val cellPadding = with(density) { paddingPx.toDp() }
    val rowWidth = with(density) { widths.sum().toDp() }
    Row(
        modifier = Modifier
            .width(rowWidth)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .drawBehind {
                val stroke = TableLineWidth.toPx()
                var boundary = 0f
                for (index in 0 until widths.lastIndex) {
                    boundary += widths[index]
                    drawLine(lineColor, Offset(boundary, 0f), Offset(boundary, size.height), strokeWidth = stroke)
                }
                if (drawBottom) {
                    drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = stroke)
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        cells.forEachIndexed { index, runs ->
            Box(
                modifier = Modifier
                    .width(with(density) { widths[index].toDp() })
                    .padding(horizontal = cellPadding, vertical = 6.dp),
            ) {
                MdRichText(
                    runs = runs,
                    style = style,
                    textAlign = (alignments.getOrNull(index) ?: MdAlign.START).toTextAlign(),
                )
            }
        }
    }
}
