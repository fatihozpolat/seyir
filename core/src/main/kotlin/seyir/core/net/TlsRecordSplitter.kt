package seyir.core.net

import java.io.ByteArrayOutputStream

/**
 * TLS ClientHello kaydını SNI alan adının ortasından iki kayda böler. Kayıt başlığı
 * (tür 0x16, sürüm, uzunluk) her parçaya kopyalanır; el sıkışma iletisinin kendisine dokunulmaz.
 */
internal object TlsRecordSplitter {
    private const val HANDSHAKE = 0x16
    private const val CLIENT_HELLO = 0x01
    private const val SERVER_NAME = 0x0000

    /** Bölünmüş kayıtlar ve (varsa) arkasından gelen ek baytlar; ClientHello değilse null. */
    fun split(b: ByteArray, off: Int, len: Int): List<ByteArray>? {
        if (len < 5 + 4 || (b[off].toInt() and 0xff) != HANDSHAKE) return null
        val recordLen = u16(b, off + 3)
        if (recordLen < 2 || 5 + recordLen > len) return null
        val payload = off + 5
        if ((b[payload].toInt() and 0xff) != CLIENT_HELLO) return null

        val cut = sniMiddle(b, payload, recordLen) ?: (recordLen / 2)
        if (cut <= 0 || cut >= recordLen) return null

        val first = record(b, off, payload, cut)
        val second = record(b, off, payload + cut, recordLen - cut)
        val rest = off + 5 + recordLen
        return if (rest < off + len) listOf(first, second, b.copyOfRange(rest, off + len)) else listOf(first, second)
    }

    /** SNI alan adının ortasının, kayıt yüküne göre konumu. */
    internal fun sniMiddle(b: ByteArray, payload: Int, payloadLen: Int): Int? =
        sniRange(b, payload, payloadLen)?.let { (start, length) -> start + length / 2 - payload }

    /** İlk yazım bir ClientHello ise içindeki SNI alan adı (küçük harf); değilse null. */
    fun serverName(b: ByteArray, off: Int, len: Int): String? {
        if (len < 5 + 4 || (b[off].toInt() and 0xff) != HANDSHAKE) return null
        val recordLen = minOf(u16(b, off + 3), len - 5)
        val payload = off + 5
        if ((b[payload].toInt() and 0xff) != CLIENT_HELLO) return null
        val (start, length) = sniRange(b, payload, recordLen) ?: return null
        if (start + length > off + len) return null
        return String(b, start, length, Charsets.US_ASCII).lowercase()
    }

    /** SNI alan adının dizideki başlangıcı ve uzunluğu. */
    private fun sniRange(b: ByteArray, payload: Int, payloadLen: Int): Pair<Int, Int>? = runCatching {
        val end = payload + payloadLen
        var p = payload + 4 // tür (1) + uzunluk (3)
        p += 2 + 32 // sürüm + random
        p += 1 + (b[p].toInt() and 0xff) // oturum kimliği
        p += 2 + u16(b, p) // şifre takımları
        p += 1 + (b[p].toInt() and 0xff) // sıkıştırma
        val extEnd = p + 2 + u16(b, p)
        p += 2
        while (p + 4 <= minOf(extEnd, end)) {
            val type = u16(b, p)
            val size = u16(b, p + 2)
            if (type == SERVER_NAME) {
                // liste uzunluğu (2) + ad türü (1) + ad uzunluğu (2) + ad
                return@runCatching (p + 4 + 5) to u16(b, p + 4 + 3)
            }
            p += 4 + size
        }
        null
    }.getOrNull()

    private fun record(b: ByteArray, header: Int, from: Int, length: Int): ByteArray =
        ByteArrayOutputStream(5 + length).apply {
            write(HANDSHAKE)
            write(b[header + 1].toInt())
            write(b[header + 2].toInt())
            write(length shr 8)
            write(length and 0xff)
            write(b, from, length)
        }.toByteArray()

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
}
