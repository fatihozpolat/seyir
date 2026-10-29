package seyir.core

import com.fasterxml.jackson.databind.JsonNode
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey

/** GitHub Releases'taki bir sürüm ve indirilebilir dosyaları. */
data class AppRelease(
    val version: String,
    val name: String,
    val notes: String?,
    val pageUrl: String,
    /** Dosya adı → GitHub API'deki indirme adresi (özel depoda erişim anahtarıyla indirilir). */
    val assets: Map<String, String>,
)

object AppUpdate {
    /** "v1.2.0", "1.2", "1.2.0-beta" → [1, 2, 0]; sayı olmayan son ekler yok sayılır. */
    fun parseVersion(text: String): List<Int> =
        text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** "1.0.0-beta.5" → ["beta", "5"]; ön sürüm değilse boş ("+derleme" eki yok sayılır). */
    fun prereleaseOf(text: String): List<String> =
        text.trim().substringBefore('+').substringAfter('-', "").split('.').filter { it.isNotEmpty() }

    /** Ön sürüm eki var mı ("1.0.0-beta.5" evet, "1.0.0" hayır). */
    fun isPrerelease(text: String): Boolean = prereleaseOf(text).isNotEmpty()

    /**
     * [latest], [current]'tan yeni mi (eksik basamaklar 0 sayılır: 1.2 = 1.2.0). Sayılar eşitse SemVer kuralı:
     * ön sürüm, aynı numaralı son sürümden eskidir (1.0.0-beta < 1.0.0); ön sürüm ekleri parça parça karşılaştırılır
     * (beta.4 < beta.5 < beta.10, beta < rc).
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return comparePrerelease(prereleaseOf(latest), prereleaseOf(current)) > 0
    }

    private fun comparePrerelease(a: List<String>, b: List<String>): Int {
        // Eki olmayan (son sürüm) ekli olandan yenidir
        if (a.isEmpty() || b.isEmpty()) return b.size.compareTo(a.size).coerceIn(-1, 1)
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toIntOrNull()
            val y = b[i].toIntOrNull()
            val order = when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a[i].compareTo(b[i])
            }
            if (order != 0) return order
        }
        return a.size.compareTo(b.size)
    }

    /**
     * `GET /repos/{sahip}/{depo}/releases` yanıtından en yeni sürüm: taslaklar atlanır, ön sürümler yalnız
     * [includePrereleases] doğruysa sayılır (ön sürüm kullanan, sonraki ön sürümü ve son sürümü de görsün).
     */
    fun newestRelease(json: JsonNode, includePrereleases: Boolean): AppRelease? =
        json.filter { !it.path("draft").asBoolean(false) && (includePrereleases || !it.path("prerelease").asBoolean(false)) }
            .mapNotNull(::parseRelease)
            .fold(null as AppRelease?) { best, next -> if (best == null || isNewer(next.version, best.version)) next else best }

    /** `GET /repos/{sahip}/{depo}/releases/latest` yanıtı. Taslak ve ön sürümler GitHub'da zaten "latest" olmaz. */
    fun parseRelease(json: JsonNode): AppRelease? {
        val tag = json.path("tag_name").asText("").ifBlank { return null }
        return AppRelease(
            version = tag.removePrefix("v"),
            name = json.path("name").asText("").ifBlank { tag },
            notes = json.path("body").asText("").ifBlank { null },
            pageUrl = json.path("html_url").asText(""),
            assets = json.path("assets").associate { it.path("name").asText("") to it.path("url").asText("") }
                .filterKeys { it.isNotEmpty() },
        )
    }

    /** Bu işletim sistemi için kurulum dosyası: Windows'ta .msi (yoksa .exe); öteki sistemlerde null (sürüm sayfası açılır). */
    fun installerFor(release: AppRelease, windows: Boolean = AppDirs.isWindows): Map.Entry<String, String>? =
        if (!windows) null
        else release.assets.entries.firstOrNull { it.key.endsWith(".msi", ignoreCase = true) }
            ?: release.assets.entries.firstOrNull { it.key.endsWith(".exe", ignoreCase = true) }
}

/** Güncelleme doğrulamasının sonucu; [Ok] dışındakilerde kurulum yapılmaz (kapalı kapı). */
sealed interface UpdateVerification {
    data object Ok : UpdateVerification

