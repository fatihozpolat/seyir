package seyir.core

import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotatingLogStreamTest {
    private val dir = Files.createTempDirectory("logs").toFile().apply { deleteOnExit() }
    private val previousLevel = SeyirLog.minLevel

    @AfterTest
    fun restore() {
        SeyirLog.minLevel = previousLevel
    }

    @Test
    fun rotatesWhenLimitIsExceededAndKeepsOneOldFile() {
        val log = File(dir, "seyir.log")
        val stream = RotatingLogStream(log, maxBytes = 100)
        repeat(10) { stream.write("a".repeat(30).toByteArray()) }
        stream.close()
        val rotated = File(dir, "seyir.1.log")
        assertTrue(rotated.exists())
        assertTrue(log.length() <= 100 && rotated.length() <= 100)
        assertEquals(setOf("seyir.log", "seyir.1.log"), dir.list()!!.toSet())
    }

    @Test
    fun oversizedFileFromPreviousRunIsRotatedAtStart() {
        val log = File(dir, "old.log").apply { writeText("z".repeat(500)) }
        RotatingLogStream(log, maxBytes = 100).close()
        assertEquals(500, File(dir, "old.1.log").length())
        assertEquals(0, log.length())
    }

    @Test
    fun julRecordsAreRoutedThroughSeyirLog() {
        val originalErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            SeyirLog.minLevel = 4
            SeyirLog.installJulBridge()
            Logger.getLogger("ornek.kutuphane").log(Level.INFO, "merhaba {0}", "dünya")
            // OkHttp/JDK bilgi kayıtları info seviyesinde görünmez, debug'a düşer
            Logger.getLogger("okhttp3.OkHttpClient").log(Level.INFO, "gürültü")
            Logger.getLogger("okhttp3.OkHttpClient").log(Level.WARNING, "uyarı")
        } finally {
            System.setErr(originalErr)
        }
        val text = captured.toString(Charsets.UTF_8)
        assertTrue("I/kutuphane: merhaba dünya" in text, text)
        assertFalse("gürültü" in text, text)
        assertTrue("W/OkHttpClient: uyarı" in text, text)
    }
}
