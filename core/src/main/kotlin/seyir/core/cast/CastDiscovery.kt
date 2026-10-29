package seyir.core.cast

import seyir.core.SeyirLog
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/** Ağda bulunan bir Google Cast alıcısı (Chromecast, Google TV, Cast destekli TV). */
data class CastDevice(
    val name: String,
    val host: String,
    val port: Int = 8009,
    val model: String? = null,
    val id: String? = null,
)

/**
 * Cast alıcılarını mDNS ile bulur (`_googlecast._tcp.local`). Sorgu her IPv4 ağ arayüzünden
 * rastgele bir porttan gönderilir; RFC 6762'deki "legacy unicast" kuralı gereği alıcılar yanıtı
 * doğrudan bu porta yollar, böylece 5353'ü dinleyen başka bir programla çakışılmaz.
 */
object CastDiscovery {
    private const val SERVICE = "_googlecast._tcp.local"
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")

    /** [timeoutMs] boyunca gelen yanıtları toplar; aynı cihaz birden çok arayüzden gelse de bir kez döner. */
    fun scan(timeoutMs: Long = 3_000): List<CastDevice> {
        val found = ConcurrentHashMap<String, CastDevice>()
        val threads = localAddresses().map { address ->
            Thread.ofVirtual().name("cast-scan").start {
                runCatching { scanOn(address, timeoutMs) { found.putIfAbsent(it.id ?: it.host, it) } }
                    .onFailure { SeyirLog.d(TAG, "Arayüzde arama yapılamadı $address: ${it.message}") }
            }
        }
        threads.forEach { it.join(timeoutMs + 1_000) }
        return found.values.sortedBy { it.name.lowercase() }
    }

    private fun localAddresses(): List<Inet4Address> =
        NetworkInterface.networkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .flatMap { it.inetAddresses().toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLinkLocalAddress }

    private fun scanOn(address: Inet4Address, timeoutMs: Long, onDevice: (CastDevice) -> Unit) {
        DatagramSocket(InetSocketAddress(address, 0)).use { socket ->
            val query = buildQuery()
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buffer = ByteArray(9_000)
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) break
                // Kayıp paketlere karşı sorgu bir saniye arayla yinelenir
                if (now >= nextSend) {
                    socket.send(DatagramPacket(query, query.size, GROUP, 5353))
                    nextSend = now + 1_000
                }
                socket.soTimeout = (minOf(deadline, nextSend) - now).coerceAtLeast(1).toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                runCatching { parse(packet.data.copyOf(packet.length), packet.address) }
                    .onSuccess { devices -> devices.forEach(onDevice) }
                    .onFailure { SeyirLog.d(TAG, "Yanıt çözülemedi (${packet.address}): ${it.message}") }
            }
        }
    }

    internal fun buildQuery(): ByteArray {
        val out = ByteArrayOutputStream()
        // Başlık: kimlik 0, bayrak yok, 1 soru
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        SERVICE.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        out.write(byteArrayOf(0, 12, 0, 1)) // PTR, IN
        return out.toByteArray()
    }

    // ---- DNS yanıtı ----

    private class Reader(val data: ByteArray) {
        var pos = 0
        fun u8() = data[pos++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        /** Sıkıştırma işaretçilerini izleyerek bir alan adı okur. */
        fun name(): String {
            val labels = mutableListOf<String>()
            var cursor = pos
            var jumped = false
            var hops = 0
            while (true) {
                val len = data[cursor].toInt() and 0xFF
                when {
                    len == 0 -> {
                        cursor++
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        val target = ((len and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                        if (!jumped) pos = cursor + 2
                        jumped = true
                        cursor = target
                        check(++hops < 64) { "Ad işaretçisi döngüsü" }
                    }
                    else -> {
                        labels += String(data, cursor + 1, len, Charsets.UTF_8)
                        cursor += len + 1
                    }
                }
            }
            if (!jumped) pos = cursor
            return labels.joinToString(".")
        }
    }

    internal fun parse(data: ByteArray, sender: InetAddress): List<CastDevice> {
        val r = Reader(data)
        r.pos = 4
        val questions = r.u16()
        val records = r.u16() + r.u16() + r.u16()
        repeat(questions) {
            r.name()
            r.pos += 4
        }

        val instances = mutableSetOf<String>()
        val srv = mutableMapOf<String, Pair<String, Int>>()
        val txt = mutableMapOf<String, Map<String, String>>()
        val addresses = mutableMapOf<String, String>()
        repeat(records) {
            val name = r.name()
            val type = r.u16()
            r.u16() // sınıf
            r.u32() // TTL
            val length = r.u16()
            val end = r.pos + length
            when (type) {
                12 -> if (name.equals(SERVICE, ignoreCase = true)) instances += r.name()
                33 -> {
                    r.u16() // öncelik
                    r.u16() // ağırlık
                    val port = r.u16()
                    srv[name] = r.name() to port
                }
                16 -> {
                    val map = mutableMapOf<String, String>()
                    while (r.pos < end) {
                        val len = r.u8()
                        val entry = String(data, r.pos, len, Charsets.UTF_8)
                        r.pos += len
                        val eq = entry.indexOf('=')
                        if (eq > 0) map[entry.substring(0, eq)] = entry.substring(eq + 1)
                    }
                    txt[name] = map
                }
                1 -> if (length == 4) addresses[name] = data.copyOfRange(r.pos, r.pos + 4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            r.pos = end
        }

        // Bazı alıcılar PTR'yi göndermeden yalnız SRV/TXT'yi tekrar yollar
        val names = instances + srv.keys.filter { it.endsWith(SERVICE, ignoreCase = true) }
        return names.mapNotNull { instance ->
            val (target, port) = srv[instance] ?: return@mapNotNull null
            val info = txt[instance].orEmpty()
            CastDevice(
                name = info["fn"] ?: instance.substringBefore("._googlecast"),
                host = addresses[target] ?: sender.hostAddress,
                port = port,
                model = info["md"],
                id = info["id"],
            )
        }
    }

    private const val TAG = "CastDiscovery"
}
