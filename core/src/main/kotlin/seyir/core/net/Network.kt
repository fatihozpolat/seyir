package seyir.core.net

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import seyir.core.NoSolve
import seyir.core.SeyirLog
import java.net.InetAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Ağ ayarları. Varsayılanlar Türkiye'deki DNS ve SNI engelini aşacak şekilde seçildi. */
data class NetworkSettings(
    /** TLS ClientHello'yu parçalayarak SNI engelini aş. */
    val fragmentTls: Boolean = true,
    /** null ise sistem DNS'i kullanılır. */
    val dnsOverHttps: DohProvider? = DohProvider.CLOUDFLARE,
    /**
     * IP düzeyinde engellenen siteler için vekil sunucu: `socks5://127.0.0.1:40000` veya
     * `http://host:port`. Vekil kullanılırken parçalama ve DoH devre dışı kalır.
     */
    val proxy: String? = null,
)

enum class DohProvider(val url: String, val bootstrap: List<String>) {
    CLOUDFLARE("https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE("https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
    ADGUARD("https://dns-unfiltered.adguard.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
}

/**
 * Eklentilerin kullandığı ortak HTTP istemcilerini ([app], [insecureApp]) yapılandırır.
 * Uygulama açılışında, eklentiler yüklenmeden önce çağrılmalıdır.
 */
object Network {
    private const val TAG = "Network"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * Son kurulan ayarlar ve istemci; tünel açarken aynı ayarlar kullanılır. DoH çözümleyicisi ve
     * onun HTTP istemcisi durum başına bir tanedir; istemci ve tünel ikisini de paylaşır.
     */
    private class State(val settings: NetworkSettings) {
        val proxy: Proxy? = settings.proxy?.takeIf { it.isNotBlank() }?.let(::parseProxy)

        /** DoH sorgularını yapan istemci; durum bırakılırken kapatılır. Vekil varken kullanılmaz. */
        private val dohClient: OkHttpClient? =
            if (proxy == null) settings.dnsOverHttps?.let { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build() } else null

        /** Doğrudan bağlanırken kullanılan çözümleyici (DoH, başarısızsa sistem DNS'i). */
        val dns: Dns = AddressOrderDns(
            dohClient?.let { FallbackDns(doh(checkNotNull(settings.dnsOverHttps), it)) } ?: Dns.SYSTEM,
        )

        val client: OkHttpClient = buildClient(settings, dns)

        /** Bu duruma ait havuzları boşaltır ve iş parçacıklarını durdurur; sürmekte olan istekler biter. */
        fun close() {
            for (c in listOfNotNull(client, dohClient)) {
                runCatching {
                    c.connectionPool.evictAll()
                    c.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Volatile
    private var state: State? = null

    private fun currentState(): State =
        state ?: synchronized(this) {
            state ?: State(NetworkSettings()).also { state = it }
        }

    /** Son kurulan temel istemci (parçalama, DoH ve vekil ayarları dahil). Kurulum yoksa varsayılanlarla oluşur. */
    val client: OkHttpClient get() = currentState().client

    /**
     * [host]:[port] adresine ham bir TCP tüneli açar (CONNECT vekili ve oynatıcı aktarımı için).
     * Vekil yoksa ClientHello parçalamalı soket ve DoH ile çözülen adres, SOCKS vekil varsa vekil
     * üzerinden uzaktan çözümleme, HTTP vekil varsa CONNECT el sıkışması kullanılır.
     */
    fun openTunnel(host: String, port: Int): Socket {
        val st = currentState()
        val proxy = st.proxy
        return when (proxy?.type()) {
            Proxy.Type.SOCKS -> Socket(proxy).also { socket ->
                connectOrClose(socket) { it.connect(InetSocketAddress.createUnresolved(host, port), CONNECT_TIMEOUT_MS) }
            }
            Proxy.Type.HTTP -> Socket().also { socket ->
                connectOrClose(socket) {
                    val address = proxy.address() as InetSocketAddress
                    it.connect(InetSocketAddress(address.hostString, address.port), CONNECT_TIMEOUT_MS)
                    httpConnectHandshake(it, host, port)
                }
            }
            else -> openDirect(st, host, port)
        }
    }

    private inline fun connectOrClose(socket: Socket, block: (Socket) -> Unit) {
        try {
            block(socket)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun openDirect(st: State, host: String, port: Int): Socket {
        val addresses = if (isIpLiteral(host)) listOf(InetAddress.getByName(host)) else st.dns.lookup(host)
        var failure: IOException? = null
        for (address in addresses) {
            val socket = if (st.settings.fragmentTls) FragmentingSocketFactory().createSocket() else Socket()
            try {
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                failure = e
            }
        }
        throw failure ?: IOException("$host için adres bulunamadı")
    }

    private fun isIpLiteral(host: String): Boolean = ':' in host || IPV4_LITERAL.matches(host)

    /** HTTP vekile `CONNECT` gönderir; tünel verisini yutmamak için yanıtı bayt bayt okur. */
    private fun httpConnectHandshake(socket: Socket, host: String, port: Int) {
        val target = if (':' in host) "[$host]:$port" else "$host:$port"
        socket.getOutputStream().apply {
            write("CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        val input = socket.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("Vekil CONNECT yanıtı vermeden bağlantıyı kapattı")
            head.append(b.toChar())
            if (head.length > 16 * 1024) throw IOException("Vekil CONNECT yanıtı çok uzun")
        }
        val statusLine = head.lineSequence().first()
        if (statusLine.split(' ').getOrNull(1) != "200") throw IOException("Vekil tüneli reddetti: $statusLine")
    }

    fun install(settings: NetworkSettings = NetworkSettings()) {
        seyir.core.Compat.install()
        val previous = state
        val fresh = State(settings)
        val base = fresh.client
        state = fresh
        app.baseClient = base
        insecureApp.baseClient = base.newBuilder().ignoreAllSslErrors().build()
        // YouTube çıkarıcısı (NewPipe) için indirici; ayarlar değişince yeni istemciyi kullanır
        runCatching { org.schabi.newpipe.extractor.NewPipe.init(NewPipeDownloader { client }) }
            .onFailure { SeyirLog.w(TAG, "NewPipe başlatılamadı", it) }
        previous?.let(::retire)
        SeyirLog.i(TAG, "Ağ ayarlandı: parçalama=${settings.fragmentTls}, doh=${settings.dnsOverHttps}")
    }

    /** Önceki durumu kapatmadan önce bekleme süresi (ms); eldeki istekler (indirme, aktarım) bitebilsin diye. */
    @Volatile
    internal var retireDelayMs = 60_000L

    private val retirer by lazy {
        java.util.concurrent.ScheduledThreadPoolExecutor(1) { Thread(it, "seyir-network-retire").apply { isDaemon = true } }
    }

    private fun retire(old: State) {
        val delay = retireDelayMs
        if (delay <= 0) old.close() else retirer.schedule({ old.close() }, delay, TimeUnit.MILLISECONDS)
    }

    /** Verilen ayarlarla bağımsız bir istemci kurar (kendi DNS çözümleyicisiyle); uygulama [install] kullanır. */
    fun buildClient(settings: NetworkSettings): OkHttpClient = State(settings).client

    private fun buildClient(settings: NetworkSettings, dns: Dns): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // Bütün eklentiler için ortak Cloudflare çözümü: eklenti kendi yakalayıcısını
            // eklemese de 403/503 sınama sayfası tarayıcıyla çözülür (çözücü atanmışsa).
            .addInterceptor(CloudflareKiller.global)
            // newCall anında çağrıyı "çözme" bayrağıyla işaretler (bkz. NoSolve); enqueue başka iş parçacığında yürür
            .eventListenerFactory(NoSolve.listenerFactory)

        val proxy = settings.proxy?.takeIf { it.isNotBlank() }?.let(::parseProxy)
        if (proxy != null) {
            builder.proxy(proxy)
        } else {
            if (settings.fragmentTls) {
                builder.socketFactory(FragmentingSocketFactory())
                // Bölünmüş ClientHello'yu reddeden sunuculara bölmeden yeniden bağlanır
                builder.interceptors().add(0, SplitFallbackInterceptor())
            }
            builder.dns(dns)
        }
        return builder.build()
    }

    private fun parseProxy(value: String): Proxy {
        val uri = URI(if ("://" in value) value else "http://$value")
        val type = when (uri.scheme.lowercase()) {
            "socks", "socks5", "socks4" -> Proxy.Type.SOCKS
            "http", "https" -> Proxy.Type.HTTP
            else -> throw IllegalArgumentException("Desteklenmeyen vekil türü: ${uri.scheme}")
        }
        return Proxy(type, InetSocketAddress.createUnresolved(uri.host, uri.port))
    }

    private fun doh(provider: DohProvider, client: OkHttpClient): Dns =
        DnsOverHttps.Builder()
            .client(client)
            .url(provider.url.toHttpUrl())
            .bootstrapDnsHosts(provider.bootstrap.map { InetAddress.getByName(it) })
            .build()

    /** DoH çalışmazsa (ör. ağ DoH'u engelliyorsa) sistem DNS'ine düşer. */
    private class FallbackDns(private val primary: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            runCatching { primary.lookup(hostname) }
                .getOrElse {
                    SeyirLog.d(TAG, "DoH başarısız ($hostname), sistem DNS'i deneniyor: ${it.message}")
                    Dns.SYSTEM.lookup(hostname)
                }
    }

    /**
     * Makinede IPv6 yolu yoksa (çoğu ev bağlantısı, Docker) DNS'in döndürdüğü IPv6 adresleri
     * "Network is unreachable" ile boşa gider; bu durumda ayıklanır, varsa IPv4 öne alınır.
     */
    internal class AddressOrderDns(private val delegate: Dns, private val ipv6: () -> Boolean = { ipv6Available }) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = delegate.lookup(hostname)
            val v4 = all.filterIsInstance<java.net.Inet4Address>()
            if (ipv6()) return v4 + all.filterNot { it is java.net.Inet4Address }
            return v4.ifEmpty { all }
        }
    }

    /** Genel bir IPv6 adresine yol var mı? (UDP connect paket göndermez, yalnız yönlendirmeye bakar) */
    internal val ipv6Available: Boolean by lazy {
        runCatching {
            java.net.DatagramSocket().use { it.connect(InetAddress.getByName("2606:4700:4700::1111"), 53) }
            true
        }.getOrDefault(false)
    }

    private fun OkHttpClient.Builder.ignoreAllSslErrors(): OkHttpClient.Builder {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        return sslSocketFactory(context.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
    }
}
