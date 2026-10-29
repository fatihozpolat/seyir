package seyir.app.data

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import seyir.core.AtomicFile
import seyir.core.SeyirLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Küçük JSON dosyaları için ortak okuma/yazma. Yazım benzersiz geçici dosya + atomik taşıma ile
 * yapılır; bozuk dosya sessizce ezilmez, yedeklenir.
 */
internal object JsonStore {
    val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // T::class.java genel türü siler (Map<String, X> → Map<String, LinkedHashMap>); jacksonTypeRef korur
    inline fun <reified T> read(file: File, noinline default: () -> T): T = read(file, jacksonTypeRef<T>(), default)

    fun <T> read(file: File, type: TypeReference<T>, default: () -> T): T {
        if (!file.exists()) return default()
        return try {
            mapper.readValue(file, type) ?: default()
        } catch (e: JsonProcessingException) {
            // Bozuk dosya bir sonraki yazımda ezilip geçmişi yok etmesin diye kenara alınır
            val backup = backupCorrupt(file)
            SeyirLog.w("JsonStore", "${file.name} bozuk, ${backup?.name ?: "yedeklenemedi"} olarak ayrıldı; varsayılan kullanılıyor", e)
            default()
        } catch (e: Exception) {
            SeyirLog.w("JsonStore", "${file.name} okunamadı, varsayılan kullanılıyor", e)
            default()
        }
    }

    /** Bozuk dosyayı `<ad>.corrupt-<zaman>` adıyla yanına taşır; taşınamazsa null. */
    fun backupCorrupt(file: File, now: Long = System.currentTimeMillis()): File? {
        val backup = File(file.absoluteFile.parentFile, "${file.name}.corrupt-$now")
        return runCatching {
            synchronized(AtomicFile.lockFor(file)) {
                Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            backup
        }.getOrNull()
    }

    /** [value]'yu dosyaya hemen (eşzamanlı) yazar; hata günlüğe düşer, çağırana fırlatılmaz. */
    fun write(file: File, value: Any, pretty: Boolean = true) {
        runCatching {
            AtomicFile.replace(file) { tmp ->
                val writer = if (pretty) mapper.writerWithDefaultPrettyPrinter() else mapper.writer()
                writer.writeValue(tmp, value)
            }
        }.onFailure { SeyirLog.w("JsonStore", "${file.name} yazılamadı", it) }
    }

    /** Bekleyen bütün gecikmeli yazımları hemen diske işler (uygulama kapanırken çağrılır). */
    fun flushAll() = DebouncedWriter.flushAll()
}

/**
 * Sık değişen bir dosya için yazımları birleştirir: [schedule] çağrıları [delayMs] içinde tek
 * yazıma iner ve yazım arka plandaki tek iş parçacığında yapılır; çağıran (arayüz ya da mpv
 * dinleyicisi) disk beklemez. [snapshot] yazım anında güncel değeri verir, dosyaya o yazılır.
 */
internal class DebouncedWriter(
    private val file: File,
    private val delayMs: Long = 500,
    private val pretty: Boolean = false,
    private val snapshot: () -> Any,
) {
    private val dirty = AtomicBoolean(false)
    private val flushLock = Any()
    private var task: ScheduledFuture<*>? = null

    init {
        registry.add(this)
    }

    /** Dosyanın yeniden yazılması gerektiğini bildirir; yazım kısa süre sonra yapılır. */
    fun schedule() {
        dirty.set(true)
        synchronized(this) {
            if (task == null) task = scheduler.schedule(::onTimer, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Bekleyen değişiklik varsa şimdi, bu iş parçacığında yazar. */
    fun flush() {
        synchronized(this) {
            task?.cancel(false)
            task = null
        }
        synchronized(flushLock) {
            if (dirty.getAndSet(false)) JsonStore.write(file, snapshot(), pretty)
        }
    }

    private fun onTimer() {
        // Yazım sürerken gelen yeni değişiklik yeni bir zamanlayıcı kurabilsin diye önce bırakılır
        synchronized(this) { task = null }
        flush()
    }

    companion object {
        private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "seyir-json-writer").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        private val registry: MutableSet<DebouncedWriter> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        init {
            // Kapanış yolu atlanırsa (ör. süreç sonlandırma sinyali) bekleyen yazımlar yine de işlenir
            Runtime.getRuntime().addShutdownHook(Thread({ flushAll() }, "seyir-json-flush"))
        }

        fun flushAll() {
            val writers = synchronized(registry) { registry.toList() }
            writers.forEach { runCatching { it.flush() } }
        }
    }
}
