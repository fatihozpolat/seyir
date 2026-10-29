package seyir.app

import java.text.Normalizer

/** Gruplamaya giren tek bir sonuç: hangi sağlayıcıdan geldiği ve başlık/yıl bilgisi. */
data class GroupEntry<T>(val provider: String, val item: T, val title: String, val year: Int?)

/**
 * Aynı yapımı bulan sağlayıcıların tek kartta toplanmış hali. [entries] giriş sırasını korur;
 * ilki "en iyi" sağlayıcıdır ve kart tıklanınca o açılır.
 */
data class TitleGroup<T>(val key: String, val year: Int?, val entries: List<GroupEntry<T>>) {
    val primary: GroupEntry<T> get() = entries.first()

    /** Yapımı bulan sağlayıcılar (tekrarsız, ilk görülme sırasıyla). */
    val providers: List<String> get() = entries.map { it.provider }.distinct()
}

private val trailingYear = Regex("""\s*\((?:19|20)\d{2}\)\s*$""")
private val trailingYearDigits = Regex("""\(((?:19|20)\d{2})\)\s*$""")

/**
 * Başlığı karşılaştırmaya hazırlar: sondaki "(2019)" atılır; büyük/küçük harf, aksan ve noktalama
 * yok sayılır; Türkçe İ/ı/ş/ğ/ç/ö/ü harfleri karşılıklarına (i/s/g/c/o/u) katlanır.
 */
fun normalizeTitle(title: String): String {
    val folded = buildString(title.length) {
        for (ch in title.replace(trailingYear, "")) {
            when (ch) {
                'İ', 'I', 'ı' -> append('i')
                'Ş', 'ş' -> append('s')
                'Ğ', 'ğ' -> append('g')
                'Ç', 'ç' -> append('c')
                'Ö', 'ö' -> append('o')
                'Ü', 'ü' -> append('u')
                else -> append(ch)
            }
        }
    }
    return Normalizer.normalize(folded, Normalizer.Form.NFD)
        .lowercase(java.util.Locale.ROOT)
        .filter { it.isLetterOrDigit() }
}

/** Başlığın sonundaki "(2019)" biçimindeki yılı döndürür; yoksa null. */
fun yearFromTitle(title: String): Int? = trailingYearDigits.find(title)?.groupValues?.get(1)?.toInt()

/**
 * Sağlayıcı sonuçlarını normalleştirilmiş başlığa göre gruplar. Aynı başlıkta iki yapımın da yılı
 * biliniyorsa yılları aynı olmalıdır (yeniden çevrimler ayrı kalır); biri yılsızsa ad yeterlidir.
 * Girdi sırası korunur: grup sırası ilk üyenin görüldüğü sıradır, grup içinde de giriş sırası geçerlidir.
 * Normalleştirilmiş başlığı boş kalan sonuçlar kendi başına grup olur.
 *
 * @param results sağlayıcı adı ve o sağlayıcının sonuçları (en iyi sağlayıcı başta)
 */
fun <T> groupByTitle(
    results: List<Pair<String, List<T>>>,
    title: (T) -> String,
    year: (T) -> Int?,
): List<TitleGroup<T>> {
    class Bucket(val key: String, var year: Int?, val entries: MutableList<GroupEntry<T>> = mutableListOf())

    val buckets = mutableListOf<Bucket>()
    val byKey = HashMap<String, MutableList<Bucket>>()
    for ((provider, items) in results) {
        for (item in items) {
            val name = title(item)
            val entry = GroupEntry(provider, item, name, year(item))
            val key = normalizeTitle(name)
            if (key.isEmpty()) {
                buckets += Bucket("", entry.year).also { it.entries += entry }
                continue
            }
            val candidates = byKey.getOrPut(key) { mutableListOf() }
            val y = entry.year
            val target = if (y != null) {
                candidates.firstOrNull { it.year == y } ?: candidates.firstOrNull { it.year == null }
            } else {
                candidates.firstOrNull()
            }
            if (target != null) {
                target.entries += entry
                // Yılsız grup, yılı olan ilk üyeyle yıl kazanır; sonraki farklı yıllar ayrı grup olur
                if (target.year == null && y != null) target.year = y
            } else {
                val bucket = Bucket(key, y).also { it.entries += entry }
                candidates += bucket
                buckets += bucket
            }
        }
    }
    return buckets.map { TitleGroup(it.key, it.year, it.entries.toList()) }
}
