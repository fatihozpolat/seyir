package seyir.app

/** Bir sağlık kaydını yazan kaynak; öncelik sırası için gerekir ([HealthPolicy]). */
enum class HealthSource { PROBE, SEARCH, HOME }

/**
 * Bir sağlayıcının son bilinen durumu.
 *
 * @property source kaydı yazan kaynak; eski `health.json` dosyalarında yoktur (null), böyle kayıtlar
 *   Sına sonucu gibi korunur ve yalnız Sına ezebilir
 */
data class ProviderHealth(
    val ok: Boolean,
    val message: String? = null,
    val at: Long = System.currentTimeMillis(),
    val source: HealthSource? = null,
)

/**
 * Üç yazıcının (Sına, arama, ana sayfa) sağlık kaydı üzerindeki önceliği:
 *
 * - Sına tam bir uçtan uca denemedir; sonucu her zaman geçerlidir ve öncekini ezer.
 * - Arama ve ana sayfa yalnız gerçek bir hatada kaydı kırmızıya *çeker*; Sına'nın kırmızısını ezmez.
 * - Başarılı arama/ana sayfa yalnız kendi bildirdiği hatayı *temizler*; Sına kırmızısını yeşile çevirmez.
 * - Hiç kayıt yoksa başarı doğrudan yeşil kayıt olarak yazılır.
 */
object HealthPolicy {
    /** [report] uygulanınca ortaya çıkacak kayıt; değişiklik yoksa [current] aynen döner. */
    fun resolve(current: ProviderHealth?, report: ProviderHealth): ProviderHealth? {
        val source = report.source ?: return report
        if (source == HealthSource.PROBE) return report
        if (current == null) return report
        return if (report.ok) {
            // Başarı: yalnız aynı kaynağın kendi hatası temizlenir (yeşil kayıtlar zaten sağlam)
            if (!current.ok && current.source == source) report else current
        } else {
            // Hata: Sına (ya da kaynağı bilinmeyen eski) kırmızısı korunur; ötekiler en yeni hatayla değişir
            if (!current.ok && (current.source == HealthSource.PROBE || current.source == null)) current else report
        }
    }
}
