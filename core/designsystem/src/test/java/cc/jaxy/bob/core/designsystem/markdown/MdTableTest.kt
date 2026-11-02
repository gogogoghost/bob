package cc.jaxy.bob.core.designsystem.markdown

import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Test

class MdTableTest {
    @Test
    fun `GFM tables keep column alignment and cell content`() {
        val blocks = parseMarkdown(
            """
            | Name | Score | Notes |
            |:-----|------:|:-----:|
            | Ada  | 10    | ok    |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf(MdAlign.START, MdAlign.END, MdAlign.CENTER), table.alignments)
        assertEquals(listOf("Name", "Score", "Notes"), table.headers.map { it.text() })
        assertEquals(listOf(listOf("Ada", "10", "ok")), table.rows.map { row -> row.map { it.text() } })
    }

    @Test
    fun `tables without delimiter colons align every column to the start`() {
        val table = parseMarkdown(
            """
            | A | B |
            |---|---|
            | 1 | 2 |
            """.trimIndent(),
        ).single() as MdBlock.Table
        assertEquals(listOf(MdAlign.START, MdAlign.START), table.alignments)
    }

    @Test
    fun `alignment maps to text alignment`() {
        assertEquals(TextAlign.Start, MdAlign.START.toTextAlign())
        assertEquals(TextAlign.Center, MdAlign.CENTER.toTextAlign())
        assertEquals(TextAlign.End, MdAlign.END.toTextAlign())
    }

    @Test
    fun `column widths use the widest cell and clamp to bounds`() {
        assertEquals(listOf(100, 100), tableColumnWidths(listOf(listOf(40, 120), listOf(100, 20)), 60, 100))
        assertEquals(listOf(60, 80, 60), tableColumnWidths(listOf(listOf(1, 80), listOf(2, 3, 4)), 60, 100))
        assertEquals(emptyList<Int>(), tableColumnWidths(emptyList(), 60, 100))
        assertEquals(emptyList<Int>(), tableColumnWidths(listOf(emptyList()), 60, 100))
    }

    private fun List<MdRun>.text(): String = joinToString("") { it.text }.trim()
}
