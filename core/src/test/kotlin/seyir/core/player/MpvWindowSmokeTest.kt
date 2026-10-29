package seyir.core.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gerçek mpv ile pencere yeniden kullanımı ve pencere durumunun saklanıp geri yüklenmesi denemesi (Windows).
 * Kendi açtığı mpv pencerelerini kullanır; ağ gerektirir (Mux ve Apple test akışları), bu yüzden yalnızca
 * `SEYIR_MANUAL=1` ile çalışır. Rapor `build/mpv-window-smoke-report.txt` dosyasına da yazılır.
 *
 * Çalıştırma: `$env:SEYIR_MANUAL=1; .\gradlew.bat :core:test --tests '*MpvWindowSmokeTest*' --console=plain -i`
 */
@Tag("manual")
class MpvWindowSmokeTest {
    private val mux = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    private val bipbop = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
    private fun lavfi(size: String) = "av://lavfi:testsrc=size=$size:rate=25:duration=600"

    private val report = CopyOnWriteArrayList<String>()
    private fun say(line: String) {
        report += line
        println("RAPOR: $line")
    }

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetDpiForSystem(): Int
        fun GetWindowTextW(hwnd: Pointer, text: CharArray, max: Int): Int
    }

    private class Rec : PlaybackListener {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)

        @Volatile var summary: PlaybackSummary? = null
        val windowStates = CopyOnWriteArrayList<Pair<PlayerWindowState, Boolean>>()
        override fun onStarted() = started.countDown()
        override fun onFinished(summary: PlaybackSummary) {
            this.summary = summary
            finished.countDown()
        }

        override fun onWindowState(state: PlayerWindowState, final: Boolean) {
            windowStates += state to final
        }
    }

    private fun command(): MpvCommand {
        assumeTrue(System.getenv("SEYIR_MANUAL") == "1", "elle çalıştırılan test (SEYIR_MANUAL=1)")
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows denemesi")
        val bundled = listOf("../app/resources/windows/mpv/mpv.exe", "app/resources/windows/mpv/mpv.exe")
            .map(::File).firstOrNull { it.isFile }?.absolutePath
        val command = MpvLocator.find(System.getenv("SEYIR_MPV") ?: bundled)
        assumeTrue(command != null, "mpv bulunamadı")
        return command!!
    }

    private fun ui(command: MpvCommand): PlayerUi {
        val dir = MpvUi.locate(workingDir = File("..").absoluteFile.normalize().path)
        assumeTrue(dir != null, "arayüz klasörü bulunamadı")
        assumeTrue(MpvUi.commandSupportsUi(command), "mpv arayüz için eski")
        return PlayerUi(dir!!.absolutePath, "tr")
    }

    /** Oturumun mpv'sine ikinci bir IPC bağlantısı açıp özellik okur (Windows borusu birden çok istemciyi kabul eder). */
    private fun query(session: PlaybackSession, vararg names: String): List<String?> {
        val endpoint = assertNotNull((session as MpvSession).ipcEndpoint)
        MpvIpcClient(MpvIpcTransports.open(endpoint, true)).use { client ->
            return names.map { client.getProperty(it)?.let { node -> if (node.isTextual) node.asText() else node.toString() } }
        }
    }

    private fun command(session: PlaybackSession, vararg args: Any) {
        val endpoint = assertNotNull((session as MpvSession).ipcEndpoint)
        MpvIpcClient(MpvIpcTransports.open(endpoint, true)).use { it.request(args.toList()) }
    }

    private fun waitFor(what: String, seconds: Long = 60, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "$what zamanında olmadı")
            Thread.sleep(100)
        }
    }

    /** Pencerenin işletim sistemindeki GERÇEK başlığı (mpv `title` açılımından sonra). */
    private fun windowTitleOf(session: PlaybackSession): String {
        val user32 = Native.load("user32", User32::class.java)
        val hwnd = Pointer.createConstant(query(session, "window-id")[0]!!.toLong())
        val buffer = CharArray(512)
        val length = user32.GetWindowTextW(hwnd, buffer, buffer.size)
        return String(buffer, 0, length)
    }

    private fun pidOf(session: PlaybackSession): Long? = (session as MpvSession).processId

    private fun frameOf(session: PlaybackSession, probe: WindowProbe): WindowFrame {
        val id = query(session, "window-id")[0]!!.toLong()
        return assertNotNull(probe.frame(id), "pencere çerçevesi okunamadı")
    }

    @Test
    fun `episodes load into the same mpv process and window with the Seyir interface`() {
        val command = command()
        val ui = ui(command)
        val probe = WindowProbe.platform()
        val player = MpvPlayer(command)
        val a = Rec()
        val b = Rec()
        val c = Rec()
        val d = Rec()
        val extra = listOf("--ao=null")
        try {
            val sessionA = player.play(
                PlayRequest(mux, "Bölüm 1, \"x=y\" 100%", startSeconds = 30.0, ui = ui, extraArgs = extra, window = PlayerWindowState(900, 506, 300, 200)),
                a,
            )
            assertTrue(a.started.await(60, TimeUnit.SECONDS), "A başlamadı")
            sessionA.setNextEpisode("S1 B2 — Sıradaki, bölüm")
            waitFor("A düğme bilgisi", 20) { query(sessionA, "user-data/seyir/next-title")[0] == "S1 B2 — Sıradaki, bölüm" }
            val pidA = pidOf(sessionA)
            val infoA = query(sessionA, "window-id", "title", "media-title", "time-pos", "user-data/seyir/next-title")
            say("A: pid=$pidA pencere=${infoA[0]} başlık='${infoA[1]}' medya='${infoA[2]}' konum=${infoA[3]} sonraki='${infoA[4]}'")
            val windowTitleOfA = windowTitleOf(sessionA)
            assertEquals("Bölüm 1, \"x=y\" 100%", windowTitleOfA, "pencere başlığı")
            val frameA = frameOf(sessionA, probe)
            say("A: çerçeve=$frameA")

            // B: aynı süreç, aynı pencere; başlık kalite seçeneği ve özel karakterlerle
            val sessionB = player.play(
                PlayRequest(bipbop, "Bölüm 2, a=b \$HOME", startSeconds = 8.0, ui = ui, extraArgs = extra + "--hls-bitrate=1000000"),
                b,
            )
            assertTrue(b.started.await(60, TimeUnit.SECONDS), "B başlamadı")
            val pidB = pidOf(sessionB)
            val infoB = query(sessionB, "window-id", "title", "media-title", "time-pos", "user-data/seyir/next-title", "user-data/seyir/previous-title")
            say("B: pid=$pidB pencere=${infoB[0]} başlık='${infoB[1]}' medya='${infoB[2]}' konum=${infoB[3]} sonraki='${infoB[4]}' önceki='${infoB[5]}'")
            say("B: A dinleyicisine bitiş: replaced=${a.summary?.replaced} userStopped=${a.summary?.userStopped} startFailed=${a.summary?.startFailed} naturalEnd=${a.summary?.naturalEnd} error=${a.summary?.error}")
            say("B: çerçeve=${frameOf(sessionB, probe)}")
            say("A: gerçek pencere başlığı='${windowTitleOfA}' B: gerçek pencere başlığı='${windowTitleOf(sessionB)}'")
            assertEquals("Bölüm 2, a=b \$HOME - ".removeSuffix(" - "), windowTitleOf(sessionB), "pencere başlığı")
            assertTrue(pidA == pidB, "süreç korunmalı")
            assertEquals(infoA[0], infoB[0], "pencere (HWND) korunmalı")
            assertNotEquals(infoA[1], infoB[1])
            assertEquals("Bölüm 2, a=b \$HOME", infoB[2])
            assertEquals("", infoB[4], "eski sonraki bölüm başlığı temizlenmeli")
            assertTrue((infoB[3]?.toDoubleOrNull() ?: 0.0) in 8.0..60.0, "B başlangıç konumu uygulanmalı: ${infoB[3]}")
            val summaryA = assertNotNull(a.summary)
            assertTrue(summaryA.replaced && summaryA.userStopped && !summaryA.startFailed && !summaryA.naturalEnd)

            // C: açılamayan dosya; yalnız C hata ile biter, pencere açık kalır
            val sessionC = player.play(PlayRequest("http://127.0.0.1:9/yok.m3u8", "Bozuk", ui = ui, extraArgs = extra), c)
            assertTrue(c.finished.await(60, TimeUnit.SECONDS), "C bitişi gelmedi")
            val summaryC = assertNotNull(c.summary)
            say("C: açılamayan dosya: endReason=${summaryC.endReason} fileError=${summaryC.fileError} startFailed=${summaryC.startFailed} pid=${pidOf(sessionC)}")
            assertTrue(summaryC.startFailed)
            assertTrue(pidA == pidOf(sessionC))
            assertTrue(b.summary?.replaced == true)

            // D: yedek kaynak aynı pencerede açılır
            val sessionD = player.play(PlayRequest(mux, "Yedek kaynak", startSeconds = 12.0, ui = ui, extraArgs = extra), d)
            assertTrue(d.started.await(60, TimeUnit.SECONDS), "D başlamadı")
            val infoD = query(sessionD, "window-id", "media-title")
            say("D: pid=${pidOf(sessionD)} pencere=${infoD[0]} medya='${infoD[1]}'")
            assertTrue(pidA == pidOf(sessionD))
            assertEquals(infoA[0], infoD[0])

            // Kalite menüsü (edition) yeni dosyada çalışır, edition sızmaz
            val editions = query(sessionD, "editions", "current-edition")
            say("D: sürümler=${editions[0]} geçerli=${editions[1]}")
            sessionD.stop()
            assertTrue(d.finished.await(20, TimeUnit.SECONDS))
            waitFor("süreç kapanışı", 20) { runCatching { ProcessHandle.of(pidA!!).map { it.isAlive }.orElse(false) }.getOrDefault(false).not() }
            say("D: stop() sonrası mpv süreci kapandı (pid=$pidA)")
        } finally {
            player.close()
        }
    }

    @Test
    fun `natural end keeps the window open and closeIdle closes it`() {
        val command = command()
        val player = MpvPlayer(command)
        val a = Rec()
        val b = Rec()
        try {
            val sessionA = player.play(PlayRequest("av://lavfi:testsrc=size=320x240:rate=25:duration=4", "Kısa", extraArgs = listOf("--ao=null")), a)
            assertTrue(a.started.await(60, TimeUnit.SECONDS))
            val pid = pidOf(sessionA)!!
            assertTrue(a.finished.await(60, TimeUnit.SECONDS), "doğal bitiş gelmedi")
            val summary = assertNotNull(a.summary)
            say("Doğal bitiş: endReason=${summary.endReason} naturalEnd=${summary.naturalEnd} reachedEnd=${summary.reachedEnd} replaced=${summary.replaced} süreç canlı=${ProcessHandle.of(pid).map { it.isAlive }.orElse(false)}")
            assertTrue(summary.naturalEnd && summary.endReason == "eof")
            assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "pencere sonraki bölüm için açık kalmalı")
            // Sonraki bölüm aynı pencerede
            val sessionB = player.play(PlayRequest(lavfi("320x240"), "Sonraki", extraArgs = listOf("--ao=null")), b)
            assertTrue(b.started.await(60, TimeUnit.SECONDS))
            assertTrue(pidOf(sessionB) == pid)
            sessionB.stop()
            assertTrue(b.finished.await(20, TimeUnit.SECONDS))
            say("Bitişten sonra sonraki bölüm aynı süreçte açıldı (pid=$pid)")
        } finally {
            player.close()
        }
    }

    @Test
    fun `plain mpv without the interface also reuses the window`() {
        val command = command()
        val player = MpvPlayer(command)
        val a = Rec()
        val b = Rec()
        val extra = listOf("--ao=null")
        try {
            val sessionA = player.play(PlayRequest(lavfi("640x360"), "Düz mpv 1", extraArgs = extra), a)
            assertTrue(a.started.await(60, TimeUnit.SECONDS))
            val infoA = query(sessionA, "window-id", "title")
            val sessionB = player.play(PlayRequest(lavfi("320x240"), "Düz mpv 2", startSeconds = 3.0, extraArgs = extra), b)
            assertTrue(b.started.await(60, TimeUnit.SECONDS))
            val infoB = query(sessionB, "window-id", "title", "time-pos")
            say("Düz mpv: pid ${pidOf(sessionA)} -> ${pidOf(sessionB)}, pencere ${infoA[0]} -> ${infoB[0]}, başlık '${infoA[1]}' -> '${infoB[1]}', konum ${infoB[2]}")
            assertTrue(pidOf(sessionA) == pidOf(sessionB))
            assertEquals(infoA[0], infoB[0])
            assertEquals("Düz mpv 2", infoB[1])
            assertTrue(a.summary?.replaced == true)
        } finally {
            player.close()
        }
    }

    @Test
    fun `window geometry saved from one process is restored exactly by the next`() {
        val command = command()
        val probe = WindowProbe.platform()
        val user32 = Native.load("user32", User32::class.java)
        say("Ekranlar: ${probe.screens()} sistem DPI=${user32.GetDpiForSystem()}")
        val player = MpvPlayer(command)
        val extra = listOf("--ao=null")

        // Bir pencere açar, [prepare] ile değiştirir, kapatır; kayıtlı son durumu ve açılış çerçevesini döndürür
        fun open(state: PlayerWindowState?, prepare: (PlaybackSession, Rec) -> Unit = { _, _ -> }): Triple<WindowFrame, PlayerWindowState?, Rec> {
            val rec = Rec()
            val session = player.play(PlayRequest(lavfi("640x360"), "Geometri", extraArgs = extra, window = state), rec)
            assertTrue(rec.started.await(60, TimeUnit.SECONDS), "başlamadı")
            Thread.sleep(500) // pencere yerleşsin
            val frame = frameOf(session, probe)
            prepare(session, rec)
            session.stop()
            assertTrue(rec.finished.await(20, TimeUnit.SECONDS))
            waitFor("final durum", 10) { rec.windowStates.any { it.second } }
            return Triple(frame, rec.windowStates.lastOrNull()?.first, rec)
        }

        try {
            // 1) Verilen geometri aynen uygulanır
            val wanted = PlayerWindowState(1000, 600, 200, 150)
            val (frame1, _, _) = open(wanted)
            say("1) istenen=$wanted açılan çerçeve=$frame1")
            assertEquals(WindowFrame(200, 150, 1000, 600), frame1)

            // 2) Pencere işletim sistemi çağrısıyla taşınıp boyutlandırılır (kullanıcı sürüklemesi gibi), durum okunur
            var movedFrame: WindowFrame? = null
            val (_, saved2, rec2) = open(wanted) { session, rec ->
                val hwnd = Pointer.createConstant(query(session, "window-id")[0]!!.toLong())
                user32.SetWindowPos(hwnd, null, 333, 177, 913, 701, 0x0004 /* SWP_NOZORDER */)
                waitFor("taşıma sonrası durum", 15) { rec.windowStates.any { it.first.x != 200 && it.first.width != 1000 } }
                Thread.sleep(1200)
                movedFrame = frameOf(session, probe)
            }
            say("2) taşıdıktan sonra çerçeve=$movedFrame kaydedilen son durum=$saved2 (durum bildirimleri=${rec2.windowStates.size})")
            assertEquals(PlayerWindowState(movedFrame!!.clientWidth, movedFrame!!.clientHeight, movedFrame!!.x, movedFrame!!.y), saved2)

            // 3) Kaydedilen durum yeni süreçte aynen geri gelir (kenarlık, başlık çubuğu, gölge farkı dahil)
            val (frame3, _, _) = open(saved2)
            say("3) kayıtlı=$saved2 yeni süreçte açılan çerçeve=$frame3")
            assertEquals(WindowFrame(saved2!!.x!!, saved2.y!!, saved2.width, saved2.height), frame3)

            // 4) Negatif koordinat (+-X biçimi)
            val negative = PlayerWindowState(900, 500, -120, 90)
            val (frame4, _, _) = open(negative)
            say("4) negatif konum istenen=$negative açılan=$frame4")
            assertEquals(WindowFrame(-120, 90, 900, 500), frame4)

            // 5) Büyütülmüş: normal boyut korunur, geri alınca normal çerçeve gelir
            val maxState = PlayerWindowState(1000, 600, 200, 150, maximized = true)
            var restored5: WindowFrame? = null
            val (frame5, saved5, _) = open(maxState) { session, rec ->
                Thread.sleep(1500)
                say("5) büyütülmüş açıldı: window-maximized=${query(session, "window-maximized")[0]} osd=${query(session, "osd-width", "osd-height")}")
                command(session, "set_property", "window-maximized", false)
                Thread.sleep(1200)
                restored5 = frameOf(session, probe)
                command(session, "set_property", "window-maximized", true)
                Thread.sleep(1500)
            }
            say("5) açılış çerçevesi(büyütülmüş)=$frame5 geri alınca=$restored5 kapanışta kaydedilen=$saved5")
            assertEquals(WindowFrame(200, 150, 1000, 600), restored5)
            assertEquals(PlayerWindowState(1000, 600, 200, 150, maximized = true), saved5, "büyütülmüşken kapatılınca normal boyut korunmalı")

            // 6) Tam ekran
            val fsState = PlayerWindowState(1000, 600, 200, 150, fullscreen = true)
            var restored6: WindowFrame? = null
            val (_, saved6, _) = open(fsState) { session, _ ->
                Thread.sleep(1500)
                say("6) tam ekran açıldı: fullscreen=${query(session, "fullscreen")[0]} osd=${query(session, "osd-width", "osd-height")}")
                command(session, "set_property", "fullscreen", false)
                Thread.sleep(1200)
                restored6 = frameOf(session, probe)
                command(session, "set_property", "fullscreen", true)
                Thread.sleep(1500)
            }
            say("6) geri alınca=$restored6 kapanışta kaydedilen=$saved6")
            assertEquals(WindowFrame(200, 150, 1000, 600), restored6)
            assertEquals(fsState, saved6)

            // 7) Ekran dışı konum: yalnız boyut uygulanır, pencere görünür bir ekranda kalır
            val offscreen = PlayerWindowState(1100, 650, 7000, 300)
            val (frame7, _, _) = open(offscreen)
            say("7) ekran dışı istenen=$offscreen açılan=$frame7")
            assertEquals(1100, frame7.clientWidth)
            assertEquals(650, frame7.clientHeight)
            assertTrue(WindowGeometry.isVisible(frame7.x, frame7.y, frame7.clientWidth, probe.screens()), "pencere görünür olmalı")
            assertNotEquals(7000, frame7.x)
            assertFalse(frame7.minimized)
        } finally {
            player.close()
            File("build").mkdirs()
            File("build/mpv-window-smoke-report.txt").writeText(report.joinToString("\n"))
        }
    }
}
