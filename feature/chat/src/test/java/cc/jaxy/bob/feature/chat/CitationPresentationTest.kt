package cc.jaxy.bob.feature.chat

import cc.jaxy.bob.core.data.chat.WebCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationPresentationTest {
    private val url = "https://example.com/source?q=one&lang=zh"

    @Test
    fun `UTF-16 offsets preserve emoji and Chinese with clickable source markers`() {
        val body = "🌍世界 has facts."
        val result = presentCitations(body, listOf(WebCitation(url, "来源", 2, 4)))
        assertEquals("🌍世界 [🔗](<$url>) has facts.", result.markdown)
        assertEquals("来源", result.sources.single().title)
        assertEquals("🌍世界 has facts.", body)
    }

    @Test
    fun `invalid ranges retain source cards without guessing a location`() {
        val body = "🌍 facts"
        val ranges = listOf(-1 to 2, 0 to 99, 3 to 3, 4 to 2, 0 to 1, 1 to 2)
        ranges.forEach { (start, end) ->
            val result = presentCitations(body, listOf(WebCitation(url, startIndex = start, endIndex = end)))
            assertEquals(body, result.markdown)
            assertEquals(url, result.sources.single().url)
        }
        assertEquals(body, presentCitations(body, listOf(WebCitation(url))).markdown)
    }

    @Test
    fun `duplicate snapshots dedupe cards and markers but keep distinct occurrences`() {
        val body = "One and two"
        val first = WebCitation(url, "Source", 0, 3)
        val second = first.copy(startIndex = 8, endIndex = 11)
        val result = presentCitations(body, listOf(first, first, second, second.copy(startIndex = null)))
        assertEquals("One [🔗](<$url>) and two [🔗](<$url>)", result.markdown)
        assertEquals(1, result.sources.size)
    }

    @Test
    fun `later provider titles enrich a source without duplicating it`() {
        val result = presentCitations("Fact", listOf(WebCitation(url), WebCitation(url, "Actual provider title")))
        assertEquals("Actual provider title", result.sources.single().title)
        assertEquals("1", result.sources.single().index)
    }

    @Test
    fun `legacy footnotes remain links and reject non-web schemes`() {
        val markdown = "A claim[^1].\n\n[^1]: [Old source]($url)\n[^2]: javascript:alert(1)"
        val legacy = parseCitations(markdown)
        assertEquals(listOf(url), legacy.map { it.url })
        assertEquals("Old source", legacy.single().title)
        assertEquals(markdown, presentCitations(markdown, emptyList()).markdown)
    }

    @Test
    fun `multiple sources at one location are ordered and deduplicated`() {
        val other = "https://other.example/source"
        val result = presentCitations("Fact", listOf(WebCitation(url, "First", 0, 4), WebCitation(other, "Second", 0, 4)))
        assertEquals("Fact [🔗](<$url>) [🔗](<$other>)", result.markdown)
        assertEquals(listOf(url, other), result.sources.map { it.url })
    }

    @Test
    fun `code links HTML and uncertain delimiters are never modified`() {
        val bodies = listOf(
            "```kotlin\nval answer = 42\n```",
            "~~~text\nanswer\n~~~",
            "> ~~~text\n> answer\n> ~~~",
            "- ~~~text\n  answer\n  ~~~",
            "    answer",
            "Use `answer` here",
            "Use ``answer ` code`` here",
            "[answer](https://example.org)",
            "![answer](https://example.org/image.png)",
            "<a href=\"https://example.org\">answer</a>",
            "**answer**",
            "[answer]: https://example.org",
        )
        bodies.forEach { body ->
            val start = body.indexOf("answer").let { if (it < 0) body.indexOf("val") else it }
            val end = start + if (body.substring(start).startsWith("answer")) 6 else 3
            val result = presentCitations(body, listOf(WebCitation(url, startIndex = start, endIndex = end)))
            assertEquals(body, result.markdown)
            assertEquals(1, result.sources.size)
        }
    }

    @Test
    fun `plain prose after Markdown code keeps its native citation`() {
        val body = "```\ncode\n```\nActual fact."
        val result = presentCitations(body, listOf(WebCitation(url, startIndex = body.indexOf("Actual"), endIndex = body.length)))
        assertEquals("$body [🔗](<$url>)", result.markdown)
    }

    @Test
    fun `unsafe schemes malformed URLs and credentials are not clickable sources`() {
        val urls = listOf("javascript:alert(1)", "file:///etc/passwd", "intent://example", "data:text/html,hi", "httpx://example.com", "https://", "https://user:pass@example.com", "https://example.com/\nattack")
        urls.forEach { unsafe ->
            assertFalse(isSafeWebUrl(unsafe))
            val result = presentCitations("Fact", listOf(WebCitation(unsafe, startIndex = 0, endIndex = 4)))
            assertEquals("Fact", result.markdown)
            assertTrue(result.sources.isEmpty())
        }
        assertTrue(isSafeWebUrl(url))
        assertTrue(isSafeWebUrl("HTTP://example.com/path"))
    }
    @Test
    fun `only the bare link emoji survives after a citation link`() {
        val first = "https://first.example/a"
        val citations = listOf(WebCitation(first, "First"))
        val cases = mapOf(
            "A[citation:1]来源2." to "A[🔗](<$first>).",
            "A[citation:1] 来源2." to "A[🔗](<$first>) .",
            "A[citation:1]source 2." to "A[🔗](<$first>).",
            "A[citation:1][2](next)." to "A[🔗](<$first>)(next).",
            "A[🔗](<$first>)来源2." to "A[🔗](<$first>).",
            "A[🔗](<$first>) 2 people." to "A[🔗](<$first>) 2 people.",
        )
        for ((body, expected) in cases) {
            assertEquals(expected, presentCitations(body, citations).markdown)
        }
    }
    @Test
    fun `uncited search hits do not leave numbering gaps in source cards`() {
        val first = "https://first.example/a"
        val skipped = "https://skipped.example/b"
        val third = "https://third.example/c"
        val result = presentCitations(
            "See [🔗]($first) and [🔗]($third).",
            listOf(WebCitation(first, "First"), WebCitation(skipped, "Skipped"), WebCitation(third, "Third")),
        )
        assertEquals(listOf(first, third), result.usedSources.map { it.url })
        assertEquals(listOf("1", "2"), result.usedSources.map { it.index })
    }

    @Test
    fun `source cards are ordered by their displayed numeric index`() {
        val citations = listOf(
            ChatCitation("3", "Third", "https://third.example"),
            ChatCitation("", "Unnumbered", "https://unnumbered.example"),
            ChatCitation("1", "First", "https://first.example"),
            ChatCitation("2", "Second", "https://second.example"),
        )
        assertEquals(listOf("1", "2", "3", ""), orderCitations(citations).map { it.index })
    }

    @Test
    fun `bare emoji urls fold only for known sources`() {
        val first = "https://first.example/a"
        val citations = listOf(WebCitation(first, "First"))
        // Known source: folds into one link, no raw URL text remains.
        val folded = presentCitations("See 🔗https://first.example/a here.", citations)
        assertEquals("See [🔗](<$first>) here.", folded.markdown)
        assertEquals(listOf(first), folded.usedSources.map { it.url })
        // Unknown URL: stays visible text, never becomes a clickable link.
        val exotic = presentCitations("See 🔗https://evil.example/x here.", citations)
        assertTrue("🔗https://evil.example/x" in exotic.markdown)
        assertTrue(exotic.usedSources.isEmpty())
        // Folded link with a numbering tail collapses to the bare emoji.
        val tailed = presentCitations("See 🔗https://first.example/a来源2 end.", citations)
        assertEquals("See [🔗](<$first>) end.", tailed.markdown)
    }
}
