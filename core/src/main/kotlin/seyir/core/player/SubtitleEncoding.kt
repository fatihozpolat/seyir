package seyir.core.player

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Altyazı dosyalarının karakter kodlamasını sezer ve UTF-8'e çevirir.
 *
 * Türkçe altyazılar sık sık Windows-1254 (ya da ISO-8859-9) gelir; mpv bunları UTF-8 sanıp
 * "ý", "þ", "ð" gibi bozuk harfler gösterir. Kural basittir: geçerli UTF-8 (BOM'lu ya da BOM'suz)
 * olduğu gibi kalır, UTF-16 BOM'u varsa o okunur, geri kalan her şey Windows-1254 sayılır.
 * Windows-1254, ISO-8859-9'un üst kümesidir (Türkçe harfler iki kodlamada aynı baytlardadır).
 */
object SubtitleEncoding {
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** Türkçe için yedek kodlama; JDK'da yoksa (çok nadir) ISO-8859-9'a düşülür. */
    private val fallbackCharset: Charset =
        runCatching { Charset.forName("windows-1254") }.getOrElse { Charset.forName("ISO-8859-9") }

    /** [bytes] geçerli bir UTF-8 dizisi mi (BOM'a bakılmaz, ASCII de geçerlidir). */
    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** Sezilen kodlama; tanılama için de kullanılır. */
    fun detect(bytes: ByteArray): Charset = when {
        bytes.startsWith(utf16LeBom) -> Charsets.UTF_16LE
        bytes.startsWith(utf16BeBom) -> Charsets.UTF_16BE
        isValidUtf8(bytes) -> Charsets.UTF_8
        else -> fallbackCharset
    }

    /** Metne çevirir; baştaki BOM atılır. */
    fun decode(bytes: ByteArray): String {
        val charset = detect(bytes)
        val skip = when {
            charset == Charsets.UTF_8 && bytes.startsWith(utf8Bom) -> utf8Bom.size
            charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE -> 2
            else -> 0
        }
        return String(bytes, skip, bytes.size - skip, charset)
    }

    /**
     * mpv'ye verilecek bayt dizisi: zaten UTF-8 ise aynı dizi (BOM dahil) döner, değilse UTF-8'e
     * çevrilmiş yeni dizi.
     */
    fun toUtf8(bytes: ByteArray): ByteArray =
        if (isValidUtf8(bytes)) bytes else decode(bytes).toByteArray(Charsets.UTF_8)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
