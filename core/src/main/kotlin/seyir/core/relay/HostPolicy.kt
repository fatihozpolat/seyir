package seyir.core.relay

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Aktarıcının SSRF savunması için adres sınıflandırması. "Özel" sayılanlar: döngü (loopback),
 * bağlantı-yerel (link-local), yerel ağ (RFC 1918, ULA `fc00::/7`), CGNAT (`100.64.0.0/10`),
 * belirsiz (`0.0.0.0`, `::`) ve çok noktaya yayın adresleri.
 */
internal object HostPolicy {
    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** [address] özel/yerel bir adres mi? */
    fun isPrivate(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address
        return when (address) {
            // 100.64.0.0/10 (CGNAT, Tailscale vb.)
            is Inet4Address -> (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
            // fc00::/7 (benzersiz yerel adres)
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    /** [host] bir IP sabit değeriyse (DNS gerektirmeden) çözümlenmiş adresi, değilse null. */
    fun literal(host: String): InetAddress? {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (!IPV4_LITERAL.matches(bare) && ':' !in bare) return null
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }

    /**
     * Ad ya da sabit adres olarak yerel olan bir hedef mi: özel IP sabit değeri, `localhost`,
     * `.local`/`.lan`/`.home.arpa` ile biten veya noktasız (tek etiketli) ad. Adın çözümlenmiş
     * adresine DNS ile bakılmaz; o denetim bağlantı anında ayrıca yapılır.
     */
    fun isLocalHost(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        literal(name)?.let { return isPrivate(it) }
        return name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") ||
            name.endsWith(".lan") || name.endsWith(".home.arpa") || '.' !in name
    }
}
