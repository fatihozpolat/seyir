package seyir.core.hls

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import seyir.core.relay.StreamKind
import seyir.core.relay.StreamRelay
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gerçek doğrulama (elle): herkese açık çok katmanlı akışların ana listesi okunur, her tercih için
 * `--hls-bitrate` ile görüntüsüz mpv aktarıcı üzerinden açılır ve mpv'nin açtığı video yüksekliği
 * beklenen katmanla karşılaştırılır. Ağ ya da mpv yoksa test atlanır.
 *
 * mpv yolu: `-Dseyir.mpv=<yol>` ya da ortam değişkeni `SEYIR_MPV`; yoksa ana klasördeki paketli mpv.
 * Çalıştırma: `.\gradlew.bat :core:test -PmanualTests --tests "*HlsQualityManualTest*" -i`
 */
@Tag("manual")
class HlsQualityManualTest {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private val streams = listOf(
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        // Ses katmanı (gear0), ses grupları ve altyazı grupları içerir
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
    )

    private fun mpvPath(): String? =
        listOfNotNull(
            System.getProperty("seyir.mpv"),
            System.getenv("SEYIR_MPV"),
            "C:/Users/Fatih/seyir/app/resources/windows/mpv/mpv.exe",
        ).firstOrNull { File(it).isFile }

    private fun reachable(url: String) =
        runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } }.getOrDefault(false)

    /** mpv'yi görüntüsüz çalıştırır, açılan videonun yüksekliğini döndürür (video yoksa null). */
    private fun openedHeight(mpv: String, url: String, extra: List<String>): Int? {
        val process = ProcessBuilder(
            listOf(
                mpv, "--no-config", "--vo=null", "--ao=null", "--length=2", "--ytdl=no",
                "--demuxer-lavf-o=extension_picky=0", "--network-timeout=20",
                "--term-playing-msg=HEIGHT=\${height}",
            ) + extra + listOf("--", url),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "mpv zaman aşımı")
        return Regex("""HEIGHT=(\d+)""").find(output)?.groupValues?.get(1)?.toInt()
    }

    @Test
    fun mpvOpensTheLayerSelectedByPreferenceThroughRelay() {
        val mpv = mpvPath()
        assumeTrue(mpv != null, "mpv bulunamadı")
        assumeTrue(streams.all { reachable(it) }, "Test akışlarına erişilemiyor (ağ yok)")

        val fetcher = HlsVariantFetcher({ http })
        StreamRelay({ http }, { host, port -> Socket(host, port) }).use { relay ->
            relay.start()
            for (stream in streams) {
                val layers = runBlocking { fetcher.layers(stream) }
                println("AKIŞ $stream")
                println("  katmanlar: " + layers.joinToString { "${it.label}@${it.bandwidth}" })
                assertTrue(layers.size >= 2, "katman bulunamadı: $stream")

                for (preference in QualityPreference.entries) {
                    val expected = HlsQuality.choose(layers, preference.target)!!
                    val args = HlsQuality.mpvArgs(layers, preference.target)
                    val url = relay.register(stream, StreamKind.HLS, emptyMap(), null)
                    val height = openedHeight(mpv!!, url, args)
                    println("  tercih=${preference.id} args=$args beklenen=${expected.height} mpv=$height")
                    assertEquals(expected.height, height, "$stream tercih ${preference.id}")
                }

                // Kaynaklar penceresindeki gibi: her çipin katmanı tek tek
                for (layer in layers) {
                    val args = HlsQuality.mpvArgs(layers, QualityTarget.MaxHeight(layer.effectiveHeight))
                    val height = openedHeight(mpv!!, relay.register(stream, StreamKind.HLS, emptyMap(), null), args)
                    println("  çip=${layer.label} args=$args mpv=$height")
                    assertEquals(layer.height, height)
                }
            }
        }
    }

    @Test
    fun mpvMinWouldPickAudioOnlyLayerOnBipbopButExplicitBandwidthDoesNot() {
        val mpv = mpvPath()
        assumeTrue(mpv != null, "mpv bulunamadı")
        val stream = streams[1]
        assumeTrue(reachable(stream), "Test akışına erişilemiyor (ağ yok)")
        val minHeight = openedHeight(mpv!!, stream, listOf("--hls-bitrate=min"))
        println("bipbop --hls-bitrate=min -> yükseklik=$minHeight (null: yalnız ses katmanı açıldı)")
        val layers = runBlocking { HlsVariantFetcher({ http }).layers(stream) }
        val lowest = openedHeight(mpv, stream, HlsQuality.mpvArgs(layers, QualityTarget.Lowest))
        println("bipbop açık bant genişliği -> yükseklik=$lowest")
        assertEquals(layers.last().height, lowest)
    }
}
