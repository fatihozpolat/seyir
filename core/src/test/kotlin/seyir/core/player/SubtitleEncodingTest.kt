package seyir.core.player

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubtitleEncodingTest {
    private val sample = "Çok güzel bir şarkı, ığdır İstanbul ĞÜŞÖÇ"
    private val cp1254 = charset("windows-1254")

    @Test
    fun `plain ascii stays untouched`() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()
        assertEquals(Charsets.UTF_8, SubtitleEncoding.detect(bytes))
        assertSame(bytes, SubtitleEncoding.toUtf8(bytes))
    }

    @Test
    fun `valid utf8 stays untouched`() {
        val bytes = sample.toByteArray(Charsets.UTF_8)
        assertTrue(SubtitleEncoding.isValidUtf8(bytes))
        assertSame(bytes, SubtitleEncoding.toUtf8(bytes))
        assertEquals(sample, SubtitleEncoding.decode(bytes))
    }

    @Test
    fun `utf8 with bom stays untouched and decode drops the bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray(Charsets.UTF_8)
        assertSame(bytes, SubtitleEncoding.toUtf8(bytes))
        assertEquals(sample, SubtitleEncoding.decode(bytes))
    }

    @Test
    fun `windows 1254 is decoded and reencoded as utf8`() {
        val bytes = sample.toByteArray(cp1254)
        assertFalse(SubtitleEncoding.isValidUtf8(bytes))
        assertEquals(cp1254, SubtitleEncoding.detect(bytes))
        assertEquals(sample, SubtitleEncoding.decode(bytes))
        assertContentEquals(sample.toByteArray(Charsets.UTF_8), SubtitleEncoding.toUtf8(bytes))
    }

    @Test
    fun `iso 8859 9 turkish letters decode the same way`() {
        val bytes = sample.toByteArray(charset("ISO-8859-9"))
        assertEquals(sample, SubtitleEncoding.decode(bytes))
    }

    @Test
    fun `utf16 with bom is converted`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)
        assertEquals(sample, SubtitleEncoding.decode(le))
        assertEquals(sample, SubtitleEncoding.decode(be))
        assertContentEquals(sample.toByteArray(Charsets.UTF_8), SubtitleEncoding.toUtf8(le))
    }

    @Test
    fun `empty input is valid utf8`() {
        assertEquals("", SubtitleEncoding.decode(ByteArray(0)))
    }
}
