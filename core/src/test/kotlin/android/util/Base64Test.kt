package android.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base64Test {
    private val sample = ByteArray(100) { it.toByte() }

    @Test
    fun defaultEncodingWrapsAt76AndEndsWithNewline() {
        val encoded = Base64.encodeToString(sample, Base64.DEFAULT)
        val lines = encoded.split("\n")
        assertEquals(76, lines[0].length)
        assertEquals("", lines.last())
    }

    @Test
    fun noWrapMatchesJdk() {
        assertEquals(java.util.Base64.getEncoder().encodeToString(sample), Base64.encodeToString(sample, Base64.NO_WRAP))
    }

    @Test
    fun urlSafeWithoutPadding() {
        val bytes = byteArrayOf(-5, -1, -2)
        assertEquals("-__-", Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP))
        assertEquals("aGk", Base64.encodeToString("hi".toByteArray(), Base64.NO_PADDING or Base64.NO_WRAP))
    }

    @Test
    fun decodeIsLenientLikeAndroid() {
        val wrapped = Base64.encodeToString(sample, Base64.DEFAULT)
        assertContentEquals(sample, Base64.decode(wrapped, Base64.DEFAULT))
        // eksik dolgu ve url-safe alfabe kabul edilir
        assertContentEquals("hi".toByteArray(), Base64.decode("aGk", Base64.DEFAULT))
        assertContentEquals(byteArrayOf(-5, -1, -2), Base64.decode("-__-", Base64.DEFAULT))
    }

    @Test
    fun decodeRejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { Base64.decode("a", Base64.DEFAULT) }
    }
}
