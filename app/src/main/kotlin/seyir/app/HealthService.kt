package seyir.app

import androidx.compose.runtime.mutableStateMapOf
import seyir.app.data.DebouncedWriter
import seyir.app.data.JsonStore
import seyir.core.AppDirs
import java.io.File

/** Sağlayıcıların sağlık kaydının tek sahibi; kayıtlar `health.json`'a birleştirilerek yazılır. */
class HealthService {
    private val file = File(AppDirs.data, "health.json")

    /** Sağlayıcı adına göre son bilinen durum (Compose durumu); Eklentiler ekranı okur. */
    val records = mutableStateMapOf<String, ProviderHealth>().apply {
        putAll(JsonStore.read<Map<String, ProviderHealth>>(file) { emptyMap() })
    }

    // Bir aramada onlarca sağlayıcı bildirim yapar; yazımlar birleştirilip arka planda yapılır
    private val writer = DebouncedWriter(file) { records.toMap() }

    /**
     * Sağlayıcının çalışıp çalışmadığını kaydeder; Eklentiler ekranında gösterilir. Hangi kaynağın
     * ([source]) neyi ezebileceği [HealthPolicy]'de tanımlıdır.
     */
    @Synchronized
    fun report(provider: String, ok: Boolean, message: String? = null, source: HealthSource = HealthSource.PROBE) {
        val report = ProviderHealth(ok, message?.take(200), source = source)
        val next = HealthPolicy.resolve(records[provider], report) ?: return
        if (next === records[provider]) return
        records[provider] = next
        writer.schedule()
    }

    /** Kaldırılan sağlayıcının sağlık kaydını siler. */
    fun clear(provider: String) {
        records.remove(provider)
        writer.schedule()
    }
}
