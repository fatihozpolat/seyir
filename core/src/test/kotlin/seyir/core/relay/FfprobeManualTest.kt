package seyir.core.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Gerçek doğrulama: PATH'teki `ffprobe` ile herkese açık bir HLS test akışı aktarıcı üzerinden
 * açılır. `ffprobe` yoksa veya ağ erişimi yoksa test atlanır.
 */
@Tag("manual")
class FfprobeManualTest {
    private val streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    @Test
    fun ffprobeReadsHlsStreamThroughRelay() {
        val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        val reachable = runCatching { http.newCall(Request.Builder().url(streamUrl).build()).execute().use { it.isSuccessful } }
            .getOrDefault(false)
        assumeTrue(reachable, "Test akışına erişilemiyor (ağ yok)")
        val ffprobeFound = runCatching {
            ProcessBuilder("ffprobe", "-version").redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() == 0
        }.getOrDefault(false)
        assumeTrue(ffprobeFound, "ffprobe PATH'te yok")

        StreamRelay({ http }, { host, port -> Socket(host, port) }).use { relay ->
            relay.start()
            val local = relay.register(streamUrl, StreamKind.HLS, emptyMap(), null)
            val process = ProcessBuilder(
                "ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height",
                "-of", "default=nw=1", local,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "ffprobe zaman aşımı")
            println("ffprobe çıktısı:\n$output")
            assertTrue("codec_type=video" in output, output)
        }
    }
}
