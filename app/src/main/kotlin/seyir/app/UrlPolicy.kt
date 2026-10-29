package seyir.app

import seyir.app.i18n.I18n
import java.io.File
import java.net.URI

/** Güvenlik ilkesine uymayan bir adres; mesaj kullanıcıya gösterilecek dildedir. */
class InsecureUrlException(message: String) : IllegalArgumentException(message)

/**
 * Eklenti depolarının ve eklenti dosyalarının hangi adreslerden alınabileceğini belirler. Eklenti jar'ı
 * rastgele kod çalıştırdığı için kaynağı yalnız şifreli bağlantıyla okunur:
 * - `https://` her yerde kabul edilir,
 * - `http://` yalnız geliştirme için `localhost`, `127.0.0.1` ve `::1` adreslerinde kabul edilir,
 * - yerel dosya yolları (`C:\depo\plugins.json`, `/home/x/plugins.json`, `file:` adresleri) kabul edilir,
 * - başka bir şema (ftp vb.) reddedilir.
 */
object UrlPolicy {
    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://""")
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    /** Adres uzak bir sunucuya `https://` (ya da yerel geliştirme için `http://`) ile gidiyor mu. */
    fun isSecureRemote(url: String): Boolean {
        val trimmed = url.trim()
        val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase() ?: return false
        val host = hostOf(trimmed) ?: return false
        return when (scheme) {
            "https" -> true
            "http" -> host in LOOPBACK_HOSTS
            else -> false
        }
    }

    /**
     * Adresin sunucu adı (küçük harf) ya da yoksa null. `URI` alt çizgi içeren adlarda (`my_host.example`)
     * sunucuyu null verir ama adres geçerlidir; o durumda yetki bölümü elle ayrıştırılır.
     */
    internal fun hostOf(url: String): String? {
        val fromUri = runCatching { URI(url).host }.getOrNull()
        return (fromUri ?: authorityHost(url))?.takeIf { it.isNotBlank() }?.lowercase()
    }

    /** Yetki bölümünden (`şema://[kullanıcı@]sunucu[:kapı]/…`) sunucu adını elle ayıklar; URI'nin reddettiği adlar için. */
    private fun authorityHost(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } }
    }

    /**
     * Adres uzak bir sunucu değil, bu bilgisayardaki bir dosya yolu mu (`file:` dahil; `C:\` ve göreli
     * yollar yerel sayılır). Ağ paylaşımları (UNC: `\\sunucu\paylasim`, `file://sunucu/paylasim`) yerel
     * değildir: dosyayı uzak bir makine verir ve Windows oraya kimlik bilgisi göndermeye çalışır.
     */
    fun isLocalPath(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.startsWith("file:", ignoreCase = true)) return isLocalFileUrl(trimmed)
        // Ters eğik çizgiler de eğik çizgi sayılır: "\\sunucu\paylasim" → "//sunucu/paylasim"
        return SCHEME.find(trimmed) == null && !trimmed.replace('\\', '/').startsWith("//")
    }

    /** `file:` adresi yalnız sunucu adı boşsa ya da `localhost` ise yereldir; `file:////sunucu/paylasim` UNC'dir. */
    private fun isLocalFileUrl(url: String): Boolean {
        val rest = url.substring("file:".length).replace('\\', '/')
        if (!rest.startsWith("//")) return true
        val afterSlashes = rest.substring(2)
        val authority = afterSlashes.substringBefore('/')
        val path = afterSlashes.substring(authority.length)
        return when {
            authority.isEmpty() -> !path.startsWith("//")
            else -> authority.equals("localhost", ignoreCase = true) && !path.startsWith("//")
        }
    }

    fun isAllowed(url: String): Boolean = isSecureRemote(url) || isLocalPath(url)

    /** İlkeye uymayan adreste [InsecureUrlException] fırlatır. */
    fun require(url: String) {
        if (!isAllowed(url)) throw InsecureUrlException(I18n.current.insecureUrl(url))
    }

    /** Yerel adresi dosyaya çevirir (`file:` adresi ya da düz yol). */
    fun toFile(path: String): File {
        val trimmed = path.trim()
        return if (trimmed.startsWith("file:", ignoreCase = true)) File(URI(trimmed)) else File(trimmed)
    }

    /**
     * [reference] adresini [base]'e göre çözer. Taban uzaksa URI kurallarıyla, yerel dosyaysa dosyanın
     * klasörüne göre çözülür; [reference] kendi başına tam bir adresse olduğu gibi döner.
     */
    fun resolve(base: String, reference: String): String {
        val ref = reference.trim()
        // Uzak bir depo, istemciyi yerel bir dosyayı okumaya yönlendiremez
        if (!isLocalPath(base) && ref.startsWith("file:", ignoreCase = true)) throw InsecureUrlException(I18n.current.insecureUrl(ref))
        if (SCHEME.containsMatchIn(ref) || ref.startsWith("file:", ignoreCase = true)) return ref
        if (!isLocalPath(base)) return URI(base.trim()).resolve(ref).toString()
        val absolute = File(ref)
        if (absolute.isAbsolute) return ref
        return File(toFile(base).absoluteFile.parentFile, ref).path
    }
}
