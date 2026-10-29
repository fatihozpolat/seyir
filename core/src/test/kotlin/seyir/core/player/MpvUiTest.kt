package seyir.core.player

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvUiTest {
    private val request = PlayRequest("http://127.0.0.1:1/x.m3u8", "Dizi", extraArgs = listOf("--volume=50"))

    @Test
    fun `plain mpv is used when ui is off`() {
        val args = MpvArgs.build(request, "p")
        assertTrue(args.none { it.startsWith("--config-dir") || it.startsWith("--osc") || it.startsWith("--script-opts") })
    }

    @Test
    fun `ui adds config dir and disables the stock osc`() {
        val ui = PlayerUi("C:/Seyir/resources/mpv-ui", "tr")
        val args = MpvArgs.build(request.copy(ui = ui), "p")
        assertTrue("--config-dir=C:/Seyir/resources/mpv-ui" in args)
        assertTrue("--osc=no" in args)
        assertTrue("--osd-bar=no" in args)
        assertTrue("--script-opts-append=uosc-languages=tr,seyir-lang=tr" in args)
        // Ek seçenekler arayüz seçeneklerinden sonra gelir (ezebilir); adres her zaman sonda
        assertTrue(args.indexOf("--volume=50") > args.indexOf("--osc=no"))
        assertEquals(listOf("--", request.url), args.takeLast(2))
    }

    @Test
    fun `language is passed to uosc and the script`() {
        val en = MpvArgs.uiArgs(PlayerUi("d", "en"))
        assertTrue("--script-opts-append=uosc-languages=en,seyir-lang=en" in en)
        // Bilinmeyen dil Türkçeye düşer
        val other = MpvArgs.uiArgs(PlayerUi("d", "de"))
        assertTrue("--script-opts-append=uosc-languages=tr,seyir-lang=tr" in other)
    }

    @Test
    fun `thumbnail mpv path is passed unless it would break the option list`() {
        val ok = MpvUi.scriptOpts(PlayerUi("d", "tr", "/usr/local/bin/mpv"))
        assertTrue(ok.endsWith(",thumbfast-mpv_path=/usr/local/bin/mpv"))
        val comma = MpvUi.scriptOpts(PlayerUi("d", "tr", "/opt/a,b/mpv"))
        assertFalse(comma.contains("thumbfast-mpv_path"))
    }

    @Test
    fun `mpv version is parsed from the first line`() {
        assertEquals(0 to 41, MpvUi.parseVersion("mpv v0.41.0-1227-ge471496c5 Copyright © 2000-2026\n built on"))
        assertEquals(0 to 37, MpvUi.parseVersion("mpv 0.37.0 Copyright © 2000-2023 mpv/MPlayer/mplayer2 projects"))
        assertNull(MpvUi.parseVersion("bir şey"))
        assertNull(MpvUi.parseVersion(""))
    }

    @Test
    fun `ui needs mpv 0_38 or newer`() {
        assertFalse(MpvUi.isSupported(null))
        assertFalse(MpvUi.isSupported(0 to 37))
        assertTrue(MpvUi.isSupported(0 to 38))
        assertTrue(MpvUi.isSupported(0 to 41))
        assertTrue(MpvUi.isSupported(1 to 0))
    }

    @Test
    fun `ui folder is found in the resources dir or the repository`() {
        val root = Files.createTempDirectory("seyir-ui").toFile()
        try {
            assertNull(MpvUi.locate(resourcesDir = File(root, "none").path, workingDir = root.path))
            // Pakette: <kaynak>/mpv-ui
            File(root, "res/mpv-ui/scripts").mkdirs()
            assertNull(MpvUi.locate(File(root, "res").path, null)) // seyir.lua yok
            File(root, "res/mpv-ui/scripts/seyir.lua").writeText("-- x")
            assertEquals(File(root, "res/mpv-ui"), MpvUi.locate(File(root, "res").path, null))
            // Geliştirmede: depo kökünden
            File(root, "repo/app/resources/common/mpv-ui/scripts").mkdirs()
            File(root, "repo/app/resources/common/mpv-ui/scripts/seyir.lua").writeText("-- x")
            assertEquals(File(root, "repo/app/resources/common/mpv-ui"), MpvUi.locate(null, File(root, "repo").path))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shipped ui folder is complete`() {
        // Gradle testleri modül klasöründen çalışır; depodaki gömülü arayüz paketi
        val dir = listOf(File("../app/resources/common/mpv-ui"), File("app/resources/common/mpv-ui")).firstOrNull { it.isDirectory }
        assertTrue(dir != null, "app/resources/common/mpv-ui bulunamadı")
        for (path in listOf(
            "scripts/uosc/main.lua", "scripts/uosc/intl/tr.json", "scripts/thumbfast.lua", "scripts/seyir.lua",
            "script-opts/uosc.conf", "script-opts/thumbfast.conf", "fonts/uosc_icons.otf", "input.conf",
        )) {
            assertTrue(File(dir, path).isFile, "$path eksik")
        }
        assertTrue(File(dir, "script-opts/thumbfast.conf").readText().lines().any { it.trim() == "network=yes" })
        // scripts/ altındaki her dosya mpv tarafından betik olarak yüklenir; yalnızca .lua ve betik klasörleri olmalı
        val strays = File(dir, "scripts").listFiles().orEmpty().filter { it.isFile && it.extension != "lua" }
        assertTrue(strays.isEmpty(), "scripts/ içinde betik olmayan dosya var: $strays")
    }

    @Test
    fun `script messages are parsed from client-message events`() {
        fun event(json: String) = MpvMessageParser.parse(json) as MpvEvent
        assertEquals(
            SeyirScript.Message.NextRequested,
            SeyirScript.parse(event("""{"event":"client-message","args":["seyir-next"]}""")),
        )
        assertEquals(
            SeyirScript.Message.PreviousRequested,
            SeyirScript.parse(event("""{"event":"client-message","args":["seyir-prev"]}""")),
        )
        assertNull(SeyirScript.parse(event("""{"event":"client-message","args":["baska-ileti"]}""")))
        assertNull(SeyirScript.parse(event("""{"event":"client-message","args":[]}""")))
        assertNull(SeyirScript.parse(event("""{"event":"client-message"}""")))
        assertNull(SeyirScript.parse(event("""{"event":"file-loaded"}""")))
    }

    @Test
    fun `next episode info command carries the title as a single argument`() {
        assertEquals(
            listOf("script-message-to", "seyir", "seyir-next-info", "S1 B2 — Ad, soyad"),
            SeyirScript.nextInfoCommand("S1 B2 — Ad, soyad"),
        )
        assertEquals(listOf("script-message-to", "seyir", "seyir-next-info", ""), SeyirScript.nextInfoCommand(null))
        assertEquals(listOf("script-message-to", "seyir", "seyir-prev-info", "S1 B1"), SeyirScript.previousInfoCommand("S1 B1"))
    }
}
