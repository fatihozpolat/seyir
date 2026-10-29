package seyir.core

import java.io.File

/** Uygulama verilerinin (ayarlar, eklentiler, önbellek) duracağı klasörler. */
object AppDirs {
    /** Windows: %APPDATA%\Seyir, Linux: $XDG_DATA_HOME/seyir (yoksa ~/.local/share/seyir). */
    val data: File by lazy {
        val override = System.getProperty("seyir.data") ?: System.getenv("SEYIR_DATA")
        val dir = when {
            override != null -> File(override)
            isWindows -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Seyir")
            else -> File(
                System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + "/.local/share"),
                "seyir",
            )
        }
        dir.apply { mkdirs() }
    }

    val plugins: File get() = File(data, "plugins").apply { mkdirs() }

    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
