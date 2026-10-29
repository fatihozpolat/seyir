package seyir.core

import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.SimpleFormatter

/**
 * Basit günlük çıktısı. Seviye `SEYIR_LOG` ortam değişkeni veya `-Dseyir.log=` ile seçilir:
 * verbose, debug, info (varsayılan), warn, error. Ortamdan seçilen seviye ayarlardaki seviyeye
 * baskındır ([setLevelByName] onu ezmez); geliştirme sırasında ayrıntıyı zorlamak için.
 */
object SeyirLog {
    private val levelNames = mapOf(2 to "V", 3 to "D", 4 to "I", 5 to "W", 6 to "E", 7 to "A")

    private val environmentLevel: String? = System.getProperty("seyir.log") ?: System.getenv("SEYIR_LOG")

    @Volatile
    var minLevel: Int = parseLevel(environmentLevel)
        set(value) {
            field = value
            JulBridge.syncLevel(value)
        }

    fun isEnabled(level: Int) = level >= minLevel

    /** Ayarlardaki seviye adını ("error", "warn", "info", "debug") uygular; ortamdan seçilmişse dokunmaz. */
    fun setLevelByName(name: String?) {
        if (environmentLevel != null) return
        minLevel = parseLevel(name)
    }

    fun log(level: Int, tag: String, msg: String?, tr: Throwable? = null) {
        if (!isEnabled(level)) return
        val line = buildString {
            // Gecikme teşhisi için saat (ör. aktarıcıdaki upstream süreleriyle karşılaştırma)
            append(LocalTime.now().truncatedTo(ChronoUnit.MILLIS)).append(' ')
            append(levelNames[level] ?: "?").append('/').append(tag).append(": ").append(msg ?: "")
            if (tr != null) append('\n').append(tr.stackTraceToString())
        }
        System.err.println(line)
    }

    fun d(tag: String, msg: String) = log(3, tag, msg)
    fun i(tag: String, msg: String) = log(4, tag, msg)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(5, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(6, tag, msg, tr)

    /** `java.util.logging` çıktısını (OkHttp, JDK, kütüphaneler) bu günlüğe yönlendirir; tekrar çağrılabilir. */
    fun installJulBridge() = JulBridge.install()

    internal fun parseLevel(value: String?): Int = when (value?.lowercase()) {
        "verbose", "v" -> 2
        "debug", "d" -> 3
        "warn", "warning", "w" -> 5
        "error", "e" -> 6
        else -> 4
    }

    /** JUL kayıtlarını [SeyirLog] satırlarına çevirir; gürültülü JDK/OkHttp kayıtları bir kademe aşağı alınır. */
    internal object JulBridge {
        private val noisyPrefixes = listOf("okhttp3", "okio", "sun.", "jdk.", "java.", "javax.", "com.sun.")

        private val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                val level = map(record.loggerName.orEmpty(), record.level)
                if (!isEnabled(level)) return
                val message = runCatching { SimpleFormatter().formatMessage(record) }.getOrDefault(record.message)
                log(level, tagOf(record.loggerName), message, record.thrown)
            }

            override fun flush() = Unit
            override fun close() = Unit
        }

        @Synchronized
        fun install() {
            val root = LogManager.getLogManager().getLogger("")
            root.handlers.forEach { root.removeHandler(it) }
            root.addHandler(handler)
            syncLevel(minLevel)
        }

        /** Kayıtlar kaynağında elensin diye JUL kök seviyesi [SeyirLog] seviyesini izler. */
        fun syncLevel(level: Int) {
            val root = LogManager.getLogManager().getLogger("") ?: return
            root.level = when {
                level <= 2 -> Level.ALL
                level == 3 -> Level.FINE
                level == 4 -> Level.INFO
                level == 5 -> Level.WARNING
                else -> Level.SEVERE
            }
        }

        fun map(logger: String, level: Level): Int {
            val value = level.intValue()
            val noisy = noisyPrefixes.any { logger.startsWith(it) }
            return when {
                value >= Level.SEVERE.intValue() -> 6
                value >= Level.WARNING.intValue() -> 5
                // INFO ve altı: yalnız kendi kodumuzdaki INFO bilgi sayılır, kütüphane gürültüsü debug'a düşer
                value >= Level.INFO.intValue() -> if (noisy) 3 else 4
                value >= Level.FINE.intValue() -> 3
                else -> 2
            }
        }

        private fun tagOf(logger: String?): String =
            logger?.takeIf { it.isNotBlank() }?.let { name -> name.substringAfterLast('.').ifBlank { name } } ?: "JUL"
    }
}
