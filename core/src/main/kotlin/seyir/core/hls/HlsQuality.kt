package seyir.core.hls

/** Kullanıcının kalite isteği. */
sealed interface QualityTarget {
    /** Mevcut en yüksek katman (mpv'nin varsayılanı). */
    data object Highest : QualityTarget

    /** Mevcut en düşük görüntülü katman (veri tasarrufu). */
    data object Lowest : QualityTarget

    /** Yüksekliği [height]'i aşmayan en yüksek katman; hiçbiri yoksa en düşük katman. */
    data class MaxHeight(val height: Int) : QualityTarget
}

/** Ayarlardaki "Varsayılan kalite" seçenekleri; [id] ayar dosyasında saklanır. */
enum class QualityPreference(val id: String, val target: QualityTarget) {
    HIGHEST("highest", QualityTarget.Highest),
    P1080("p1080", QualityTarget.MaxHeight(1080)),
    P720("p720", QualityTarget.MaxHeight(720)),
    P480("p480", QualityTarget.MaxHeight(480)),
    LOWEST("lowest", QualityTarget.Lowest);

    companion object {
        /** Bilinmeyen ya da boş değerde varsayılan olan [HIGHEST] döner. */
        fun fromId(id: String?): QualityPreference = entries.firstOrNull { it.id == id } ?: HIGHEST
    }
}

/** Ham katmanlardan kullanıcıya gösterilecek katman listesi ve seçim mantığı. */
object HlsQuality {
    /**
     * Bant genişliğinden yükseklik tahmini (çözünürlük vermeyen listeler için). Tipik H.264 merdiveninin
     * tepe bant genişliklerine göre kabaca bölünmüştür; yalnız etiket ve tercih eşlemesi içindir.
     */
    fun estimateHeight(bandwidth: Long): Int = when {
        bandwidth >= 14_000_000 -> 2160
        bandwidth >= 6_500_000 -> 1080
        bandwidth >= 3_000_000 -> 720
        bandwidth >= 1_400_000 -> 480
        bandwidth >= 700_000 -> 360
        else -> 240
    }

    /**
     * Seçilebilir katmanlar: ses katmanları ve bant genişliği bilinmeyenler atılır, aynı yükseklikte
     * birden çok katman varsa en yüksek bant genişlikli olan kalır. Yüksekten alçağa sıralıdır.
     */
    fun layers(variants: List<HlsVariant>): List<HlsVariant> =
        variants
            .filter { !it.audioOnly && it.bandwidth > 0 }
            .groupBy { it.effectiveHeight }
            .map { (_, group) -> group.maxBy { it.bandwidth } }
            .sortedByDescending { it.effectiveHeight }

    /** [layers] listesinden isteğe en uygun katman; liste boşsa null. */
    fun choose(layers: List<HlsVariant>, target: QualityTarget): HlsVariant? {
        if (layers.isEmpty()) return null
        val sorted = layers.sortedByDescending { it.effectiveHeight }
        return when (target) {
            QualityTarget.Highest -> sorted.first()
            QualityTarget.Lowest -> sorted.last()
            is QualityTarget.MaxHeight -> sorted.firstOrNull { it.effectiveHeight <= target.height } ?: sorted.last()
        }
    }

    /**
     * mpv'ye verilecek ek seçenekler. mpv `--hls-bitrate=<sayı>` ile bant genişliği o sayıyı aşmayan en
     * yüksek katmanı açar; katmanın kendi BANDWIDTH değeri verildiği için tam o katman seçilir.
     * "En yüksek" mpv'nin varsayılanı olduğundan seçenek eklenmez. Düşük katman da `min` yerine açık
     * değerle istenir: `min` yalnız ses içeren bir katmanı seçebilir.
     */
    fun mpvArgs(layers: List<HlsVariant>, target: QualityTarget): List<String> {
        if (target == QualityTarget.Highest) return emptyList()
        val chosen = choose(layers, target) ?: return emptyList()
        return listOf("--hls-bitrate=${chosen.bandwidth}")
    }
}
