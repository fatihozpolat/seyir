package seyir.core.player

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvLoadTest {
    @Test
    fun `plain values are left alone and others get the byte length prefix`() {
        assertEquals("10.000", MpvLoad.encodeValue("10.000"))
        assertEquals("http://127.0.0.1:8123/a/b.m3u8", MpvLoad.encodeValue("http://127.0.0.1:8123/a/b.m3u8"))
        assertEquals("%6%tr,tur", MpvLoad.encodeValue("tr,tur"))
        assertEquals("%3%a=b", MpvLoad.encodeValue("a=b"))
        assertEquals("%0%", MpvLoad.encodeValue(""))
        assertEquals("%3%a b", MpvLoad.encodeValue("a b"))
        assertEquals("%6%100%25", MpvLoad.encodeValue("100%25"))
    }

    @Test
    fun `length prefix counts utf-8 bytes not characters`() {
        // "ö" iki bayttır: "Bölüm" 5 karakter, 7 bayt
        assertEquals("%7%Bölüm", MpvLoad.encodeValue("Bölüm"))
        assertEquals("%4%\uD83C\uDFAC", MpvLoad.encodeValue("\uD83C\uDFAC")) // emoji: 4 bayt
    }

    @Test
    fun `option string survives commas equals signs quotes and percent signs`() {
        val title = "Dizi, \"x=y\" 100% [1] \\ Bölüm"
        val text = MpvLoad.optionString(listOf("start" to "5.000", "title" to title, "slang" to "tr,tur"))
        val bytes = title.toByteArray(Charsets.UTF_8).size
        assertEquals("start=5.000,title=%$bytes%$title,slang=%6%tr,tur", text)
        assertTrue(parseMpvKeyValueList(text) == listOf("start" to "5.000", "title" to title, "slang" to "tr,tur"))
    }

    @Test
    fun `window title doubles dollar signs but media title does not`() {
        val request = PlayRequest("http://h/x", "Dizi \${x} \$y", preferredSubtitleLang = null)
        val options = MpvLoad.options(request).toMap()
        assertEquals("Dizi \$\${x} \$\$y", options["title"])
        assertEquals("Dizi \${x} \$y", options["force-media-title"])
    }

    @Test
    fun `loadfile command has replace, minus one index and the option list`() {
        val request = PlayRequest(
            url = "http://127.0.0.1:9/a.m3u8?x=1,2",
            title = "Başlık",
            startSeconds = 12.3456,
            audioFiles = listOf("http://127.0.0.1:9/audio.m4a"),
            extraArgs = listOf("--hls-bitrate=2149000"),
        )
        val command = MpvLoad.command(request)
        assertEquals(listOf("loadfile", "http://127.0.0.1:9/a.m3u8?x=1,2", "replace", -1), command.take(4))
        val options = parseMpvKeyValueList(command[4] as String)
        assertEquals("12.346", options.toMap()["start"])
        assertEquals("Başlık", options.toMap()["title"])
        assertEquals("tr,tur", options.toMap()["slang"])
        assertEquals("cp1254", options.toMap()["sub-codepage"])
        assertEquals("auto", options.toMap()["edition"])
        assertEquals("http://127.0.0.1:9/audio.m4a", options.toMap()["audio-files-append"])
        assertEquals("2149000", options.toMap()["hls-bitrate"])
    }

    @Test
    fun `negative start is clamped and subtitle language can be absent`() {
        val options = MpvLoad.options(PlayRequest("http://h/x", "t", startSeconds = -4.0, preferredSubtitleLang = null)).toMap()
        assertEquals("0.000", options["start"])
        assertFalse("slang" in options)
        assertFalse("sub-codepage" in options)
    }

    @Test
    fun `only the quality option goes per file, other extra args stay process level`() {
        val (perFile, process) = MpvLoad.splitExtraArgs(listOf("--hls-bitrate=500000", "--mute=yes", "--vo=null"))
        assertEquals(listOf("hls-bitrate" to "500000"), perFile)
        assertEquals(listOf("--mute=yes", "--vo=null"), process)
        assertEquals("a" to "yes", MpvLoad.parseArg("--a"))
        assertEquals("a" to "no", MpvLoad.parseArg("--no-a"))
        assertEquals("a" to "b=c", MpvLoad.parseArg("--a=b=c"))
        assertNull(MpvLoad.parseArg("-x"))
        assertNull(MpvLoad.parseArg("--"))
    }

    @Test
    fun `host arguments start idle without a file and keep window and ui options`() {
        val ui = PlayerUi("C:/ui", "tr")
        val args = MpvLoad.hostArgs("pipe", ui, listOf("--mute=yes"), listOf("--geometry=800x450+10+20", "--fullscreen"))
        assertTrue("--idle=yes" in args)
        assertTrue("--input-ipc-server=pipe" in args)
        assertTrue("--geometry=800x450+10+20" in args)
        assertTrue("--fullscreen" in args)
        assertTrue("--config-dir=C:/ui" in args)
        assertTrue("--mute=yes" in args)
        assertTrue("--hwdec=auto-safe" in args)
        assertTrue(args.none { it == "--" || it.startsWith("--start") || it.startsWith("--force-media-title") })
        assertTrue(args.indexOf("--geometry=800x450+10+20") < args.indexOf("--mute=yes"))
    }

    @Test
    fun `reuse needs mpv 0_38 or newer`() {
        assertFalse(MpvLoad.supportsReuse(null))
        assertFalse(MpvLoad.supportsReuse(0 to 37))
        assertTrue(MpvLoad.supportsReuse(0 to 38))
        assertTrue(MpvLoad.supportsReuse(0 to 41))
        assertTrue(MpvLoad.supportsReuse(1 to 0))
    }

    @Test
    fun `legacy command line also receives the window arguments before extra args`() {
        val request = PlayRequest("http://h/x.m3u8", "t", extraArgs = listOf("--volume=50"))
        val args = MpvArgs.build(request, "p", listOf("--geometry=800x450"))
        assertTrue(args.indexOf("--geometry=800x450") < args.indexOf("--volume=50"))
        assertEquals(listOf("--", request.url), args.takeLast(2))
    }

    @Test
    fun `window state serializes through jackson`() {
        val mapper = jacksonObjectMapper()
        val state = PlayerWindowState(1000, 600, -210, 131, maximized = true)
        assertEquals(state, mapper.readValue(mapper.writeValueAsString(state), PlayerWindowState::class.java))
        assertEquals(PlayerWindowState(), mapper.readValue("{}", PlayerWindowState::class.java))
        // Hesaplanan özellikler dosyaya yazılmaz (okunurken bilinmeyen alan sayılırdı)
        assertFalse(mapper.writeValueAsString(state).contains("hasSize"))
        assertFalse(mapper.writeValueAsString(state).contains("meaningful", ignoreCase = true))
    }

    /** mpv'nin anahtar-değer listesi çözümlemesinin yeterli bir kopyası: `a=b,c=%n%değer`. */
    private fun parseMpvKeyValueList(text: String): List<Pair<String, String>> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val result = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < bytes.size) {
            val eq = bytes.indexOf('='.code.toByte(), i)
            val key = String(bytes, i, eq - i, Charsets.UTF_8)
            i = eq + 1
            val value: String
            if (i < bytes.size && bytes[i] == '%'.code.toByte()) {
                val close = bytes.indexOf('%'.code.toByte(), i + 1)
                val length = String(bytes, i + 1, close - i - 1).toInt()
                value = String(bytes, close + 1, length, Charsets.UTF_8)
                i = close + 1 + length
            } else {
                var end = i
                while (end < bytes.size && bytes[end] != ','.code.toByte()) end++
                value = String(bytes, i, end - i, Charsets.UTF_8)
                i = end
            }
            result += key to value
            if (i < bytes.size && bytes[i] == ','.code.toByte()) i++
        }
        return result
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }
}
