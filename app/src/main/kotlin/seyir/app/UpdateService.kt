package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import seyir.app.i18n.I18n
import seyir.core.AppDirs
import seyir.core.AppRelease
import seyir.core.AppUpdate
import seyir.core.SeyirLog
import seyir.core.UpdateIntegrity
import seyir.core.UpdateVerification
import seyir.core.net.Network
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Uygulamanın kendi güncellemesi: GitHub Releases'taki son sürüme bakılır (özel depoda Ayarlar'daki
 * erişim anahtarıyla). Windows'ta kurulum dosyası (.msi) indirilir, **imzası ve özeti doğrulanır**,
 * sonra kurulup uygulama kapanır (kurulum paketinin yükseltme kimliği sabit olduğu için eski sürümün
 * üzerine kurulur); öteki sistemlerde sürüm sayfası açılır.
 *
 * Doğrulama kapalı kapıdır: sürümde `SHA256SUMS` ve `SHA256SUMS.sig` yoksa, imza gömülü anahtarla
 * uyuşmuyorsa ya da dosyanın SHA-256 değeri imzalı listedekinden farklıysa hiçbir şey çalıştırılmaz
 * (bkz. [UpdateIntegrity]).
 */
class UpdateService(private val controller: AppController) {
    /** Çalışan sürüm (derlemede seyir/version.txt kaynağına yazılır). */
    val currentVersion: String =
        javaClass.getResourceAsStream("/seyir/version.txt")?.use { it.readBytes().decodeToString().trim() } ?: "0.0.0"

    var available by mutableStateOf<AppRelease?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var installing by mutableStateOf(false)
        private set
    /** Kullanıcı "Sonra" dediyse bu oturumda bildirim gösterilmez. */
    var dismissed by mutableStateOf(false)

    private val mapper = jacksonObjectMapper()

    /** DoH ve TLS ayarları (Network) güncelleme isteklerine de uygulanır; ayarlar değişebildiği için her seferinde güncel istemci alınır. */
    private fun client() = Network.client.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

    /** Doğrulama başarısız: ileti kullanıcıya olduğu gibi gösterilir ("indirilemedi" ön eki eklenmez). */
    private class VerificationRefused(message: String) : IOException(message)

