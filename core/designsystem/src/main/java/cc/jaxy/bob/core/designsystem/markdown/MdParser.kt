package cc.jaxy.bob.core.designsystem.markdown

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** Block-level layout model. Parsed once (background), rendered synchronously. */
sealed interface MdBlock {
    data class Paragraph(val runs: List<MdRun>) : MdBlock
    data class Heading(val level: Int, val runs: List<MdRun>) : MdBlock
    data class Code(val text: String, val language: String?) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Ordered(val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data class Unordered(val tight: Boolean, val items: List<List<MdBlock>>) : MdBlock
    data class Table(val headers: List<List<MdRun>>, val rows: List<List<List<MdRun>>>) : MdBlock
    data object Divider : MdBlock
}

/** Inline run with cumulative style flags. */
data class MdRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

private val PARSER: Parser by lazy {
    Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()
}

/** Synchronous parse (~1ms for typical chat messages; call off the UI thread for huge docs). */
fun parseMarkdown(source: String): List<MdBlock> {
    val doc = PARSER.parse(source) as Document
    return doc.children().mapNotNull { it.toBlock() }
}

private fun Node.children(): List<Node> {
    val out = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        out.add(child)
        child = child.next
    }
    return out
}

private fun Node.toBlock(): MdBlock? = when (this) {
    is Paragraph -> MdBlock.Paragraph(collectRuns())
    is Heading -> MdBlock.Heading(level, collectRuns())
    is FencedCodeBlock -> MdBlock.Code(literal?.trimEnd('\n') ?: "", info?.trim()?.takeIf { it.isNotBlank() })
    is IndentedCodeBlock -> MdBlock.Code(literal?.trimEnd('\n') ?: "", null)
    is BlockQuote -> MdBlock.Quote(children().mapNotNull { it.toBlock() })
    is BulletList -> MdBlock.Unordered(
        tight = isTight,
        items = children().filterIsInstance<ListItem>().map { item ->
            item.children().mapNotNull { it.toBlock() }
        },
    )
    is OrderedList -> MdBlock.Ordered(
        start = startNumber,
        items = children().filterIsInstance<ListItem>().map { item ->
            item.children().mapNotNull { it.toBlock() }
        },
    )
    is TableBlock -> {
        var headers: List<List<MdRun>> = emptyList()
        val rows = mutableListOf<List<List<MdRun>>>()
        children().forEach { section ->
            when (section) {
                is TableHead -> section.children().filterIsInstance<TableRow>().firstOrNull()?.let { row ->
                    headers = row.children().filterIsInstance<TableCell>().map { it.collectRuns() }
                }
                is TableBody -> section.children().filterIsInstance<TableRow>().forEach { row ->
                    rows.add(row.children().filterIsInstance<TableCell>().map { it.collectRuns() })
                }
                else -> Unit
            }
        }
        MdBlock.Table(headers, rows)
    }
    is ThematicBreak -> MdBlock.Divider
    is HtmlBlock -> MdBlock.Paragraph(listOf(MdRun(literal?.trim() ?: "")))
    else -> null
}

private fun Node.collectRuns(): List<MdRun> {
    val runs = mutableListOf<MdRun>()
    collectInto(
        runs = runs,
        bold = false,
        italic = false,
        strike = false,
        code = false,
        link = null,
    )
    return runs.mergeAdjacent()
}

private fun Node.collectInto(
    runs: MutableList<MdRun>,
    bold: Boolean,
    italic: Boolean,
    strike: Boolean,
    code: Boolean,
    link: String?,
) {
    when (this) {
        is Text -> runs.add(MdRun(literal, bold, italic, strike, code, link))
        is Code -> runs.add(MdRun(literal, bold, italic, strike, true, link))
        is StrongEmphasis -> children().forEach { it.collectInto(runs, true, italic, strike, code, link) }
        is Emphasis -> children().forEach { it.collectInto(runs, bold, true, strike, code, link) }
        is org.commonmark.ext.gfm.strikethrough.Strikethrough ->
            children().forEach { it.collectInto(runs, bold, italic, true, code, link) }
        is Link -> children().forEach {
            it.collectInto(runs, bold, italic, strike, code, link = destination)
        }
        is Image -> runs.add(MdRun(altText() ?: destination ?: "", bold, italic, strike, code, destination))
        is SoftLineBreak, is HardLineBreak -> runs.add(MdRun("\n", bold, italic, strike, code, link))
        is HtmlInline -> runs.add(MdRun(literal, bold, italic, strike, code, link))
        else -> children().forEach { it.collectInto(runs, bold, italic, strike, code, link) }
    }
}

private fun Node.altText(): String? {
    val sb = StringBuilder()
    var child = firstChild
    while (child != null) {
        if (child is Text) sb.append(child.literal)
        child = child.next
    }
    return sb.toString().takeIf { it.isNotBlank() }
}

/** Merges adjacent runs with identical style to keep Text composables small. */
private fun List<MdRun>.mergeAdjacent(): List<MdRun> {
    if (size < 2) return this
    val out = mutableListOf<MdRun>()
    for (run in this) {
        val last = out.lastOrNull()
        if (last != null && last.copy(text = "") == run.copy(text = "")) {
            out[out.size - 1] = last.copy(text = last.text + run.text)
        } else {
            out.add(run)
        }
    }
    return out
}
