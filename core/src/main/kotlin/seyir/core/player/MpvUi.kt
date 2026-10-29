package seyir.core.player

import seyir.core.SeyirLog
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Seyir'in oynatıcı arayüzü (uosc + thumbfast + `seyir.lua`) için mpv'ye verilen ayarlar.
 *
 * @property configDir Arayüz dosyalarının klasörü (`--config-dir`): scripts, fonts, script-opts, input.conf.
 *   Bu klasör verildiğinde kullanıcının kendi `mpv.conf` dosyası okunmaz; böylece arayüz ayarları bozulmaz.
 * @property language Arayüz dili ("tr" ya da "en"); uosc menüleri ve `seyir.lua` metinleri bu dile göre gelir.
 * @property thumbnailMpvPath İleri sarma önizlemesini üreten ikinci mpv sürecinin yürütülebilir dosyası.
 *   Windows'ta thumbfast bunu kendisi bulur; yalnız PATH dışında kurulu Linux mpv'si için verilir.
 */
data class PlayerUi(
    val configDir: String,
    val language: String = "tr",
    val thumbnailMpvPath: String? = null,
)

/** Arayüz paketinin yeri ve mpv sürümü denetimi. */
object MpvUi {
    private const val TAG = "MpvUi"

    /** Paketteki klasör adı (`app/resources/common/mpv-ui`). */
    const val DIR_NAME = "mpv-ui"

    /** uosc 5.x'in gerektirdiği en düşük mpv sürümü (osd-fonts-dir, user-data özellikleri). */
    const val MIN_MAJOR = 0
    const val MIN_MINOR = 38

    /**
     * Arayüz klasörünü bulur. Pakette ve `gradlew run` ile Compose'un kaynak klasöründe
     * (`compose.application.resources.dir`) durur; geliştirme ortamında (IDE, test) bu özellik yoksa
     * çalışma klasörüne göre depodaki kaynak klasörüne bakılır. `scripts/seyir.lua` varlığı doğrulanır.
     * Parametreler yalnızca testlerde değiştirilir.
     */
    fun locate(
        resourcesDir: String? = System.getProperty("compose.application.resources.dir"),
        workingDir: String? = System.getProperty("user.dir"),
    ): File? {
        val candidates = buildList {
            if (!resourcesDir.isNullOrBlank()) add(File(resourcesDir, DIR_NAME))
            if (!workingDir.isNullOrBlank()) {
                add(File(workingDir, "app/resources/common/$DIR_NAME"))
                add(File(workingDir, "resources/common/$DIR_NAME"))
            }
        }
        return candidates.firstOrNull { File(it, "scripts/seyir.lua").isFile }
    }

    /** `mpv --version` çıktısındaki ilk satırdan (örn. "mpv v0.41.0-1227-ge471496c5" ya da "mpv 0.37.0") sürüm. */
    fun parseVersion(versionOutput: String): Pair<Int, Int>? {
        val match = Regex("""mpv\s+v?(\d+)\.(\d+)""").find(versionOutput.lineSequence().firstOrNull().orEmpty()) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    fun isSupported(version: Pair<Int, Int>?): Boolean =
        version != null && (version.first > MIN_MAJOR || (version.first == MIN_MAJOR && version.second >= MIN_MINOR))

    /**
     * Komutun mpv'si arayüzü destekliyor mu? Sürüm okunamazsa false (düz mpv'ye dönülür).
     * Yavaş olabilir (süreç başlatır); arayüz iş parçacığından çağrılmaz.
     */
    fun commandSupportsUi(command: MpvCommand): Boolean {
        // Flatpak kum havuzu arayüz klasörünü göremez
        if (command.sandboxIpcDir != null) return false
        return try {
            val process = ProcessBuilder(command.argv + "--version").redirectErrorStream(true).start()
            runCatching { process.outputStream.close() }
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            val version = parseVersion(output)
            isSupported(version).also {
                if (!it) SeyirLog.w(TAG, "mpv sürümü arayüz için eski ya da okunamadı ($version); düz mpv kullanılacak")
            }
        } catch (e: Exception) {
            SeyirLog.w(TAG, "mpv sürümü okunamadı: ${e.message}")
            false
        }
    }

    /** Arayüz diline göre `--script-opts` değeri; virgüllü seçenek değerleri bu biçimde taşınamaz, bu yüzden elenir. */
    internal fun scriptOpts(ui: PlayerUi): String {
        val lang = if (ui.language.lowercase(Locale.ROOT) == "en") "en" else "tr"
        val parts = mutableListOf("uosc-languages=$lang", "seyir-lang=$lang")
        ui.thumbnailMpvPath?.takeIf { it.isNotBlank() && !it.contains(',') }?.let { parts += "thumbfast-mpv_path=$it" }
        return parts.joinToString(",")
    }
}

/**
 * `seyir.lua` ile Seyir arasındaki mpv iletileri.
 *
 * - Seyir → betik: `script-message-to seyir seyir-next-info <başlık>` (başlık boş ya da yoksa sonraki bölüm yok).
 * - Betik → Seyir: `script-message seyir-next`; IPC istemcisi bunu `client-message` olayı olarak alır
 *   (`{"event":"client-message","args":["seyir-next"]}`).
 * - Önceki bölüm için aynısı: `seyir-prev-info <başlık>` ve `seyir-prev`.
 */
object SeyirScript {
    const val SCRIPT_NAME = "seyir"
    const val MSG_NEXT_INFO = "seyir-next-info"
    const val MSG_NEXT = "seyir-next"
    const val MSG_PREV_INFO = "seyir-prev-info"
    const val MSG_PREV = "seyir-prev"

    /** Betiğe sonraki bölümün başlığını bildirir; [title] null ya da boşsa sonraki bölüm yok demektir. */
    fun nextInfoCommand(title: String?): List<String> =
        listOf("script-message-to", SCRIPT_NAME, MSG_NEXT_INFO, title.orEmpty())

    /** Betiğe önceki bölümün başlığını bildirir; [title] null ya da boşsa önceki bölüm yok demektir. */
    fun previousInfoCommand(title: String?): List<String> =
        listOf("script-message-to", SCRIPT_NAME, MSG_PREV_INFO, title.orEmpty())

    /** Betiğin gönderdiği iletiler. */
    sealed interface Message {
        /** Kullanıcı "sonraki bölüm" düğmesine bastı. */
        data object NextRequested : Message

        /** Kullanıcı "önceki bölüm" düğmesine bastı. */
        data object PreviousRequested : Message
    }

    /** `client-message` olayını tanır; başka olay ya da başka ileti için null. */
    fun parse(event: MpvEvent): Message? {
        if (event.name != "client-message") return null
        val args = event.fields.get("args")?.takeIf { it.isArray }?.map { it.asText() } ?: return null
        return when (args.firstOrNull()) {
            MSG_NEXT -> Message.NextRequested
            MSG_PREV -> Message.PreviousRequested
            else -> null
        }
    }
}
