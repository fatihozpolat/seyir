package seyir.core.relay

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamRelayTest {
    private lateinit var upstream: HttpServer
    private lateinit var relay: StreamRelay
    private val http = OkHttpClient()
    private val seenHeaders = CopyOnWriteArrayList<Map<String, String>>()

    /** MPEG-TS gibi başlayan (0x47 senkron baytı) sahte segment. */
    private val segment = ByteArray(188 * 5) { if (it % 188 == 0) 0x47 else (it % 251).toByte() }
    private val key = ByteArray(16) { (it * 7 + 1).toByte() }
    private val movie = ByteArray(1000) { (it % 256).toByte() }

    private val upstreamBase get() = "http://127.0.0.1:${upstream.address.port}"

    @BeforeTest
    fun setUp() {
        upstream = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        upstream.executor = java.util.concurrent.Executors.newCachedThreadPool()
        // Uzantısız ana liste
        upstream.createContext("/v/master") { ex ->
            reply(
                ex,
                ("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"tr\",URI=\"audio/list\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360\nmedia\n").toByteArray(),
                "text/plain",
            )
        }
        upstream.createContext("/v/media") { ex ->
            reply(
                ex,
                ("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"../k/key?id=1\",IV=0x01\n" +
                    "#EXTINF:4,\nseg1.png\n#EXTINF:4,\n$upstreamBase/v/seg2.png\n#EXT-X-ENDLIST\n").toByteArray(),
                "application/octet-stream",
            )
        }
        upstream.createContext("/v/audio/list") { ex -> reply(ex, "#EXTM3U\n#EXTINF:4,\ns.aac\n".toByteArray(), "text/plain") }
        upstream.createContext("/v/seg1.png") { ex -> reply(ex, segment, "image/png") }
        upstream.createContext("/v/seg2.png") { ex -> reply(ex, segment, "image/png") }
        upstream.createContext("/k/key") { ex -> reply(ex, key, "application/octet-stream") }
        upstream.createContext("/movie.mp4") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range == null) {
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                reply(ex, movie, "video/mp4")
            } else {
                val (from, to) = range.removePrefix("bytes=").split('-').let { it[0].toInt() to it[1].toInt() }
                ex.responseHeaders.add("Content-Range", "bytes $from-$to/${movie.size}")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                reply(ex, movie.copyOfRange(from, to + 1), "video/mp4", 206)
            }
        }
        upstream.createContext("/echo-headers") { ex ->
            seenHeaders += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            reply(ex, "ok".toByteArray(), "text/plain")
        }
        // Yalnız oynatıcı sayfasının (ana listenin) Referer'ını kabul eden CDN taklidi
        upstream.createContext("/guarded/list") { ex ->
            reply(ex, "#EXTM3U\n#EXTINF:2,\nseg.png\n#EXT-X-ENDLIST\n".toByteArray(), "text/plain")
        }
        upstream.createContext("/guarded/seg.png") { ex ->
            val ok = ex.requestHeaders.getFirst("Referer")?.startsWith("$upstreamBase/guarded/list") == true
            if (ok) reply(ex, segment, "image/png") else reply(ex, "yasak".toByteArray(), "text/plain", 403)
        }
        upstream.createContext("/missing") { ex -> reply(ex, "yok".toByteArray(), "text/plain", 404) }
        upstream.createContext("/stream") { ex ->
            // Uzunluk bilinmiyor: parçalı yanıt
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { o -> repeat(3) { o.write("parca$it;".toByteArray()); o.flush() } }
        }
        upstream.start()

        relay = StreamRelay({ http }, { host, port -> Socket(host, port) })
        relay.start()
    }

    @AfterTest
    fun tearDown() {
        relay.close()
        upstream.stop(0)
    }

    private fun reply(ex: HttpExchange, data: ByteArray, type: String, status: Int = 200) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Response =
        http.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute()

    private fun line(text: String, prefix: String) = text.lines().first { it.startsWith(prefix) }

    @Test
    fun rewritesPlaylistsAndPassesSegmentBytes() {
        val entry = relay.register("$upstreamBase/v/master", StreamKind.HLS, emptyMap(), null)
        assertTrue(entry.startsWith("http://127.0.0.1:${relay.port}/r/"), entry)
        assertTrue(entry.endsWith(".m3u8"), entry)

        val master = get(entry).use { r ->
            assertEquals("application/vnd.apple.mpegurl", r.header("Content-Type"))
            r.body.string()
        }
        val mediaUrl = master.lines().first { it.startsWith("http://") }
        assertTrue(mediaUrl.endsWith(".m3u8"), mediaUrl)
        val audioUrl = Regex("""URI="([^"]+)"""").find(line(master, "#EXT-X-MEDIA"))!!.groupValues[1]
        assertTrue(audioUrl.startsWith("http://127.0.0.1:${relay.port}/r/") && audioUrl.endsWith(".m3u8"), audioUrl)

        val media = get(mediaUrl).use { it.body.string() }
        val keyUrl = Regex("""URI="([^"]+)"""").find(line(media, "#EXT-X-KEY"))!!.groupValues[1]
        assertTrue(keyUrl.endsWith(".key") && keyUrl.contains("/r/"), keyUrl)
        assertTrue(line(media, "#EXT-X-KEY").endsWith("IV=0x01"))
        val segments = media.lines().filter { it.startsWith("http://") }
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.endsWith(".ts") }, segments.toString())

        // Alt ses listesindeki .aac segmenti uzantısını korur
        val audio = get(audioUrl).use { it.body.string() }
        assertTrue(audio.lines().first { it.startsWith("http://") }.endsWith(".aac"))

        for (seg in segments) {
            get(seg).use { r ->
                assertEquals(200, r.code)
                assertEquals("video/mp2t", r.header("Content-Type"))
                assertContentEquals(segment, r.body.bytes())
            }
        }
        get(keyUrl).use { r -> assertContentEquals(key, r.body.bytes()) }
    }

    @Test
    fun sniffsPlaylistWhenContentTypeLies() {
        // /v/media text/plain değil octet-stream ile geldi, yine de yeniden yazılmalı
        val url = relay.register("$upstreamBase/v/media", StreamKind.HLS, emptyMap(), null)
        val text = get(url).use { it.body.string() }
        assertTrue(text.lines().none { it.endsWith(".png") }, text)
    }

    @Test
    fun forwardsRefererAndCustomHeaders() {
        val url = relay.register(
            "$upstreamBase/echo-headers", StreamKind.FILE,
            mapOf("X-Token" to "abc123"), "https://site.example/page",
        )
        get(url).use { assertEquals(200, it.code) }
        val seen = seenHeaders.single()
        assertEquals("https://site.example/page", seen["referer"])
        assertEquals("abc123", seen["x-token"])
        assertTrue(seen["user-agent"]!!.startsWith("Mozilla/5.0"), seen.toString())
        // İstemci Accept göndermese de upstream'e oynatıcınınki gibi */* gider
        assertEquals("*/*", seen["accept"])
    }

    @Test
    fun retriesForbiddenSegmentWithPlaylistAsReferer() {
        val entry = relay.register("$upstreamBase/guarded/list", StreamKind.HLS, emptyMap(), "https://site.example/")
        val seg = get(entry).use { it.body.string() }.lines().first { it.startsWith("http://") }
        get(seg).use { r ->
            assertEquals(200, r.code)
            assertContentEquals(segment, r.body.bytes())
        }
    }

    @Test
    fun forwardsRangeAsPartialContent() {
        val url = relay.register("$upstreamBase/movie.mp4", StreamKind.FILE, emptyMap(), null)
        assertTrue(url.endsWith("/video.mp4"), url)
        get(url, mapOf("Range" to "bytes=10-19")).use { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 10-19/1000", r.header("Content-Range"))
            assertEquals("10", r.header("Content-Length"))
            assertEquals("bytes", r.header("Accept-Ranges"))
            assertContentEquals(movie.copyOfRange(10, 20), r.body.bytes())
        }
    }

    @Test
    fun passesUpstreamErrorCodes() {
        val url = relay.register("$upstreamBase/missing", StreamKind.FILE, emptyMap(), null)
        get(url).use { r ->
            assertEquals(404, r.code)
            assertEquals("yok", r.body.string())
        }
    }

    @Test
    fun registersSubtitleWithExtension() {
        val vtt = relay.registerSubtitle("$upstreamBase/echo-headers", emptyMap(), null)
        assertTrue(vtt.endsWith(".vtt"), vtt)
        val srt = relay.registerSubtitle("$upstreamBase/x/altyazi.srt?dl=1", emptyMap(), null)
        assertTrue(srt.endsWith(".srt"), srt)
        get(vtt).use { assertEquals("ok", it.body.string()) }
    }

    @Test
    fun servesTwoRequestsOnOneConnection() {
        val url = relay.register("$upstreamBase/movie.mp4", StreamKind.FILE, emptyMap(), null)
        val path = url.removePrefix("http://127.0.0.1:${relay.port}")
        Socket("127.0.0.1", relay.port).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            repeat(2) {
                out.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:${relay.port}\r\n\r\n".toByteArray())
                out.flush()
                val (status, body) = readResponse(input)
                assertEquals(200, status)
                assertContentEquals(movie, body)
            }
        }
    }

    @Test
    fun usesChunkedEncodingWhenLengthUnknown() {
        val url = relay.register("$upstreamBase/stream", StreamKind.FILE, emptyMap(), null)
        get(url).use { r ->
            assertEquals(null, r.header("Content-Length"))
            assertEquals("chunked", r.header("Transfer-Encoding"))
            assertEquals("parca0;parca1;parca2;", r.body.string())
        }
    }

    @Test
    fun connectTunnelCarriesBytesToEchoServer() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { echo ->
            Thread.ofVirtual().start {
                echo.accept().use { s ->
                    val buf = ByteArray(1024)
                    while (true) {
                        val n = s.getInputStream().read(buf)
                        if (n < 0) break
                        s.getOutputStream().write(buf, 0, n)
                        s.getOutputStream().flush()
                    }
                }
            }
            Socket("127.0.0.1", relay.port).use { socket ->
                socket.soTimeout = 10_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${echo.localPort}\r\n\r\n".toByteArray())
                out.flush()
                val head = readHead(input)
                assertTrue(head.startsWith("HTTP/1.1 200 Connection Established"), head)
                val payload = ByteArray(5000) { (it * 3).toByte() }
                out.write(payload)
                out.flush()
                assertContentEquals(payload, input.readNBytes(payload.size))
            }
        }
    }

    @Test
    fun connectToUnreachableTargetReturns502() {
        val broken = StreamRelay({ http }, { _, _ -> throw java.io.IOException("erişilemiyor") })
        broken.start()
        broken.use {
            Socket("127.0.0.1", it.port).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().write("CONNECT example.invalid:443 HTTP/1.1\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                assertTrue(readHead(socket.getInputStream()).startsWith("HTTP/1.1 502"))
            }
        }
    }

    @Test
    fun forwardsAbsoluteUriRequestsThroughClient() {
        val proxied = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relay.port)))
            .build()
        proxied.newCall(Request.Builder().url("$upstreamBase/movie.mp4").build()).execute().use { r ->
            assertEquals(200, r.code)
            assertContentEquals(movie, r.body.bytes())
        }
        proxied.newCall(Request.Builder().url("$upstreamBase/missing").build()).execute().use { r ->
            assertEquals(404, r.code)
        }
    }

    // ---- yardımcılar ----

    @Test
    fun lanSessionUsesLanPortAddsCorsAndStripsFakeImageHeader() {
        val fakePng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(63) { 1 }
        upstream.createContext("/lan/list") { ex ->
            reply(ex, "#EXTM3U\n#EXTINF:4,\nfake.png\n#EXT-X-ENDLIST\n".toByteArray(), "text/plain")
        }
        upstream.createContext("/lan/fake.png") { ex -> reply(ex, fakePng + segment, "image/png") }

        val entry = relay.register("$upstreamBase/lan/list", StreamKind.HLS, emptyMap(), null, lanHost = "127.0.0.1")
        val lanPort = relay.startLan()
        assertTrue(entry.startsWith("http://127.0.0.1:$lanPort/r/"), entry)

        val list = get(entry).use { r ->
            assertEquals("*", r.header("Access-Control-Allow-Origin"))
            r.body.string()
        }
        val seg = list.lines().first { it.startsWith("http://") }
        assertTrue(seg.startsWith("http://127.0.0.1:$lanPort/r/") && seg.endsWith(".ts"), seg)
        get(seg).use { r ->
            assertEquals("*", r.header("Access-Control-Allow-Origin"))
            assertEquals("video/mp2t", r.header("Content-Type"))
            assertContentEquals(segment, r.body.bytes())
        }
    }

    @Test
    fun lanPortServesOnlyLanSessions() {
        val local = relay.register("$upstreamBase/movie.mp4", StreamKind.FILE, emptyMap(), null)
        val lanPort = relay.startLan()
        get(local.replace(":${relay.port}/", ":$lanPort/")).use { assertEquals(404, it.code) }

        // Vekil isteği ağ kapısında reddedilir, izin sorgusu yanıtlanır
        Socket("127.0.0.1", lanPort).use { s ->
            s.getOutputStream().write("GET $upstreamBase/movie.mp4 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            assertEquals(403, readResponse(s.getInputStream()).first)
        }
        val lan = relay.register("$upstreamBase/movie.mp4", StreamKind.FILE, emptyMap(), null, lanHost = "127.0.0.1")
        val preflight = Request.Builder().url(lan).method("OPTIONS", null).header("Access-Control-Request-Headers", "range").build()
        http.newCall(preflight).execute().use { r ->
            assertEquals(204, r.code)
            assertEquals("*", r.header("Access-Control-Allow-Origin"))
            assertEquals("range", r.header("Access-Control-Allow-Headers"))
        }
    }

    @Test
    fun lanSubtitleIsServedAsWebVtt() {
        upstream.createContext("/sub.srt") { ex ->
            reply(ex, "1\r\n00:00:01,500 --> 00:00:03,000\r\nMerhaba\r\n".toByteArray(), "application/x-subrip")
        }
        val url = relay.registerSubtitle("$upstreamBase/sub.srt", emptyMap(), null, lanHost = "127.0.0.1")
        assertTrue(url.endsWith(".vtt"), url)
        get(url).use { r ->
            assertEquals("text/vtt; charset=utf-8", r.header("Content-Type"))
            assertEquals("WEBVTT\n\n1\n00:00:01.500 --> 00:00:03.000\nMerhaba\n", r.body.string())
        }
    }

    @Test
    fun subtitleInWindows1254IsServedAsUtf8() {
        val text = "1\r\n00:00:01,500 --> 00:00:03,000\r\nÇok güzel şarkı, ığdır İstanbul\r\n"
        upstream.createContext("/tr.srt") { ex ->
            reply(ex, text.toByteArray(charset("windows-1254")), "application/x-subrip; charset=windows-1254")
        }
        val url = relay.registerSubtitle("$upstreamBase/tr.srt", emptyMap(), null)
        assertTrue(url.endsWith(".srt"), url)
        get(url).use { r ->
            assertTrue(r.header("Content-Type")!!.contains("utf-8"), r.header("Content-Type"))
            assertEquals(text, String(r.body.bytes(), Charsets.UTF_8))
        }
    }

    @Test
    fun utf8SubtitleIsServedUnchanged() {
        val text = "1\n00:00:01,500 --> 00:00:03,000\nÇok güzel\n"
        upstream.createContext("/u8.srt") { ex -> reply(ex, text.toByteArray(Charsets.UTF_8), "text/plain") }
        val url = relay.registerSubtitle("$upstreamBase/u8.srt", emptyMap(), null)
        get(url).use { r -> assertEquals(text, String(r.body.bytes(), Charsets.UTF_8)) }
    }

    @Test
    fun lanSubtitleInWindows1254BecomesUtf8WebVtt() {
        upstream.createContext("/tr2.srt") { ex ->
            reply(ex, "1\r\n00:00:01,500 --> 00:00:03,000\r\nşğıİ\r\n".toByteArray(charset("windows-1254")), "application/x-subrip")
        }
        val url = relay.registerSubtitle("$upstreamBase/tr2.srt", emptyMap(), null, lanHost = "127.0.0.1")
        get(url).use { r ->
            assertEquals("WEBVTT\n\n1\n00:00:01.500 --> 00:00:03.000\nşğıİ\n", String(r.body.bytes(), Charsets.UTF_8))
        }
    }

    @Test
    fun findsTsStartAfterGarbage() {
        val garbage = ByteArray(70) { 0x47 } // senkron baytına benzeyen ama 188 arayla sürmeyen önek
        assertEquals(70, StreamRelay.tsStart(okio.ByteString.of(*(garbage + segment))))
        assertEquals(0, StreamRelay.tsStart(okio.ByteString.of(*segment)))
        assertEquals("WEBVTT\n00:01.000 --> 00:02.000\nx", StreamRelay.toWebVtt("WEBVTT\n00:01.000 --> 00:02.000\nx"))
    }

    // ---- SSRF savunması ----

    /** `*.example.test` adlarını bu bilgisayara (127.0.0.1) çözen istemci: "genel ad özel adrese çözülüyor" durumunu taklit eder. */
    private val rebinding = OkHttpClient.Builder()
        .dns { listOf(InetAddress.getByName("127.0.0.1")) }
        .proxy(Proxy.NO_PROXY)
        .build()

    private val upstreamPort get() = upstream.address.port

    /** Kayıtlı yerel URL'deki upstream adresini [target] ile değiştirir (oturum kimliği aynı kalır). */
    private fun retarget(localUrl: String, target: String): String {
        val parts = localUrl.split('/').toMutableList()
        parts[parts.size - 2] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        return parts.joinToString("/")
    }

    @Test
    fun sessionRefusesHostsOutsideItsOrigin() {
        val relay2 = StreamRelay({ rebinding }, { host, port -> Socket(host, port) }).apply { start() }
        try {
            val url = relay2.register("http://origin.example.test:$upstreamPort/movie.mp4", StreamKind.FILE, emptyMap(), null)
            get(url).use { assertEquals(200, it.code) }
            // Oturum kimliği bilinse bile başka ana bilgisayar (yerel olan da dahil) aktarılmaz
            for (other in listOf("http://other.example.test:$upstreamPort/movie.mp4", "http://127.0.0.1:$upstreamPort/movie.mp4", "http://localhost:$upstreamPort/movie.mp4")) {
                get(retarget(url, other)).use { assertEquals(403, it.code, other) }
            }
        } finally {
            relay2.close()
        }
    }

    @Test
    fun playlistHostsJoinTheSessionButPrivateOnesDoNot() {
        upstream.createContext("/h/list") { ex ->
            val text = "#EXTM3U\n#EXTINF:2,\nhttp://cdn.example.test:$upstreamPort/v/seg1.png\n" +
                "#EXTINF:2,\nhttp://127.0.0.1:$upstreamPort/v/seg2.png\n#EXT-X-ENDLIST\n"
            reply(ex, text.toByteArray(), "text/plain")
        }
        val relay2 = StreamRelay({ rebinding }, { host, port -> Socket(host, port) }).apply { start() }
        try {
            val entry = relay2.register("http://origin.example.test:$upstreamPort/h/list", StreamKind.HLS, emptyMap(), null)
            val segments = get(entry).use { it.body.string() }.lines().filter { it.startsWith("http://") }
            assertEquals(2, segments.size)
            // Listenin başvurduğu genel ana bilgisayar açılır; özel adres (liste genel bir adresten geldiği için) açılmaz
            get(segments[0]).use { assertEquals(200, it.code) }
            get(segments[1]).use { assertEquals(403, it.code) }
            // Listede geçmeyen ana bilgisayar yine reddedilir
            get(retarget(segments[0], "http://unlisted.example.test:$upstreamPort/v/seg1.png")).use { assertEquals(403, it.code) }
        } finally {
            relay2.close()
        }
    }

    @Test
    fun lanSessionNeverReachesPrivateAddresses() {
        val relay2 = StreamRelay({ rebinding }, { host, port -> Socket(host, port) }).apply { start() }
        try {
            // Genel görünen ad özel adrese çözülüyor (DNS rebinding): ağ kapısında kesilir, yerel kapıda (mpv) geçer
            val target = "http://origin.example.test:$upstreamPort/movie.mp4"
            val local = relay2.register(target, StreamKind.FILE, emptyMap(), null)
            get(local).use { assertEquals(200, it.code) }
            val lan = relay2.register(target, StreamKind.FILE, emptyMap(), null, lanHost = "127.0.0.1")
            get(lan).use { assertEquals(502, it.code) }
            // Kayıt adresi zaten yerel/özel bir hedefse (ev ağındaki sunucu) ağ oturumu da çalışır
            val homeServer = relay2.register("$upstreamBase/movie.mp4", StreamKind.FILE, emptyMap(), null, lanHost = "127.0.0.1")
            get(homeServer).use { assertEquals(200, it.code) }
        } finally {
            relay2.close()
        }
    }

    @Test
    fun stopLanClosesGateAndDropsLanSessions() {
        val lan = relay.register("$upstreamBase/movie.mp4", StreamKind.FILE, emptyMap(), null, lanHost = "127.0.0.1")
        val local = relay.register("$upstreamBase/movie.mp4", StreamKind.FILE, emptyMap(), null)
        val lanPort = relay.startLan()
        assertTrue(relay.lanOpen)
        get(lan).use { assertEquals(200, it.code) }

        relay.stopLan()
        assertTrue(!relay.lanOpen)
        assertFailsWith<java.io.IOException> { Socket("127.0.0.1", lanPort).use { } }
        // Yerel kapı ve oturumu etkilenmez
        get(local).use { assertEquals(200, it.code) }
        // İkinci çağrı zararsız; yeni ağ kaydı kapıyı yeniden açar ve eski ağ oturumu geri gelmez
        relay.stopLan()
        val again = relay.register("$upstreamBase/movie.mp4", StreamKind.FILE, emptyMap(), null, lanHost = "127.0.0.1")
        assertTrue(relay.lanOpen)
        get(again).use { assertEquals(200, it.code) }
        get(lan.replace(":$lanPort/", ":${relay.startLan()}/")).use { assertEquals(404, it.code) }
    }

    @Test
    fun classifiesPrivateAddresses() {
        for (host in listOf("127.0.0.1", "10.1.2.3", "192.168.0.7", "172.16.5.5", "169.254.1.1", "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1")) {
            assertTrue(HostPolicy.isLocalHost(host), host)
        }
        for (host in listOf("203.0.113.5", "198.51.100.7", "172.32.0.1", "100.128.0.1", "2001:db8::1", "cdn.example.com")) {
            assertTrue(!HostPolicy.isLocalHost(host), host)
        }
        assertTrue(HostPolicy.isLocalHost("localhost") && HostPolicy.isLocalHost("nas") && HostPolicy.isLocalHost("tv.local"))
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            check(b >= 0) { "bağlantı erken kapandı: $sb" }
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readResponse(input: InputStream): Pair<Int, ByteArray> {
        val head = readHead(input)
        val status = head.substringAfter(' ').substringBefore(' ').toInt()
        val length = Regex("(?i)content-length: (\\d+)").find(head)!!.groupValues[1].toInt()
        val body = ByteArrayOutputStream().also { it.write(input.readNBytes(length)) }
        return status to body.toByteArray()
    }
}
