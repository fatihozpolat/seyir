package seyir.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlPolicyTest {
    @Test
    fun `https her yerde kabul edilir`() {
        assertTrue(UrlPolicy.isSecureRemote("https://example.com/repo.json"))
        assertTrue(UrlPolicy.isAllowed("HTTPS://Example.com/x.jar"))
    }

    @Test
    fun `http yalniz localhost icin kabul edilir`() {
        assertTrue(UrlPolicy.isSecureRemote("http://localhost:8080/plugins.json"))
        assertTrue(UrlPolicy.isSecureRemote("http://127.0.0.1/x.jar"))
        assertTrue(UrlPolicy.isSecureRemote("http://[::1]:9000/x.jar"))
        assertFalse(UrlPolicy.isAllowed("http://example.com/plugins.json"))
        assertFalse(UrlPolicy.isAllowed("http://192.0.2.10/plugins.json"))
    }

    @Test
    fun `localhost gorunumlu aldatmacalar reddedilir`() {
        assertFalse(UrlPolicy.isAllowed("http://localhost.example.com/x.jar"))
        assertFalse(UrlPolicy.isAllowed("http://localhost@example.com/x.jar"))
        assertFalse(UrlPolicy.isAllowed("http://example.com#localhost"))
    }

    @Test
    fun `baska semalar reddedilir`() {
        assertFalse(UrlPolicy.isAllowed("ftp://example.com/x.jar"))
        assertFalse(UrlPolicy.isAllowed("javascript://example.com"))
    }

    @Test
    fun `yerel dosya yollari kabul edilir`() {
        assertTrue(UrlPolicy.isAllowed("C:\\depo\\plugins.json"))
        assertTrue(UrlPolicy.isAllowed("/home/kullanici/plugins.json"))
        assertTrue(UrlPolicy.isAllowed("file:///tmp/plugins.json"))
        assertTrue(UrlPolicy.isAllowed("plugins.json"))
        assertTrue(UrlPolicy.isLocalPath("C:\\depo\\plugins.json"))
        assertFalse(UrlPolicy.isLocalPath("https://example.com/x"))
    }

    @Test
    fun `require ilkeye uymayan adreste firlatir`() {
        assertFailsWith<InsecureUrlException> { UrlPolicy.require("http://example.com/x.jar") }
        UrlPolicy.require("https://example.com/x.jar")
    }

    @Test
    fun `resolve uzak tabana gore cozer`() {
        assertEquals("https://example.com/a/b.jar", UrlPolicy.resolve("https://example.com/a/plugins.json", "b.jar"))
        assertEquals("https://other.example/x.jar", UrlPolicy.resolve("https://example.com/a/plugins.json", "https://other.example/x.jar"))
    }

    @Test
    fun `resolve yonlendirmeyle http adrese dusmeyi yakalatir`() {
        val next = UrlPolicy.resolve("https://example.com/x.jar", "http://example.com/y.jar")
        assertFalse(UrlPolicy.isAllowed(next))
    }

    @Test
    fun `uzak depo yerel dosya adresi gosteremez`() {
        assertFailsWith<InsecureUrlException> { UrlPolicy.resolve("https://example.com/plugins.json", "file:///etc/passwd") }
    }

    @Test
    fun `resolve yerel tabana gore dosya yolu verir`() {
        val resolved = UrlPolicy.resolve(java.io.File(System.getProperty("java.io.tmpdir"), "repo/plugins.json").path, "A.jar")
        assertEquals(java.io.File(System.getProperty("java.io.tmpdir"), "repo/A.jar").absoluteFile, java.io.File(resolved).absoluteFile)
    }

    @Test
    fun `alt cizgili sunucu adlari okunur`() {
        assertEquals("my_host.example.com", UrlPolicy.hostOf("https://my_host.example.com/plugins.json"))
        assertTrue(UrlPolicy.isSecureRemote("https://my_host.example.com/plugins.json"))
        assertTrue(UrlPolicy.isAllowed("https://cdn_1.example.com/x.jar"))
        // Alt çizgi, localhost aldatmacasını da kabul ettirmez
        assertFalse(UrlPolicy.isAllowed("http://local_host/x.jar"))
        assertEquals(null, UrlPolicy.hostOf("https:///yol"))
    }

    @Test
    fun `ag paylasimlari yerel sayilmaz ve reddedilir`() {
        assertFalse(UrlPolicy.isLocalPath("\\\\sunucu\\paylasim\\plugins.json"))
        assertFalse(UrlPolicy.isAllowed("\\\\sunucu\\paylasim\\plugins.json"))
        assertFalse(UrlPolicy.isAllowed("//sunucu/paylasim/plugins.json"))
        assertFalse(UrlPolicy.isAllowed("file://sunucu/paylasim/plugins.json"))
        assertFalse(UrlPolicy.isAllowed("file:////sunucu/paylasim/plugins.json"))
        assertTrue(UrlPolicy.isAllowed("file://localhost/tmp/plugins.json"))
        assertTrue(UrlPolicy.isAllowed("file:///C:/depo/plugins.json"))
        assertFailsWith<InsecureUrlException> { UrlPolicy.require("\\\\sunucu\\paylasim\\plugins.json") }
    }
}
