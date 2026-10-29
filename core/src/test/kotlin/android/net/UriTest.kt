package android.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UriTest {
    @Test
    fun parsesParts() {
        val uri = Uri.parse("https://example.com:8443/a/b/c.m3u8?x=1&y=%C3%A7&x=2#frag")
        assertEquals("https", uri.getScheme())
        assertEquals("example.com", uri.getHost())
        assertEquals(8443, uri.getPort())
        assertEquals(listOf("a", "b", "c.m3u8"), uri.getPathSegments())
        assertEquals("c.m3u8", uri.getLastPathSegment())
        assertEquals("1", uri.getQueryParameter("x"))
        assertEquals(listOf("1", "2"), uri.getQueryParameters("x"))
        assertEquals("ç", uri.getQueryParameter("y"))
        assertEquals("frag", uri.getFragment())
        assertEquals("https://example.com:8443/a/b/c.m3u8?x=1&y=%C3%A7&x=2#frag", uri.toString())
    }

    @Test
    fun invalidAddressDoesNotThrow() {
        val uri = Uri.parse("ht tp://bozuk adres")
        assertNull(uri.getHost())
        assertEquals("ht tp://bozuk adres", uri.toString())
    }

    @Test
    fun encodesLikeAndroid() {
        assertEquals("a%20b%26c", Uri.encode("a b&c"))
        assertEquals("a b&c", Uri.decode("a%20b%26c"))
    }
}
