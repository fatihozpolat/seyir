package seyir.core.hls

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlsVariantFetcherTest {
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val seen = CopyOnWriteArrayList<Map<String, String>>()
    private val hits = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()

    private val master = "#EXTM3U\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nhd.m3u8\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\nsd.m3u8\n"

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/master.m3u8") { ex ->
            hits.incrementAndGet()
            seen += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            reply(ex, 200, master)
        }
        // Accept başlığı olmadan 200 kodlu hata metni dönen sunucu
        server.createContext("/needs-accept.m3u8") { ex ->
            val ok = ex.requestHeaders.getFirst("Accept") != null
            reply(ex, 200, if (ok) master else "Hata: bad request")
        }
        server.createContext("/media.m3u8") { ex -> reply(ex, 200, "#EXTM3U\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST\n") }
        server.createContext("/boom.m3u8") { ex -> reply(ex, 500, "hata") }
        server.createContext("/slow.m3u8") { ex ->
            Thread.sleep(2_000)
            reply(ex, 200, master)
        }
        // İlk istek Referer'sız olduğu için 403; kaydedilen adres Referer yapılınca 200
        server.createContext("/guarded.m3u8") { ex ->
            val referer = ex.requestHeaders.getFirst("Referer").orEmpty()
            if (referer.endsWith("/guarded.m3u8")) reply(ex, 200, master) else reply(ex, 403, "yasak")
        }
        server.createContext("/parallel") { ex ->
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now, ::maxOf)
            Thread.sleep(150)
            inFlight.decrementAndGet()
            reply(ex, 200, master)
        }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private fun reply(ex: HttpExchange, code: Int, text: String) {
        val data = text.toByteArray()
        ex.sendResponseHeaders(code, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    private fun fetcher(timeoutMs: Long = 3_000) = HlsVariantFetcher({ OkHttpClient() }, timeoutMs)

    @Test
    fun returnsLayersAndSendsLinkHeadersRefererAndAccept() = runBlocking {
        val layers = fetcher().layers("$base/master.m3u8", mapOf("X-Token" to "abc"), "https://player.example/")
        assertEquals(listOf(720, 480), layers.map { it.height })
        // Göreli adres, yönlendirilmiş son adrese göre çözülür
        assertEquals("$base/hd.m3u8", layers[0].url)
        val headers = seen.single()
        assertEquals("abc", headers["x-token"])
        assertEquals("https://player.example/", headers["referer"])
        assertEquals("*/*", headers["accept"])
        assertTrue(headers["user-agent"].orEmpty().startsWith("Mozilla"))
    }

    @Test
    fun interceptorIsApplied() = runBlocking {
        val interceptor = Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-Intercepted", "1").build()) }
        fetcher().layers("$base/master.m3u8", interceptor = interceptor)
        assertEquals("1", seen.single()["x-intercepted"])
    }

    @Test
    fun cachesResultAndDoesNotHitNetworkAgain() = runBlocking {
        val f = fetcher()
        f.layers("$base/master.m3u8")
        f.layers("$base/master.m3u8")
        assertEquals(1, hits.get())
        assertEquals(2, f.cached("$base/master.m3u8", null)?.size)
    }

    @Test
    fun acceptHeaderMakesServerRespondWithPlaylist() = runBlocking {
        assertEquals(2, fetcher().layers("$base/needs-accept.m3u8").size)
    }

    @Test
    fun mediaPlaylistErrorsAndBadUrlsMeanNoLayers() = runBlocking {
        val f = fetcher()
        assertTrue(f.layers("$base/media.m3u8").isEmpty())
        assertTrue(f.layers("$base/boom.m3u8").isEmpty())
        assertTrue(f.layers("$base/yok.m3u8").isEmpty())
        assertTrue(f.layers("bu bir adres degil").isEmpty())
        // Kapalı bir porta bağlanılamaması da sessizce boş döner
        assertTrue(f.layers("http://127.0.0.1:1/master.m3u8").isEmpty())
    }

    @Test
    fun timeoutMeansNoLayersAndIsRememberedBriefly() = runBlocking {
        val f = fetcher(timeoutMs = 300)
        val started = System.nanoTime()
        assertTrue(f.layers("$base/slow.m3u8").isEmpty())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500, "zaman aşımı beklenenden uzun sürdü")
        // Başarısızlık kısa süre hatırlanır: ikinci çağrı hemen döner
        val again = System.nanoTime()
        assertTrue(f.layers("$base/slow.m3u8").isEmpty())
        assertTrue((System.nanoTime() - again) / 1_000_000 < 100)
    }

    @Test
    fun forbiddenIsRetriedWithOriginAsReferer() = runBlocking {
        assertEquals(2, fetcher().layers("$base/guarded.m3u8").size)
    }

    @Test
    fun concurrencyIsLimitedToThree() = runBlocking {
        val f = fetcher()
        (1..9).map { i -> async { f.layers("$base/parallel?i=$i") } }.awaitAll()
        assertTrue(maxInFlight.get() in 1..3, "eşzamanlı istek ${maxInFlight.get()}")
    }
}
