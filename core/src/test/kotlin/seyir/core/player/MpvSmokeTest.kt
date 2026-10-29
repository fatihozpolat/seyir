package seyir.core.player

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gerçek mpv ile duman testi: ağ ve ekran gerektirir (mpv penceresi açılır), bu yüzden yalnızca
 * `SEYIR_MANUAL=1` ortam değişkeniyle çalışır. mpv yolu `SEYIR_MPV` ile ya da depodaki
 * `app/resources/windows/mpv/` klasöründen alınır.
 *
 * Çalıştırma: `SEYIR_MANUAL=1 ./gradlew :core:test --tests '*MpvSmokeTest*' --console=plain -i`
 */
@Tag("manual")
class MpvSmokeTest {
    private val streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    private fun locateMpv(): MpvCommand {
        assumeTrue(System.getenv("SEYIR_MANUAL") == "1", "elle çalıştırılan test (SEYIR_MANUAL=1)")
        val bundled = listOf("../app/resources/windows/mpv/mpv.exe", "app/resources/windows/mpv/mpv.exe")
            .map(::File).firstOrNull { it.isFile }?.absolutePath
        val command = MpvLocator.find(System.getenv("SEYIR_MPV") ?: bundled)
        assumeTrue(command != null, "mpv bulunamadı")
        return command!!
    }

    private class Recorder : PlaybackListener {
        val started = CountDownLatch(1)
        val progress = CopyOnWriteArrayList<Pair<Double, Double?>>()
        val exited = CountDownLatch(1)

        @Volatile var exitPosition: Double? = null
        @Volatile var exitDuration: Double? = null
        @Volatile var reachedEnd: Boolean? = null
        @Volatile var error: String? = null

        override fun onStarted() = started.countDown()
        override fun onProgress(position: Double, duration: Double?) {
            progress += position to duration
            println("onProgress position=$position duration=$duration")
        }

        override fun onExit(position: Double?, duration: Double?, reachedEnd: Boolean, error: String?) {
            exitPosition = position
            exitDuration = duration
            this.reachedEnd = reachedEnd
            this.error = error
            println("onExit position=$position duration=$duration reachedEnd=$reachedEnd error=$error")
            exited.countDown()
        }
    }

    @Test
    fun `plays hls stream from start offset reports progress and stops`() {
        val player = MpvPlayer(locateMpv())
        val recorder = Recorder()
        val session = player.play(PlayRequest(url = streamUrl, title = "Seyir duman testi", startSeconds = 5.0), recorder)
        try {
            assertTrue(recorder.started.await(60, TimeUnit.SECONDS), "onStarted gelmedi")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (recorder.progress.size < 3 && System.nanoTime() < deadline) Thread.sleep(200)
            assertTrue(recorder.progress.size >= 3, "yeterli onProgress gelmedi: ${recorder.progress}")
            assertTrue(session.isRunning)
        } finally {
            session.stop()
        }
        assertTrue(recorder.exited.await(15, TimeUnit.SECONDS), "onExit gelmedi")
        assertTrue(!session.isRunning)
        assertNull(recorder.error)
        assertEquals(false, recorder.reachedEnd)
        val position = assertNotNull(recorder.exitPosition)
        assertTrue(position >= 5.0 && position < 120.0, "son konum beklenmedik: $position")
        assertTrue(recorder.progress.first().first >= 4.0, "başlangıç konumu uygulanmadı: ${recorder.progress.first()}")
    }

    @Test
    fun `adds subtitles over ipc`() {
        val command = locateMpv()
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/tr.vtt") { exchange ->
            hits.incrementAndGet()
            val body = "WEBVTT\n\n00:00:01.000 --> 00:00:30.000\nMerhaba Seyir\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/vtt")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val recorder = Recorder()
        val session = MpvPlayer(command).play(
            PlayRequest(
                url = streamUrl,
                title = "Seyir altyazı testi",
                startSeconds = 5.0,
                subtitles = listOf(
                    SubtitleTrack("http://127.0.0.1:${server.address.port}/tr.vtt", "Türkçe Altyazı", "tr"),
                ),
            ),
            recorder,
        )
        try {
            assertTrue(recorder.started.await(60, TimeUnit.SECONDS), "onStarted gelmedi")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (hits.get() == 0 && System.nanoTime() < deadline) Thread.sleep(200)
            assertTrue(hits.get() > 0, "mpv altyazıyı istemedi")
            // İkinci bir IPC istemcisiyle parça listesine bak: başlık, dil ve seçili olma
            val endpoint = assertNotNull((session as MpvSession).ipcEndpoint)
            val second = MpvIpcClient(MpvIpcTransports.open(endpoint, seyir.core.AppDirs.isWindows))
            second.use {
                var subTrack: com.fasterxml.jackson.databind.JsonNode? = null
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (subTrack == null && System.nanoTime() < until) {
                    val tracks = it.request(listOf("get_property", "track-list")).data
                    subTrack = tracks?.firstOrNull { t -> t.get("type")?.asText() == "sub" && t.get("external")?.asBoolean() == true }
                    if (subTrack == null) Thread.sleep(300)
                }
                println("altyazı parçası: $subTrack")
                assertNotNull(subTrack, "harici altyazı parçası listede yok")
                assertEquals("Türkçe Altyazı", subTrack.get("title")?.asText())
                assertEquals("tr", subTrack.get("lang")?.asText())
                assertEquals(true, subTrack.get("selected")?.asBoolean())
            }
        } finally {
            session.stop()
            server.stop(0)
        }
        assertTrue(recorder.exited.await(15, TimeUnit.SECONDS), "onExit gelmedi")
        assertNull(recorder.error)
    }

    @Test
    fun `unreachable url reports an error`() {
        val player = MpvPlayer(locateMpv())
        val recorder = Recorder()
        player.play(PlayRequest(url = "http://127.0.0.1:9/yok.m3u8", title = "hata testi"), recorder)
        assertTrue(recorder.exited.await(60, TimeUnit.SECONDS), "onExit gelmedi")
        println("hata metni: ${recorder.error}")
        assertNotNull(recorder.error)
    }
}
