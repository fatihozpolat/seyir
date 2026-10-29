package seyir.core.net

import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * SNI'ya bakarak engelleyen DPI'ı aşan soket fabrikası. Bağlantının ilk yazımı TLS ClientHello ise
 * kayıt düzeyinde ikiye bölünür: alan adının ortasından iki ayrı TLS kaydı (TLS buna izin verir,
 * sunucu birleştirir; ilk kayda bakan DPI alan adını göremez). Bu yöntem çekirdeğin TCP
 * davranışından bağımsızdır. ClientHello değilse ya da tek yazımda tam gelmediyse eski yönteme
 * düşülür: ilk baytlar tek tek ayrı TCP segmentleri olarak yazılır.
 *
 * Bölünmüş ClientHello'yu reddeden sunucular da var (ör. dygvideo.dygdigital.com bağlantıyı
 * sıfırlıyor); bunlar [TlsSplitPolicy] ile işaretlenir ve onlara bölmeden yazılır.
 *
 * Ölçümler (Türkiye, 28.10.2026): TCP parçalamada Windows'ta en az ~20 ayrı segment gerekiyor,
 * ama Linux başlangıç tıkanıklık penceresi (10 segment) yüzünden fazlasını birleştiriyor, bu
 * yüzden Linux'ta işe yaramıyor. TLS kayıt bölme iki sistemde de 2 kayıtla çalıştı.
 */
class FragmentingSocketFactory(
    private val fragmentCount: Int = 64,
    private val fragmentSize: Int = 1,
) : SocketFactory() {
    override fun createSocket(): Socket = FragmentingSocket(fragmentCount, fragmentSize)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(java.net.InetSocketAddress(localHost, localPort))
            connect(java.net.InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(java.net.InetSocketAddress(localAddress, localPort))
            connect(java.net.InetSocketAddress(address, port))
        }
}

private class FragmentingSocket(
    private val fragmentCount: Int,
    private val fragmentSize: Int,
) : Socket() {
    private var output: OutputStream? = null

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        // Parçaların ayrı TCP segmentleri olarak gitmesi için Nagle kapatılır
        tcpNoDelay = true
        super.connect(endpoint, timeout)
    }

    @Synchronized
    override fun getOutputStream(): OutputStream =
        output ?: FragmentingOutputStream(super.getOutputStream(), fragmentCount, fragmentSize).also { output = it }
}

private class FragmentingOutputStream(
    out: OutputStream,
    private val fragmentCount: Int,
    private val fragmentSize: Int,
) : FilterOutputStream(out) {
    private var firstWrite = true

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!firstWrite) {
            out.write(b, off, len)
            return
        }
        firstWrite = false

        val serverName = TlsRecordSplitter.serverName(b, off, len)
        if (serverName != null && serverName in TlsSplitPolicy.unsplitHosts) {
            out.write(b, off, len)
            return
        }

        TlsRecordSplitter.split(b, off, len)?.let { records ->
            // Kayıtlar ayrı yazılır; ayrıca segment sınırı da kayıt sınırına denk gelir
            records.forEach { out.write(it); out.flush() }
            return
        }

        var pos = 0
        var sent = 0
        while (pos < len && sent < fragmentCount) {
            val n = minOf(fragmentSize, len - pos)
            out.write(b, off + pos, n)
            out.flush()
            pos += n
            sent++
        }
        if (pos < len) out.write(b, off + pos, len - pos)
        out.flush()
    }

    override fun write(b: Int) {
        firstWrite = false
        out.write(b)
    }
}
