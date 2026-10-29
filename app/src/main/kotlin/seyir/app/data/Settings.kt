package seyir.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import seyir.core.AppDirs
import seyir.core.net.DohProvider
import seyir.core.net.NetworkSettings
import seyir.core.player.PlayerWindowState
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Kullanıcı ayarları; `settings.json` dosyasında saklanır. */
data class AppSettings(
    /** Arayüz dili: "system" (sistem dili), "tr" ya da "en". */
    val language: String = "system",
    val fragmentTls: Boolean = true,
    /** DohProvider adı ya da "NONE" (sistem DNS'i). */
    val doh: String = DohProvider.CLOUDFLARE.name,
    val proxy: String = "",
    val mpvPath: String = "",
    val browserPath: String = "",
    val solveCloudflare: Boolean = true,
    val subtitleLang: String = "tr",
    val autoPlayBest: Boolean = false,
    /** Bir bölüm sonuna kadar izlenince dizinin sıradaki bölümü kendiliğinden açılır. */
    val autoplayNext: Boolean = true,
    /** Varsayılan video kalitesi: [seyir.core.hls.QualityPreference.id] ("highest", "p1080", "p720", "p480", "lowest"). */
    val defaultQuality: String = "highest",
    /** Video Seyir'in kendi oynatıcı arayüzüyle (uosc tabanlı) açılır; kapalıysa mpv'nin düz görünümü kullanılır. */
    val playerUi: Boolean = true,
    /**
     * Video Seyir penceresinin içinde, uygulamanın kendi denetimleriyle oynar; kapalıysa ayrı mpv penceresi açılır.
     * Linux'ta henüz denenmediği için varsayılan olarak yalnız Windows'ta açık.
     */
    val embeddedPlayer: Boolean = AppDirs.isWindows,
    /** Altyazı boyutu çarpanı (mpv `sub-scale`): 0.8 küçük, 1.0 normal, 1.25 büyük, 1.5 çok büyük. */
    val subtitleScale: Double = 1.0,
    /**
     * Oynatıcı (mpv) penceresinin son boyutu, konumu, büyütülmüş ve tam ekran durumu; oynatma sırasında seyrek
     * güncellenir, yeni pencere açılırken uygulanır. Ayarlar ekranında gösterilmez.
     */
    val playerWindow: PlayerWindowState? = null,
    /** Ağ taramasında bulunamayan Cast alıcısının adresi (ör. 192.0.2.50); boşsa yalnız tarama. */
    val castAddress: String = "",
    /**
     * Özel GitHub eklenti depoları için erişim anahtarı (yalnız GitHub adreslerine gönderilir). Bellekte
     * düz metindir; Windows'ta diske yazılırken boşaltılıp [githubTokenProtected]'a şifreli konur.
     */
    val githubToken: String = "",
    /** Erişim anahtarının diskteki korunmuş (DPAPI) hâli; bellekteki ayarlarda her zaman boştur. */
    val githubTokenProtected: String = "",
    /** Açılışta kurulu eklentilerin yeni sürümleri kendiliğinden kurulur. */
    val autoUpdatePlugins: Boolean = true,
    /** Açılışta depolara sonradan eklenen eklentiler de kurulur (kullanıcının kaldırdıkları hariç). */
    val autoInstallNewPlugins: Boolean = true,
    /** Açılışta uygulamanın yeni sürümü olup olmadığına bakılır. */
    val checkAppUpdates: Boolean = true,
    /** İsteğe bağlı TMDB anahtarı (v3 API anahtarı ya da v4 okuma erişim belirteci); boşsa TMDB kullanılmaz. */
    val tmdbApiKey: String = "",
    val homeProvider: String? = null,
    /** Arama sonuçları aynı başlığı bulan sağlayıcılar tek kartta toplanarak gösterilir. */
    val groupSearchByTitle: Boolean = false,
    val disabledProviders: Set<String> = emptySet(),
    /** Eklenti listesi (plugins.json) adresleri. */
    val repositories: List<String> = emptyList(),
    /** Günlük ayrıntısı: "error", "warn", "info" ya da "debug". */
    val logLevel: String = "info",
) {
    @JsonIgnore
    fun networkSettings() = NetworkSettings(
        fragmentTls = fragmentTls,
        dnsOverHttps = DohProvider.entries.firstOrNull { it.name == doh },
        proxy = proxy.ifBlank { null },
    )
}

