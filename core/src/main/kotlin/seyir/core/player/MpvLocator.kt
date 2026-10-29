package seyir.core.player

import seyir.core.AppDirs
import seyir.core.SeyirLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * mpv'yi başlatmak için komut öneki. Düz bir yürütülebilir dosya için tek elemanlıdır;
 * flatpak için `["flatpak", "run", "io.mpv.Mpv"]` gibi çok elemanlıdır.
 *
 * @property sandboxIpcDir Süreç kum havuzundaysa (flatpak) IPC soketinin konabileceği, havuzla
 *   aynı yolda paylaşılan klasör; null ise sistem varsayılanı kullanılır.
 */
data class MpvCommand(val argv: List<String>, val sandboxIpcDir: File? = null) {
    init {
        require(argv.isNotEmpty()) { "argv boş olamaz" }
    }

    /** Günlükte ve ayarlarda göstermek için okunur metin. */
    val description: String get() = argv.joinToString(" ")
}

/** Sistemde kullanılabilir mpv'yi bulur. */
object MpvLocator {
    private const val TAG = "MpvLocator"
    private const val FLATPAK_APP_ID = "io.mpv.Mpv"

    /**
     * Arama sırası: ayarlanmış yol, uygulama paketindeki kopya, `AppDirs.data/mpv/`, PATH,
     * sonra işletim sistemine özgü yaygın konumlar (Windows: scoop/Program Files/chocolatey;
     * Linux: /usr/bin, /usr/local/bin, flatpak).
     *
     * Diğer parametreler yalnızca testlerde değiştirilir.
     */
    fun find(
        configured: String?,
        bundledResourcesDir: String? = System.getProperty("compose.application.resources.dir"),
        dataDir: () -> File = { AppDirs.data },
        pathEnv: String? = System.getenv("PATH"),
        homeDir: String = System.getProperty("user.home"),
        windows: Boolean = AppDirs.isWindows,
        flatpakInstalled: () -> Boolean = ::isFlatpakMpvInstalled,
    ): MpvCommand? {
        val exeName = if (windows) "mpv.exe" else "mpv"

        // 1) Kullanıcının ayarladığı yol
        if (!configured.isNullOrBlank()) {
            resolveConfigured(configured.trim(), exeName, pathEnv, windows)?.let { return it.toCommand() }
            SeyirLog.w(TAG, "Ayarlanan mpv yolu geçersiz, diğer konumlar denenecek: $configured")
        }

        // 2) Uygulama paketiyle gelen kopya
        if (!bundledResourcesDir.isNullOrBlank()) {
            usable(File(File(bundledResourcesDir, "mpv"), exeName), windows)?.let { return it.toCommand() }
        }

        // 3) Uygulama veri klasörü
        usable(File(File(dataDir(), "mpv"), exeName), windows)?.let { return it.toCommand() }

        // 4) PATH
        searchPath(exeName, pathEnv, windows)?.let { return it.toCommand() }

        // 5) Yaygın konumlar
        val common = if (windows) windowsCandidates(homeDir) else linuxCandidates()
        common.firstNotNullOfOrNull { usable(it, windows) }?.let { return it.toCommand() }

        if (!windows && flatpakInstalled()) {
            // Flatpak kum havuzu, $XDG_RUNTIME_DIR/app/<kimlik> klasörünü aynı yolda paylaşır.
            val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
            val ipcDir = runtime?.let { File(File(it, "app"), FLATPAK_APP_ID) }
            return MpvCommand(listOf("flatpak", "run", FLATPAK_APP_ID), ipcDir)
        }
        return null
    }

    private fun File.toCommand() = MpvCommand(listOf(absolutePath))

    private fun resolveConfigured(value: String, exeName: String, pathEnv: String?, windows: Boolean): File? {
        val file = File(value)
        if (file.isDirectory) return usable(File(file, exeName), windows)
        usable(file, windows)?.let { return it }
        // "mpv" gibi çıplak bir komut adı verilmişse PATH'te ara
        if (!value.contains('/') && !value.contains('\\')) {
            return searchPath(if (windows && !value.endsWith(".exe", true)) "$value.exe" else value, pathEnv, windows)
        }
        return null
    }

    private fun searchPath(exeName: String, pathEnv: String?, windows: Boolean): File? {
        if (pathEnv.isNullOrBlank()) return null
        return pathEnv.split(File.pathSeparatorChar)
            .asSequence()
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { usable(File(it, exeName), windows) }
    }

    private fun windowsCandidates(home: String): List<File> {
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val programData = System.getenv("ProgramData") ?: "C:\\ProgramData"
        val chocolatey = System.getenv("ChocolateyInstall")?.takeIf { it.isNotBlank() }
            ?: "$programData\\chocolatey"
        val scoop = System.getenv("SCOOP")?.takeIf { it.isNotBlank() } ?: "$home\\scoop"
        return listOf(
            File(scoop, "apps\\mpv\\current\\mpv.exe"),
            File(scoop, "shims\\mpv.exe"),
            File(programFiles, "mpv\\mpv.exe"),
            File(programFilesX86, "mpv\\mpv.exe"),
            File(chocolatey, "bin\\mpv.exe"),
        )
    }

    private fun linuxCandidates(): List<File> = listOf(File("/usr/bin/mpv"), File("/usr/local/bin/mpv"))

    private fun usable(file: File, windows: Boolean): File? =
        file.takeIf { it.isFile && (windows || it.canExecute()) }

    /** `flatpak info io.mpv.Mpv` başarılıysa kurulu sayılır. */
    private fun isFlatpakMpvInstalled(): Boolean = try {
        val process = ProcessBuilder("flatpak", "info", FLATPAK_APP_ID)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (process.waitFor(5, TimeUnit.SECONDS)) {
            process.exitValue() == 0
        } else {
            process.destroyForcibly()
            false
        }
    } catch (_: Exception) {
        false
    }
}
