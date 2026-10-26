package cc.jaxy.bob.core.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearxngWebSearchTest {
    @Test
    fun `instance roots and prefixed search endpoints normalize without losing deployment path`() {
        assertEquals("", normalizeSearxngUrl("  "))
        assertEquals("https://search.example/search", normalizeSearxngUrl(" https://search.example/ "))
        assertEquals("http://localhost:8080/private/searx/search", normalizeSearxngUrl("http://localhost:8080/private/searx/"))
        assertEquals("https://search.example/prefix/search", normalizeSearxngUrl("https://search.example/prefix/search/"))
        assertEquals("https://search.example/prefix/search", normalizeSearxngUrl("https://search.example/prefix/search"))
        for (url in listOf("search.example", "ftp://search.example", "file:///search", "https://user:secret@search.example", "https://user@search.example", "https://search.example/?q=private", "https://search.example/?", "https://search.example/#", "https://search.example/#fragment")) {
            assertTrue("Unexpectedly accepted $url", runCatching { normalizeSearxngUrl(url) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
