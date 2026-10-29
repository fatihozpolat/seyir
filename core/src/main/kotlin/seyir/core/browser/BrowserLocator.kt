package seyir.core.browser

import seyir.core.AppDirs
import seyir.core.SeyirLog
import java.io.File

/**
 * Sistemde kurulu Chromium tabanlı bir tarayıcı (Edge, Chrome, Brave, Chromium) bulur.
 * Önce kullanıcının ayarladığı yol denenir, olmazsa bilinen kurulum yerleri taranır.
 */
object BrowserLocator {
    private const val TAG = "BrowserLocator"

    /**
     * Çalıştırılabilir tarayıcı dosyasını döner; bulunamazsa null.
     * [configured] boş değilse ve dosya varsa doğrudan o kullanılır.
     */
    fun find(configured: String?): File? {
        val explicit = configured?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            val file = File(explicit)
            if (file.isFile) return file
            SeyirLog.w(TAG, "Ayarlanan tarayıcı yolu bulunamadı, otomatik aranacak: $explicit")
        }
        return candidates(AppDirs.isWindows, System.getenv(), File.pathSeparator).firstOrNull { it.isFile }
    }

    /** Aday dosyaları öncelik sırasıyla üretir (varlığını denetlemez). Testlerde doğrudan kullanılır. */
    internal fun candidates(isWindows: Boolean, env: Map<String, String?>, pathSeparator: String): List<File> =
        if (isWindows) windowsCandidates(env) else linuxCandidates(env, pathSeparator)

    private fun windowsCandidates(env: Map<String, String?>): List<File> {
        val programFiles = env["ProgramFiles"] ?: "C:\\Program Files"
        val programFilesX86 = env["ProgramFiles(x86)"] ?: "C:\\Program Files (x86)"
        val localAppData = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }

        // Edge her Windows 10/11'de kurulu geldiği için ilk sırada
        val edge = "Microsoft\\Edge\\Application\\msedge.exe"
        val chrome = "Google\\Chrome\\Application\\chrome.exe"
        val brave = "BraveSoftware\\Brave-Browser\\Application\\brave.exe"
        val chromium = "Chromium\\Application\\chrome.exe"

        val result = ArrayList<File>()
        fun add(root: String?, relative: String) {
            if (root != null) result += File(root, relative)
        }
        add(programFilesX86, edge)
        add(programFiles, edge)
        add(localAppData, edge)
        add(programFiles, chrome)
        add(programFilesX86, chrome)
        add(localAppData, chrome)
        add(programFiles, brave)
        add(programFilesX86, brave)
        add(localAppData, brave)
        add(programFiles, chromium)
        add(programFilesX86, chromium)
        add(localAppData, chromium)
        return result
    }

    private fun linuxCandidates(env: Map<String, String?>, pathSeparator: String): List<File> {
        val names = listOf(
            "google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
            "microsoft-edge", "brave-browser",
        )
        val dirs = LinkedHashSet<String>()
        env["PATH"]?.split(pathSeparator)?.filter { it.isNotBlank() }?.let { dirs += it }
        dirs += "/usr/bin"
        dirs += "/snap/bin"
        return names.flatMap { name -> dirs.map { File(it, name) } }
    }
}
