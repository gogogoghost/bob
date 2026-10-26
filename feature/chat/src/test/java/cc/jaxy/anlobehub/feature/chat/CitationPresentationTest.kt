package cc.jaxy.anlobehub.feature.chat

import cc.jaxy.anlobehub.core.data.chat.WebCitation
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
        assertEquals("🌍世界 [1](<$url>) has facts.", result.markdown)
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
        assertEquals("One [1](<$url>) and two [1](<$url>)", result.markdown)
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
        assertEquals("Fact [1](<$url>) [2](<$other>)", result.markdown)
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
        assertEquals("$body [1](<$url>)", result.markdown)
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
}