/**
 * Ayarları `settings.json` dosyasında tutar. Erişim anahtarı diske [cipher] ile korunarak yazılır
 * (Windows'ta DPAPI); eski sürümlerden kalan düz metin değer açılışta şifrelenip dosya yeniden yazılır.
 * Şifreleyici olmayan sistemde (Linux) anahtar düz metin kalır, dosya yalnız sahibinin okuyabileceği
 * izinle (0600) yazılır.
 */
class SettingsStore(
    private val file: File = File(AppDirs.data, "settings.json"),
    private val cipher: TokenCipher? = TokenCipher.platform(),
) {
    /**
     * Diskteki korunmuş anahtar çözülemediyse (başka kullanıcı/makine, DPAPI hatası) özgün blok burada
     * tutulur ve kullanıcı yeni bir anahtar girene kadar dosyaya aynen geri yazılır; yoksa ilk kayıt
     * çözülemeyen anahtarı boş değerle ezip kalıcı olarak yok ederdi.
     */
    private var unreadableBlob: String? = null

    @Volatile
    var current: AppSettings = load()
        private set

    private fun load(): AppSettings {
        val stored = JsonStore.read(file) { AppSettings() }
        val token = when {
            stored.githubTokenProtected.isNotBlank() && cipher != null -> {
                val plain = cipher.unprotect(stored.githubTokenProtected)
                if (plain == null) unreadableBlob = stored.githubTokenProtected
                plain.orEmpty()
            }
            else -> stored.githubToken
        }
        val settings = stored.copy(githubToken = token, githubTokenProtected = "")
        // Eski düz metin değeri (ya da korunamamışı) açılışta şifreli biçime taşı
        if (cipher != null && stored.githubToken.isNotBlank() && stored.githubTokenProtected.isBlank()) persist(settings)
        return settings
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        current = transform(current).copy(githubTokenProtected = "")
        persist(current)
        return current
    }

    /** Dosyaya yazılacak hâl: anahtar korunabiliyorsa düz alan boşaltılıp korunmuş alana şifreli konur. */
    private fun persist(settings: AppSettings) {
        val protectedToken = settings.githubToken.takeIf { it.isNotBlank() }?.let { token -> cipher?.protect(token) }
        val onDisk = when {
            protectedToken != null -> {
                unreadableBlob = null
                settings.copy(githubToken = "", githubTokenProtected = protectedToken)
            }
            // Yeni anahtar yok ve eskisi çözülemedi: özgün blok olduğu gibi kalır
            settings.githubToken.isBlank() && unreadableBlob != null -> settings.copy(githubTokenProtected = unreadableBlob!!)
            else -> {
                // Anahtar korunamadan düz yazılıyorsa eski çözülemeyen blok artık geçersizdir
                if (settings.githubToken.isNotBlank()) unreadableBlob = null
                settings
            }
        }
        // Dosya AtomicFile ile benzersiz bir geçici dosyadan taşınarak yazılır; izinler yazımdan sonra kısıtlanır
        JsonStore.write(file, onDisk)
        restrictPermissions(file)
    }

    /** POSIX dosya sistemlerinde yazılmış dosyayı yalnız sahibine açar (rw-------); ayar dosyası anahtar içerebilir. */
    private fun restrictPermissions(target: File) {
        if (AppDirs.isWindows || !target.exists()) return
        runCatching { Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }
}
