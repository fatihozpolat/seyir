package seyir.core.relay

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.ByteString
import seyir.core.SeyirLog
import seyir.core.player.SubtitleEncoding
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Aktarılan akışın türü; yerel URL'nin uzantısını ve işlemeyi belirler. */
enum class StreamKind { HLS, DASH, FILE }

/**
 * Yalnız 127.0.0.1'e bağlanan küçük HTTP/1.1 sunucusu. İki işi vardır:
 *
 * 1. **Aktarma:** oynatıcının (mpv/ffmpeg) ve altyazıların upstream isteklerini [client] ile yapar
 *    (parçalama/DoH/vekil dahil), HLS oynatma listelerindeki bağlantıları yerel adrese çevirir.
 *    Yerel URL şeması: `http://127.0.0.1:P/r/{sid}/{base64url(upstreamUrl)}/{ad}.{uzantı}`.
 *    Uzantılar ffmpeg'in uzantı denetimini geçmek için seçilir (liste `.m3u8`, segment `.ts`,
 *    anahtar `.key`, MAP `.mp4`, altyazı `.vtt`/`.srt`).
 * 2. **CONNECT vekili:** tarayıcıya `--proxy-server=http://127.0.0.1:P` ile verilir; `CONNECT`
 *    isteklerini [tunnel] ile açılan soketlere bağlar, düz `GET http://...` isteklerini [client]
 *    ile iletir.
 *
 * Televizyona gönderim için [startLan] ikinci bir kapı açar: yerel ağa dönüktür, yalnız
 * `lanHost` ile kaydedilmiş oturumlara GET/HEAD/OPTIONS kabul eder, vekil işi yapmaz ve
 * yanıtlara alıcının oynatıcısının istediği CORS başlıklarını ekler. [stopLan] kapıyı kapatır.
 *
 * **SSRF savunması:** her oturum yalnız kayıt adresinin ana bilgisayarına ve oynatma listelerinin
 * başvurduğu ana bilgisayarlara istek yaptırabilir; kayıt adresi yerel/özel değilse yerel/özel
 * hedefler ([HostPolicy]) listelerde geçse de aktarılmaz. Ağ kapısında ayrıca her bağlantı adımında
 * (yönlendirme, DNS yanıtı) özel adrese çıkış kesilir; böylece kapı açık bir vekile dönüşmez.
 *
 * @param client upstream istekleri için kullanılan istemci (her istekte yeniden sorulur)
 * @param tunnel ham TCP tüneli açıcı (genellikle `Network::openTunnel`)
 */