    /** Sürümde `SHA256SUMS` ya da `SHA256SUMS.sig` dosyası yok. */
    data class MissingAsset(val name: String) : UpdateVerification

    /** `SHA256SUMS.sig`, gömülü açık anahtarla `SHA256SUMS` baytlarına uymuyor (ya da bozuk). */
    data object BadSignature : UpdateVerification

    /** Kurulum dosyasının adı `SHA256SUMS` içinde yok ya da dosya geçersiz/çift satırlı. */
    data class NotListed(val name: String) : UpdateVerification

    /** İndirilen dosyanın SHA-256 değeri imzalı listedekiyle aynı değil. */
    data class HashMismatch(val name: String) : UpdateVerification
}

/**
 * Güncelleme bütünlüğü: sürümde `SHA256SUMS` (kurulum dosyalarının özetleri) ve onun Ed25519 imzası
 * `SHA256SUMS.sig` bulunur. İmza uygulamaya gömülü açık anahtarla doğrulanır, sonra indirilen
 * kurulum dosyasının SHA-256 değeri listedeki satırla karşılaştırılır.
 *
 * Biçimler: `SHA256SUMS` her satırı `<64 küçük hex>␠␠<dosya adı>` (LF sonlu; `sha256sum` çıktısı);
 * `SHA256SUMS.sig` 64 baytlık ham imzanın tek satır Base64'ü ([ReleaseSigning]).
 */
object UpdateIntegrity {
    const val SUMS_ASSET = "SHA256SUMS"
    const val SIGNATURE_ASSET = "SHA256SUMS.sig"

    /**
     * Sürüm imzasını doğrulayan açık anahtar (X.509, Base64). Eşleşen özel anahtar depoda değildir;
     * `cli keygen` ile üretilir ve `scripts/release.ps1` imzalarken kullanır.
     */
    const val RELEASE_PUBLIC_KEY = "MCowBQYDK2VwAyEAj9X++GeY6TgMYqnMgjzV28G+SIDMd4tg0rvT8Lp7qnA="

    /** `SHA256SUMS` metnini dosya adı → küçük harfli hex özet eşlemesine çevirir; çift ad ya da bozuk satır varsa null. */
    fun parseSums(text: String): Map<String, String>? {
        val result = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val match = SUM_LINE.matchEntire(line) ?: return null
            val name = match.groupValues[2]
            if (result.put(name, match.groupValues[1].lowercase()) != null) return null
        }
        return result
    }

    /**
     * İmzayı ve kurulum dosyasının özetini doğrular.
     *
     * @param sums `SHA256SUMS` dosyasının tam baytları (imza bunların üzerindedir)
     * @param signature `SHA256SUMS.sig` içeriği (Base64 metin)
     * @param installerName kurulum dosyasının sürümdeki adı
     * @param installerSha256 indirilen dosyadan hesaplanan SHA-256 (hex)
     */
    fun verify(
        sums: ByteArray?,
        signature: ByteArray?,
        installerName: String,
        installerSha256: String,
        publicKey: PublicKey = ReleaseSigning.publicKeyFromBase64(RELEASE_PUBLIC_KEY),
    ): UpdateVerification {
        if (sums == null) return UpdateVerification.MissingAsset(SUMS_ASSET)
        if (signature == null) return UpdateVerification.MissingAsset(SIGNATURE_ASSET)
        if (!ReleaseSigning.verify(sums, signature.decodeToString(), publicKey)) return UpdateVerification.BadSignature
        val expected = parseSums(sums.decodeToString())?.get(installerName) ?: return UpdateVerification.NotListed(installerName)
        // Sabit zamanlı karşılaştırma gerekmez (özet gizli değil), ama büyük/küçük harf farkı yok sayılır
        return if (expected.equals(installerSha256.trim(), ignoreCase = true)) UpdateVerification.Ok
        else UpdateVerification.HashMismatch(installerName)
    }

    /** Dosyanın SHA-256 değerini küçük harfli hex olarak hesaplar (belleğe sığdırmadan). */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val SUM_LINE = Regex("""([0-9a-fA-F]{64}) [ *](.+)""")
}