    /** Son sürümü denetler; [onResult] kullanıcıya gösterilecek kısa sonucu alır (elle denetimde). */
    fun check(onResult: (String) -> Unit = {}) {
        if (checking) return
        checking = true
        controller.scope.launch(Dispatchers.IO) {
            val t = I18n.current
            val message = runCatching {
                // releases/latest ön sürümleri hiç döndürmez; ön sürüm kullanan için tüm liste okunur
                val prerelease = AppUpdate.isPrerelease(currentVersion)
                val path = if (prerelease) "releases?per_page=30" else "releases/latest"
                val request = authorized(Request.Builder().url("https://api.github.com/repos/$REPOSITORY/$path"))
                    .header("Accept", "application/vnd.github+json").build()
                client().newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> t.noReleaseYet
                        !response.isSuccessful -> t.releaseInfoFailed(response.code)
                        else -> {
                            val json = mapper.readTree(response.body.string())
                            val release = if (prerelease) AppUpdate.newestRelease(json, includePrereleases = true) else AppUpdate.parseRelease(json)
                            if (release != null && AppUpdate.isNewer(release.version, currentVersion)) {
                                available = release
                                dismissed = false
                                t.releaseAnnounced(release.version)
                            } else {
                                t.upToDate(currentVersion)
                            }
                        }
                    }
                }
            }.getOrElse {
                SeyirLog.w(TAG, "Güncelleme denetlenemedi", it)
                t.updateCheckFailed(it.message)
            }
            SeyirLog.i(TAG, message)
            checking = false
            onResult(message)
        }
    }

    /**
     * Windows'ta kurulum dosyasını indirip doğrular, çalıştırır ve uygulamayı kapatır; başka sistemde
     * sürüm sayfasını açar. Doğrulama başarısızsa dosya silinir ve [onError] açıklayıcı iletiyi alır.
     */
    fun install(onError: (String) -> Unit) {
        val release = available ?: return
        val installer = AppUpdate.installerFor(release)
        if (installer == null) {
            runCatching { Desktop.getDesktop().browse(URI(release.pageUrl)) }
            return
        }
        installing = true
        controller.scope.launch(Dispatchers.IO) {
            runCatching {
                val t = I18n.current
                // Önce imzalı özet listesi: eksikse büyük dosya hiç indirilmez
                val sums = release.assets[UpdateIntegrity.SUMS_ASSET]?.let { downloadSmall(it, MAX_SUMS_BYTES) }
                val signature = release.assets[UpdateIntegrity.SIGNATURE_ASSET]?.let { downloadSmall(it, MAX_SIGNATURE_BYTES) }
                if (sums == null || signature == null) {
                    val missing = if (sums == null) UpdateIntegrity.SUMS_ASSET else UpdateIntegrity.SIGNATURE_ASSET
                    throw VerificationRefused(t.updateAssetMissing(missing))
                }

                val dir = File(AppDirs.data, "cache/update").apply { mkdirs() }
                val target = File(dir, installer.key)
                val part = File(dir, installer.key + ".part")
                // Özel depoda dosya API'den "octet-stream" istenerek indirilir (asıl adrese yönlendirir)
                val request = authorized(Request.Builder().url(installer.value)).header("Accept", "application/octet-stream").build()
                client().newBuilder().callTimeout(30, TimeUnit.MINUTES).build().newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    response.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                }
                // Özet, diske yazılmış dosyadan hesaplanır: çalıştırılacak şey tam olarak doğrulanan şeydir
                val verdict = UpdateIntegrity.verify(sums, signature, installer.key, UpdateIntegrity.sha256Hex(part))
                if (verdict != UpdateVerification.Ok) {
                    part.delete()
                    SeyirLog.w(TAG, "Güncelleme doğrulanamadı: $verdict")
                    throw VerificationRefused(refusalMessage(verdict))
                }
                target.delete()
                check(part.renameTo(target)) { "Dosya yeniden adlandırılamadı: $part" }

                SeyirLog.i(TAG, "Kurulum doğrulandı, başlatılıyor: $target")
                val command = if (target.extension.equals("msi", true)) listOf("msiexec", "/i", target.absolutePath) else listOf(target.absolutePath)
                ProcessBuilder(command).start()
                controller.shutdown()
                exitProcess(0)
            }.onFailure {
                SeyirLog.w(TAG, "Güncelleme kurulamadı", it)
                installing = false
                onError(if (it is VerificationRefused) it.message.orEmpty() else I18n.current.updateDownloadFailed(it.message))
            }
        }
    }

    /** [UpdateVerification] sonucunu kullanıcıya gösterilecek metne çevirir. */
    private fun refusalMessage(verdict: UpdateVerification): String {
        val t = I18n.current
        return when (verdict) {
            UpdateVerification.Ok -> ""
            is UpdateVerification.MissingAsset -> t.updateAssetMissing(verdict.name)
            UpdateVerification.BadSignature -> t.updateSignatureInvalid
            is UpdateVerification.NotListed -> t.updateNotListed(verdict.name)
            is UpdateVerification.HashMismatch -> t.updateHashMismatch(verdict.name)
        }
    }

    /** Küçük bir sürüm dosyasını (özet listesi, imza) belleğe indirir; [limit]'i aşarsa hata verir. */
    private fun downloadSmall(url: String, limit: Long): ByteArray {
        val request = authorized(Request.Builder().url(url)).header("Accept", "application/octet-stream").build()
        client().newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body
            check(body.contentLength() <= limit) { "Dosya beklenenden büyük" }
            val data = body.byteStream().readNBytes((limit + 1).toInt())
            check(data.size <= limit) { "Dosya beklenenden büyük" }
            return data
        }
    }

    /** Erişim anahtarı yalnız GitHub'a giden bu isteklere eklenir (OkHttp başka sunucuya yönlendirmede onu atar). */
    private fun authorized(builder: Request.Builder): Request.Builder {
        val token = controller.settings.githubToken.trim()
        return if (token.isEmpty()) builder else builder.header("Authorization", "token $token")
    }

    companion object {
        private const val TAG = "UpdateService"
        private const val MAX_SUMS_BYTES = 64L * 1024
        private const val MAX_SIGNATURE_BYTES = 1024L
        /** Sürümlerin yayımlandığı depo. */
        const val REPOSITORY = "fatihozpolat/seyir"
    }
}