class StreamRelay(
    private val client: () -> OkHttpClient,
    private val tunnel: (host: String, port: Int) -> Socket,
) : AutoCloseable {

    /**
     * @param origin kaydedilen ilk adres (ana playlist). Bazı CDN'ler segmentlerde eklentinin verdiği
     * Referer'ı değil, oynatıcı sayfasının adresini bekler; tarayıcıdaki oynatıcı da öyle gönderir.
     */
    private class Session(
        val headers: Map<String, String>,
        val referer: String?,
        val origin: String,
        val interceptor: Interceptor?,
        /** Yeniden yazılan bağlantıların kökü (`http://127.0.0.1:P` ya da ağ adresi). */
        val base: String,
        /** Yerel ağ kapısından erişilebilir mi. */
        val lan: Boolean,
        /**
         * Bu oturumun ulaşabileceği upstream ana bilgisayarları: kayıt adresinin ana bilgisayarı ile
         * oynatma listelerinin (yeniden yazılırken) başvurduğu ana bilgisayarlar. Oturum kimliğini bilen
         * biri bile bunların dışına istek yaptıramaz (SSRF).
         */
        val allowedHosts: MutableSet<String>,
        /** Kayıt adresi yerel/özel bir hedefse (ör. ev ağındaki bir sunucu) özel adreslere de izin verilir. */
        val allowPrivate: Boolean,
    ) {
        /** 401/403 sonrası Referer olarak [origin] ile başarı alındıysa oturumun geri kalanında da kullanılır. */
        @Volatile var useOriginReferer = false
    }

    private val sessions: MutableMap<String, Session> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Session>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?) = size > MAX_SESSIONS
        },
    )
    private val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    /** Yerel ağ kapısından gelen bağlantılar; kapı kapanırken bunlar da kesilir. */
    private val lanSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val random = SecureRandom()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var lanServer: ServerSocket? = null

    @Volatile
    private var closed = false

    /** Dinlenen yerel port; [start] çağrılmadan okunamaz. */
    val port: Int
        get() = server?.localPort ?: throw IllegalStateException("Aktarıcı başlatılmadı (start çağrılmalı)")

    /** Tarayıcının CONNECT vekili olarak kullanacağı adres. */
    val proxyAddress: String get() = "127.0.0.1:$port"

    /** Sunucuyu başlatır; [port] 0 ise boş bir port seçilir. */
    @Synchronized
    fun start(port: Int = 0) {
        check(server == null) { "Aktarıcı zaten başlatıldı" }
        check(!closed) { "Aktarıcı kapatılmış" }
        val socket = ServerSocket(port, 128, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        server = socket
        Thread.ofVirtual().name("relay-accept").start { acceptLoop(socket) }
        SeyirLog.i(TAG, "Aktarıcı başlatıldı: 127.0.0.1:${socket.localPort}")
    }

    /**
     * Yerel ağ kapısını açar (açıksa dokunmaz) ve portunu döndürür. Windows'ta ilk açılışta
     * güvenlik duvarı izin sorabilir; televizyon videoyu bu kapıdan çeker.
     */
    @Synchronized
    fun startLan(): Int {
        lanServer?.let { return it.localPort }
        check(server != null && !closed) { "Aktarıcı başlatılmadı" }
        val socket = ServerSocket(0, 128)
        lanServer = socket
        Thread.ofVirtual().name("relay-lan-accept").start { acceptLoop(socket, lan = true) }
        SeyirLog.i(TAG, "Yerel ağ kapısı açıldı: *:${socket.localPort}")
        return socket.localPort
    }

    /**
     * Yerel ağ kapısını kapatır: dinleme durur, açık bağlantılar kesilir ve ağ oturumları silinir.
     * Televizyona gönderim bittiğinde (durdurma, bitiş, hata) çağrılmalıdır; kapı açık kalırsa ağdaki
     * herkese bir dinleme noktası sunulmuş olur. Kapı açık değilse bir şey yapmaz; sonradan [startLan]
     * (ya da `lanHost` ile kayıt) kapıyı yeniden açar.
     */
    @Synchronized
    fun stopLan() {
        val socket = lanServer ?: return
        lanServer = null
        runCatching { socket.close() }
        lanSockets.toList().forEach { runCatching { it.close() } }
        lanSockets.clear()
        synchronized(sessions) { sessions.values.removeAll { it.lan } }
        SeyirLog.i(TAG, "Yerel ağ kapısı kapatıldı")
    }

    /** Yerel ağ kapısı şu an açık mı. */
    val lanOpen: Boolean get() = lanServer != null

    /**
     * Oynatıcının açacağı yerel URL'yi döndürür.
     *
     * @param interceptor eklentinin `MainAPI.getVideoInterceptor` ile verdiği yakalayıcı; Android'deki
     * oynatıcı gibi bu oturumun bütün istekleri (playlist, segment, anahtar) bundan geçer.
     * @param lanHost verilirse adres bu bilgisayarın ağ adresiyle ve yerel ağ kapısının portuyla
     * kurulur ([startLan] kendiliğinden çağrılır); televizyon gibi başka bir cihaz açabilir.
     */
    fun register(
        url: String,
        kind: StreamKind,
        headers: Map<String, String>,
        referer: String?,
        interceptor: Interceptor? = null,
        lanHost: String? = null,
    ): String {
        val upstream = requireHttpUrl(url)
        val sid = newSession(headers, referer, upstream.toString(), interceptor, lanHost)
        val name = when (kind) {
            StreamKind.HLS -> "index.m3u8"
            StreamKind.DASH -> "manifest.mpd"
            StreamKind.FILE -> "video." + PlaylistRewriter.urlExtension(upstream).takeIf { it in VIDEO_EXTENSIONS }.orEmpty().ifEmpty { "mp4" }
        }
        return localUrl(sid, upstream, name)
    }

    /**
     * Altyazı dosyası için yerel URL döndürür (uzantı korunur, yoksa `.vtt`). [lanHost] verilirse
     * adres her zaman `.vtt` olur ve SRT içerik WebVTT'ye çevrilir; Cast alıcıları yalnız onu okur.
     */
    fun registerSubtitle(url: String, headers: Map<String, String>, referer: String?, lanHost: String? = null): String {
        val upstream = requireHttpUrl(url)
        val sid = newSession(headers, referer, upstream.toString(), lanHost = lanHost)
        val ext = if (lanHost != null) "vtt" else PlaylistRewriter.urlExtension(upstream).takeIf { it in SUBTITLE_EXTENSIONS } ?: "vtt"
        return localUrl(sid, upstream, "sub.$ext")
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        runCatching { lanServer?.close() }
        // Bağlantılar kapanırken kendilerini kümeden siler; eşzamanlı küme doğrudan dolaşılır (toList boyutu önceden
        // okuduğu için arada silinen öğede NoSuchElementException verir)
        for (socket in sockets) runCatching { socket.close() }
        sockets.clear()
        sessions.clear()
    }

    // ---- oturum ve URL ----

    private fun requireHttpUrl(url: String): HttpUrl =
        url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Geçersiz http(s) adresi: $url")

    private fun newSession(
        headers: Map<String, String>,
        referer: String?,
        origin: String,
        interceptor: Interceptor? = null,
        lanHost: String? = null,
    ): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        // Ağdan erişilen oturumların kimliği tahmin edilemeyecek kadar uzun tutulur
        val sid = String(CharArray(if (lanHost != null) 24 else 8) { alphabet[random.nextInt(alphabet.length)] })
        val base = if (lanHost != null) {
            val host = if (':' in lanHost) "[$lanHost]" else lanHost
            "http://$host:${startLan()}"
        } else {
            "http://127.0.0.1:$port"
        }
        val originHost = origin.toHttpUrlOrNull()?.host.orEmpty()
        sessions[sid] = Session(
            headers.toMap(), referer?.takeIf { it.isNotBlank() }, origin, interceptor, base, lanHost != null,
            allowedHosts = ConcurrentHashMap.newKeySet<String>().apply { add(originHost) },
            allowPrivate = HostPolicy.isLocalHost(originHost),
        )
        return sid
    }

    private fun localUrl(sid: String, upstream: HttpUrl, name: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(upstream.toString().toByteArray())
        val base = sessions[sid]?.base ?: "http://127.0.0.1:$port"
        return "$base/r/$sid/$encoded/$name"
    }

    /**
     * Oynatma listesinin başvurduğu ana bilgisayarı oturuma ekler. Kayıt adresi yerel değilken yerel/özel
     * bir hedefe işaret eden başvurular eklenmez (listeyi yazan sunucu aktarıcıyı içeri yönlendiremesin);
     * o istek [handleRelay] içinde 403 alır.
     */
    private fun allowHost(sid: String, url: HttpUrl) {
        val session = sessions[sid] ?: return
        if (!session.allowPrivate && HostPolicy.isLocalHost(url.host)) return
        if (session.allowedHosts.size < MAX_HOSTS_PER_SESSION) session.allowedHosts.add(url.host)
    }

    private fun rewriterFor(sid: String) = PlaylistRewriter { url, role, ext ->
        allowHost(sid, url)
        val base = when (role) {
            LinkRole.PLAYLIST -> "index"
            LinkRole.SEGMENT -> "seg"
            LinkRole.KEY -> "key"
            LinkRole.INIT -> "init"
        }
        localUrl(sid, url, "$base.$ext")
    }

    // ---- sunucu döngüsü ----

    private fun acceptLoop(server: ServerSocket, lan: Boolean = false) {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                break
            } catch (e: IOException) {
                SeyirLog.w(TAG, "Bağlantı kabul edilemedi: ${e.message}")
                break
            }
            sockets += socket
            if (lan) lanSockets += socket
            Thread.ofVirtual().name("relay-conn").start { serve(socket, lan) }
        }
    }

    private fun serve(socket: Socket, lan: Boolean) {
        try {
            socket.use {
                it.tcpNoDelay = true
                it.soTimeout = IDLE_TIMEOUT_MS
                val input = BufferedInputStream(it.getInputStream(), 16 * 1024)
                val out = BufferedOutputStream(it.getOutputStream(), 64 * 1024)
                while (!closed) {
                    val request = try {
                        HttpWire.readRequest(input)
                    } catch (e: BadRequestException) {
                        simple(out, 400, "Geçersiz istek: ${e.message}", false, lan)
                        break
                    } ?: break
                    // Ağ kapısı yalnız ağ oturumlarını aktarır; vekil ve tünel yalnız bu bilgisayara açık
                    val keepAlive = if (lan) when {
                        request.method == "OPTIONS" -> preflight(request, input, out)
                        request.target.startsWith("/r/") -> handleRelay(request, input, out, lan = true)
                        else -> simple(out, 403, "Yasak", false, cors = true)
                    } else when {
                        request.method == "CONNECT" -> {
                            handleConnect(request, socket, input, out)
                            false
                        }
                        request.target.startsWith("/r/") -> handleRelay(request, input, out)
                        request.target.startsWith("http://") -> handleForward(request, input, out)
                        else -> simple(out, 404, "Bulunamadı", request.keepAlive)
                    }
                    if (!keepAlive) break
                }
            }
        } catch (e: IOException) {
            // İstemci bağlantıyı kopardı veya zaman aşımı; sessizce kapat
            SeyirLog.d(TAG, "Bağlantı sonlandı: ${e.message}")
        } catch (e: Exception) {
            SeyirLog.w(TAG, "İstek işlenirken beklenmeyen hata: $e")
        } finally {
            sockets -= socket
            lanSockets -= socket
        }
    }

    private fun simple(out: OutputStream, status: Int, text: String, keepAlive: Boolean, cors: Boolean = false): Boolean {
        val data = text.toByteArray()
        val headers = mutableListOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to data.size.toString(),
        )
        if (cors) headers += CORS_HEADERS
        if (!keepAlive) headers += "Connection" to "close"
        HttpWire.writeHead(out, status, HttpWire.reasonPhrase(status), headers)
        out.write(data)
        out.flush()
        return keepAlive
    }

    /** Alıcının oynatıcısı Range gibi başlıklarla istek atmadan önce izin sorar. */
    private fun preflight(request: HttpRequest, input: InputStream, out: OutputStream): Boolean {
        skipBody(request, input)
        val headers = CORS_HEADERS.toMutableList()
        headers += "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS"
        headers += "Access-Control-Allow-Headers" to (request.header("Access-Control-Request-Headers") ?: "Range, Content-Type")
        headers += "Access-Control-Max-Age" to "600"
        headers += "Content-Length" to "0"
        if (!request.keepAlive) headers += "Connection" to "close"
        HttpWire.writeHead(out, 204, "No Content", headers)
        out.flush()
        return request.keepAlive
    }

    // ---- CONNECT tüneli ----

    private fun handleConnect(request: HttpRequest, client: Socket, input: InputStream, out: OutputStream) {
        val target = request.target
        val colon = target.lastIndexOf(':')
        val port = target.substring(colon + 1).toIntOrNull()
        if (colon <= 0 || port == null || port !in 1..65535) {
            simple(out, 400, "Geçersiz CONNECT hedefi: $target", false)
            return
        }
        val host = target.substring(0, colon).removePrefix("[").removeSuffix("]")
        val remote = try {
            tunnel(host, port)
        } catch (e: Exception) {
            SeyirLog.d(TAG, "Tünel açılamadı $target: ${e.message}")
            simple(out, 502, "Tünel açılamadı: ${e.message}", false)
            return
        }
        sockets += remote
        try {
            remote.tcpNoDelay = true
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            client.soTimeout = 0
            // Biri kapanınca ikisi de kapanır
            val upstreamPump = Thread.ofVirtual().name("relay-tunnel-up").start {
                pump(input, remote.getOutputStream(), client, remote)
            }
            pump(remote.getInputStream(), out, client, remote)
            upstreamPump.join()
        } finally {
            runCatching { remote.close() }
            sockets -= remote
        }
    }

    private fun pump(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (e: IOException) {
            // Karşı taraf kapattı veya sıfırladı
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    // ---- oturumlu aktarma (/r/...) ----

    private fun handleRelay(request: HttpRequest, input: InputStream, out: OutputStream, lan: Boolean = false): Boolean {
        val keepAlive = request.keepAlive
        if (request.method != "GET" && request.method != "HEAD") {
            return simple(out, 405, "Yalnız GET ve HEAD desteklenir", keepAlive, lan)
        }
        skipBody(request, input)
        // DNS rebinding'e karşı: yalnız yerel ad/adres kabul edilir. Ağ kapısında Host bu
        // bilgisayarın ağ adresidir; orada koruma, oturum kimliğinin uzunluğu ve ağ oturumu şartıdır.
        val hostName = request.header("Host")?.substringBefore(':')?.lowercase()
        if (!lan && hostName != null && hostName != "127.0.0.1" && hostName != "localhost") {
            return simple(out, 403, "Yasak", keepAlive)
        }
        val parts = request.target.substringBefore('?').split('/')
        if (parts.size != 5 || parts[1] != "r") return simple(out, 404, "Bulunamadı", keepAlive, lan)
        val sid = parts[2]
        val session = sessions[sid]?.takeIf { !lan || it.lan }
            ?: return simple(out, 404, "Oturum bulunamadı veya süresi doldu", keepAlive, lan)
        val upstream = runCatching { String(Base64.getUrlDecoder().decode(parts[3])).toHttpUrlOrNull() }.getOrNull()
            ?: return simple(out, 400, "Geçersiz kaynak adresi", keepAlive, lan)
        // SSRF: oturum yalnız kendi kayıt adresinin ve oynatma listelerinin başvurduğu ana bilgisayarlara açılır;
        // kayıt adresi yerel değilse yerel/özel hedefler hiçbir koşulda aktarılmaz
        if (upstream.host !in session.allowedHosts || (!session.allowPrivate && HostPolicy.isLocalHost(upstream.host))) {
            SeyirLog.w(TAG, "Oturum dışı adres reddedildi: ${upstream.host}")
            return simple(out, 403, "Bu adres oturuma ait değil", keepAlive, lan)
        }
        val ext = parts[4].substringAfterLast('.', "").lowercase()
        // Ağ oturumunda TS parçasının başındaki sahte resim başlığı atılır, altyazı WebVTT'ye
        // çevrilir; ikisi de bütün gövdeyi ister, kısmi istek iletilmez
        val wholeBody = ext == "m3u8" || (lan && ext == "ts") || ext in SUBTITLE_EXTENSIONS

        fun build(originReferer: Boolean): Request {
            val builder = UpstreamRequest.builder(
                upstream, session.headers, session.referer, session.origin, originReferer, request.header("Accept"),
            )
            // Oynatma listeleri yeniden yazıldığı için kısmi istenemez
            if (!wholeBody) {
                request.header("Range")?.let { builder.header("Range", it) }
                request.header("If-Range")?.let { builder.header("If-Range", it) }
            }
            return builder.build()
        }

        val startedAt = System.nanoTime()
        // Ağ kapısında yönlendirmeler ve DNS yanıtları da özel adreslere çıkamaz
        val guarded = session.lan && !session.allowPrivate
        val http = if (session.interceptor == null && !guarded) {
            client()
        } else {
            client().newBuilder().apply {
                session.interceptor?.let { addInterceptor(it) }
                if (guarded) addNetworkInterceptor(privateAddressGuard)
            }.build()
        }
        val response = try {
            val first = http.newCall(build(session.useOriginReferer)).execute()
            val currentReferer = session.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: session.referer
            if (first.code in 401..403 && !session.useOriginReferer && currentReferer != session.origin) {
                first.close()
                val retry = http.newCall(build(originReferer = true)).execute()
                if (retry.isSuccessful) {
                    session.useOriginReferer = true
                    SeyirLog.i(TAG, "Referer ${first.code} verdi; oturum $sid için kaynak adresi Referer yapıldı")
                }
                retry
            } else {
                first
            }
        } catch (e: Exception) {
            SeyirLog.d(TAG, "Upstream hatası $upstream: ${e.message}")
            return simple(out, 502, "Kaynağa ulaşılamadı: ${e.message}", keepAlive, lan)
        }
        if (SeyirLog.isEnabled(3)) {
            SeyirLog.d(TAG, "Upstream ${response.code} ${(System.nanoTime() - startedAt) / 1_000_000} ms $upstream")
        }
        // İstemci kopsa da hata fırlasa da upstream yanıtı kapanır
        return response.use { relayResponse(request, out, sid, ext, it, keepAlive, lan) }
    }

    /**
     * Ağ kapısı oturumlarında her bağlantı adımında (yönlendirmeler dahil) çalışır: istek adresi özel bir
     * sabit IP ise ya da doğrudan bağlantıda soket özel bir adrese gittiyse isteği keser. Böylece genel bir
     * ad özel bir adrese çözülse (DNS rebinding) ya da upstream özel adrese yönlendirse de içeri çıkılmaz.
     */
    private val privateAddressGuard = Interceptor { chain ->
        val host = chain.request().url.host
        if (HostPolicy.literal(host)?.let(HostPolicy::isPrivate) == true) throw IOException("Özel adrese aktarma engellendi: $host")
        val route = chain.connection()?.route()
        if (route != null && route.proxy.type() == java.net.Proxy.Type.DIRECT && HostPolicy.isPrivate(route.socketAddress.address)) {
            throw IOException("Özel adrese aktarma engellendi: $host")
        }
        chain.proceed(chain.request())
    }

    private fun relayResponse(
        request: HttpRequest,
        out: OutputStream,
        sid: String,
        ext: String,
        response: Response,
        keepAliveIn: Boolean,
        lan: Boolean,
    ): Boolean {
        var keepAlive = keepAliveIn
        val body = response.body
        val source = body.source()
        val code = response.code
        val isHead = request.method == "HEAD"
        val reason = response.message.ifBlank { HttpWire.reasonPhrase(code) }
        val contentRange = response.header("Content-Range")
        // Aralık ortasından başlayan yanıtlarda koklama yanıltır
        val sniffable = code == 200 || (code == 206 && contentRange?.startsWith("bytes 0-") == true)

        var head = ByteString.EMPTY
        if (sniffable) {
            try {
                source.request(SNIFF_BYTES.toLong())
                head = source.buffer.snapshot(minOf(SNIFF_BYTES.toLong(), source.buffer.size).toInt())
            } catch (e: IOException) {
                return simple(out, 502, "Kaynak okunamadı: ${e.message}", false, lan)
            }
        }

        if (sniffable && isPlaylist(head) && body.contentLength() <= MAX_PLAYLIST_BYTES) {
            val text = try {
                readLimited(source)
            } catch (e: IOException) {
                return simple(out, 502, "Oynatma listesi okunamadı: ${e.message}", false, lan)
            }
            val data = rewriterFor(sid).rewrite(text, response.request.url).toByteArray()
            return writeWhole(out, data, "application/vnd.apple.mpegurl", isHead, keepAlive, lan)
        }

        // Altyazılar (Türkçe olanlar sık sık Windows-1254 gelir) UTF-8'e çevrilir; mpv ve Cast alıcısı
        // yalnız UTF-8'i doğru gösterir. Çok büyük gövdeler (altyazı olamaz) olduğu gibi geçer.
        if (ext in SUBTITLE_EXTENSIONS && code == 200 && body.contentLength() <= MAX_SUBTITLE_BYTES) {
            val raw = try {
                readBytesLimited(source, MAX_SUBTITLE_BYTES)
            } catch (e: IOException) {
                return simple(out, 502, "Altyazı okunamadı: ${e.message}", false, lan)
            }
            if (lan && ext == "vtt") {
                return writeWhole(out, toWebVtt(SubtitleEncoding.decode(raw)).toByteArray(), "text/vtt; charset=utf-8", isHead, keepAlive, lan)
            }
            val type = contentType(ext, response.header("Content-Type"), head, sniffable)
                ?.substringBefore(';')?.trim()?.takeIf { it.startsWith("text/") || it.startsWith("application/x-subrip") }
                ?: "text/plain"
            return writeWhole(out, SubtitleEncoding.toUtf8(raw), "$type; charset=utf-8", isHead, keepAlive, lan)
        }

        // Bazı sunucular TS parçasının başına sahte resim başlığı koyar; mpv bunu atlar, Cast alıcısı atlamaz
        var skip = 0L
        if (lan && ext == "ts" && code == 200 && head.size > 0 && head[0] != TS_SYNC) {
            skip = try {
                source.request(TS_SCAN_BYTES)
                tsStart(source.buffer.snapshot(minOf(TS_SCAN_BYTES, source.buffer.size).toInt()))
            } catch (e: IOException) {
                return simple(out, 502, "Kaynak okunamadı: ${e.message}", false, lan)
            }
            if (skip > 0) source.skip(skip)
        }

        val length = body.contentLength().let { if (it >= 0) it - skip else it }
        val noBody = isHead || code == 204 || code == 304 || code in 100..199
        val chunked = !noBody && length < 0 && request.isHttp11
        if (!noBody && length < 0 && !chunked) keepAlive = false

        val headers = mutableListOf<Pair<String, String>>()
        if (lan) headers += CORS_HEADERS
        contentType(ext, response.header("Content-Type"), if (skip > 0) ByteString.of(TS_SYNC) else head, sniffable)?.let { headers += "Content-Type" to it }
        for (name in PASS_HEADERS) response.header(name)?.let { headers += name to it }
        if (length >= 0) headers += "Content-Length" to length.toString()
        if (chunked) headers += "Transfer-Encoding" to "chunked"
        if (!keepAlive) headers += "Connection" to "close"
        HttpWire.writeHead(out, code, reason, headers)
        if (noBody) {
            out.flush()
            return keepAlive
        }
        HttpWire.copyBody(source.inputStream(), out, length, chunked)
        return keepAlive
    }

    private fun writeWhole(out: OutputStream, data: ByteArray, type: String, isHead: Boolean, keepAlive: Boolean, cors: Boolean): Boolean {
        val headers = mutableListOf(
            "Content-Type" to type,
            "Content-Length" to data.size.toString(),
            "Cache-Control" to "no-store",
        )
        if (cors) headers += CORS_HEADERS
        if (!keepAlive) headers += "Connection" to "close"
        HttpWire.writeHead(out, 200, "OK", headers)
        if (!isHead) out.write(data)
        out.flush()
        return keepAlive
    }

    private fun isPlaylist(head: ByteString): Boolean {
        val bytes = head.toByteArray()
        val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val marker = "#EXTM3U".toByteArray()
        if (bytes.size - offset < marker.size) return false
        return marker.indices.all { bytes[offset + it] == marker[it] }
    }

    private fun readLimited(source: okio.BufferedSource): String {
        val buffer = Buffer()
        while (source.read(buffer, 8192) != -1L) {
            if (buffer.size > MAX_PLAYLIST_BYTES) throw IOException("Oynatma listesi çok büyük")
        }
        return buffer.readUtf8()
    }

    private fun readBytesLimited(source: okio.BufferedSource, limit: Long): ByteArray {
        val buffer = Buffer()
        while (source.read(buffer, 8192) != -1L) {
            if (buffer.size > limit) throw IOException("Altyazı çok büyük")
        }
        return buffer.readByteArray()
    }

    private fun contentType(ext: String, upstream: String?, head: ByteString, sniffable: Boolean): String? {
        if (ext == "ts" && sniffable && head.size > 0 && head[0] == 0x47.toByte()) return "video/mp2t"
        val byExtension = when (ext) {
            "ts" -> "video/mp2t"
            "m4s", "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "key" -> "application/octet-stream"
            "vtt" -> "text/vtt; charset=utf-8"
            "srt" -> "application/x-subrip; charset=utf-8"
            "mpd" -> "application/dash+xml"
            else -> null
        }
        // Segmentler genellikle `image/png` gibi yanlış türle gelir; uzantıya göre düzelt
        val misleading = upstream == null || upstream.startsWith("image/") || upstream.startsWith("text/html") ||
            upstream.startsWith("application/octet-stream")
        return if (byExtension != null && (misleading || ext in OVERRIDE_EXTENSIONS)) byExtension else upstream ?: byExtension
    }

    private fun skipBody(request: HttpRequest, input: InputStream) {
        val length = request.contentLength
        if (length > 0) input.skipNBytes(length)
    }

    // ---- mutlak URI'li düz HTTP vekil isteği ----

    private fun handleForward(request: HttpRequest, input: InputStream, out: OutputStream): Boolean {
        var keepAlive = request.keepAlive
        val url = request.target.toHttpUrlOrNull() ?: return simple(out, 400, "Geçersiz adres", false)
        if (request.header("Transfer-Encoding") != null) return simple(out, 411, "Parçalı istek gövdesi desteklenmez", false)

        val method = request.method
        val length = request.contentLength
        val bodyBytes = if (length > 0) {
            if (length > MAX_REQUEST_BODY_BYTES) return simple(out, 413, "İstek gövdesi çok büyük", false)
            input.readNBytes(length.toInt())
        } else {
            null
        }
        val contentType = request.header("Content-Type")?.toMediaTypeOrNull()
        val requestBody = when {
            method == "GET" || method == "HEAD" -> null
            bodyBytes != null -> bodyBytes.toRequestBody(contentType)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }
        val builder = Request.Builder().url(url).method(method, requestBody)
        for ((name, value) in request.headers) {
            if (name.lowercase() in HOP_BY_HOP || name.equals("Content-Length", true) ||
                (requestBody != null && contentType != null && name.equals("Content-Type", true))
            ) continue
            builder.addHeader(name, value)
        }

        // Tarayıcı yönlendirmeleri kendisi izler
        val upstreamClient = client().newBuilder().followRedirects(false).followSslRedirects(false).build()
        val response = try {
            upstreamClient.newCall(builder.build()).execute()
        } catch (e: Exception) {
            SeyirLog.d(TAG, "Vekil upstream hatası $url: ${e.message}")
            return simple(out, 502, "Kaynağa ulaşılamadı: ${e.message}", keepAlive)
        }
        return response.use {
            val code = it.code
            val body = it.body
            val bodyLength = body.contentLength()
            val noBody = method == "HEAD" || code == 204 || code == 304 || code in 100..199
            val chunked = !noBody && bodyLength < 0 && request.isHttp11
            if (!noBody && bodyLength < 0 && !chunked) keepAlive = false

            val headers = mutableListOf<Pair<String, String>>()
            for (i in 0 until it.headers.size) {
                val name = it.headers.name(i)
                if (name.lowercase() in HOP_BY_HOP || name.equals("Content-Length", true)) continue
                headers += name to it.headers.value(i)
            }
            if (bodyLength >= 0) headers += "Content-Length" to bodyLength.toString()
            if (chunked) headers += "Transfer-Encoding" to "chunked"
            if (!keepAlive) headers += "Connection" to "close"
            HttpWire.writeHead(out, code, it.message.ifBlank { HttpWire.reasonPhrase(code) }, headers)
            if (noBody) {
                out.flush()
            } else {
                HttpWire.copyBody(body.source().inputStream(), out, bodyLength, chunked)
            }
            keepAlive
        }
    }

    internal companion object {
        /** TS paketinin başlangıcı: 188 bayt arayla üç senkron baytının görüldüğü ilk konum (yoksa 0). */
        fun tsStart(data: ByteString): Long {
            for (i in 0 until data.size - 2 * TS_PACKET) {
                if (data[i] == TS_SYNC && data[i + TS_PACKET] == TS_SYNC && data[i + 2 * TS_PACKET] == TS_SYNC) return i.toLong()
            }
            return 0
        }

        /** SRT'yi WebVTT'ye çevirir; zaten WebVTT ise dokunmaz. */
        fun toWebVtt(text: String): String {
            val body = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
            if (body.trimStart().startsWith("WEBVTT")) return body
            return "WEBVTT\n\n" + SRT_TIME.replace(body) { "${it.groupValues[1]}.${it.groupValues[2]}" }
        }

        private val SRT_TIME = Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{3})""")
        const val TS_SYNC: Byte = 0x47
        const val TS_PACKET = 188
        const val TS_SCAN_BYTES = 64L * 1024
        val CORS_HEADERS = listOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges, Content-Type",
        )
        const val TAG = "StreamRelay"
        const val MAX_SESSIONS = 256
        const val MAX_HOSTS_PER_SESSION = 64
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000
        const val SNIFF_BYTES = 16
        const val MAX_PLAYLIST_BYTES = 32L * 1024 * 1024
        const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024
        const val MAX_REQUEST_BODY_BYTES = 16L * 1024 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "flv")
        val SUBTITLE_EXTENSIONS = setOf("vtt", "srt", "ass", "ssa")
        val PASS_HEADERS = listOf("Content-Range", "Accept-Ranges", "ETag", "Last-Modified")
        val OVERRIDE_EXTENSIONS = setOf("key", "vtt", "srt")
        val HOP_BY_HOP = setOf(
            "connection", "keep-alive", "proxy-connection", "proxy-authorization", "proxy-authenticate",
            "te", "trailer", "transfer-encoding", "upgrade", "host",
        )
    }
}
