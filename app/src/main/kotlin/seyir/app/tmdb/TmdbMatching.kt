package seyir.app.tmdb

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** TMDB'deki iki içerik türü; [path] arama ve görsel adreslerindeki karşılığıdır. */
enum class TmdbKind(val path: String) {
    Movie("movie"),
    Tv("tv"),
}

/** TMDB arama sonucundan alınan, arayüzün ihtiyaç duyduğu alanlar. */
data class TmdbInfo(
    val id: Int,
    val kind: TmdbKind,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** 10 üzerinden puan; oy yoksa null. */
    val rating: Double? = null,
    val voteCount: Int = 0,
)

/** Eklentinin verdiği alanlara TMDB'den eksikler eklenmiş hâl; [fromTmdb] işaretleri kaynağı bildirir. */
data class MergedDetail(
    val posterUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val year: Int?,
    val score: Double?,
    val posterFromTmdb: Boolean,
    val backdropFromTmdb: Boolean,
    val scoreFromTmdb: Boolean,
)

private val YEAR_SUFFIX = Regex("""\s*\((?:19|20)\d{2}\)\s*$""")
private val YEAR_IN_NAME = Regex("""\(((?:19|20)\d{2})\)\s*$""")
private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
private val DIACRITICS = Regex("""\p{Mn}+""")

/** Sitelerin başlığa eklediği gürültü sözcükleri (normalleştirilmiş, sondan atılır). */
private val TRAILING_NOISE = listOf("full izle", "izle", "turkce dublaj", "turkce altyazili", "altyazili", "dublaj", "watch online")

/**
 * Başlıkları karşılaştırmak için sadeleştirir: büyük/küçük harf, aksan (Türkçe İ/ı dahil),
 * noktalama, sondaki "(2019)" yılı ve sitelerin eklediği "izle" gibi son ekler yok sayılır.
 */
fun normalizeTitle(text: String): String {
    val noYear = text.replace(YEAR_SUFFIX, "")
    val lower = noYear.replace('ı', 'i').replace('İ', 'i').lowercase(Locale.ROOT)
    val plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(DIACRITICS, "")
    var result = plain.replace('&', ' ').replace(NON_ALNUM, " ").trim()
    var changed = true
    while (changed) {
        changed = false
        for (noise in TRAILING_NOISE) {
            if (result.endsWith(" $noise")) {
                result = result.removeSuffix(noise).trim()
                changed = true
            }
        }
    }
    return result
}

/** Başlığın sonundaki "(2019)" yılını verir; yoksa null. */
fun yearInName(name: String): Int? = YEAR_IN_NAME.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

/**
 * Sağlayıcıdaki içerik türüne göre TMDB'de aranacak türler. Müzik, canlı yayın gibi TMDB'de
 * karşılığı olmayanlar için boş liste döner; belirsiz türlerde ([episodeCount] ipucu olur) ikisi de aranır.
 */
fun kindsFor(typeName: String?, episodeCount: Int = 0): List<TmdbKind> = when (typeName) {
    "Movie", "AnimeMovie" -> listOf(TmdbKind.Movie)
    "TvSeries", "Anime", "OVA", "AsianDrama" -> listOf(TmdbKind.Tv)
    "Live", "Music", "AudioBook", "Audio", "Podcast", "NSFW", "CustomMedia", "Video" -> emptyList()
    else -> if (episodeCount > 1) listOf(TmdbKind.Tv) else listOf(TmdbKind.Movie, TmdbKind.Tv)
}

/**
 * Aday listesinden en iyi eşleşmeyi seçer; emin olunamıyorsa null verir (yanlış afiş göstermektense
 * hiçbir şey eklememek yeğdir).
 *
 * Kurallar: normalleştirilmiş başlık, TMDB başlığı ya da özgün başlıkla tam eşleşmeli. Yıl biliniyorsa
 * (parametre ya da başlıktaki "(2019)") aday en çok bir yıl farklı olabilir ve tam yıl eşleşmesi tek
 * olmalı (tam eşleşme yoksa tek yakın aday kabul edilir). Yıl bilinmiyorsa başlık eşleşmesi tek olmalı.
 */
fun pickBest(candidates: List<TmdbInfo>, title: String, year: Int? = null): TmdbInfo? {
    val query = normalizeTitle(title)
    if (query.isEmpty()) return null
    val titled = candidates.filter { c ->
        query == normalizeTitle(c.title) || (c.originalTitle != null && query == normalizeTitle(c.originalTitle))
    }
    if (titled.isEmpty()) return null
    val wanted = year ?: yearInName(title)
    if (wanted == null) return titled.singleOrNull()
    val near = titled.filter { c -> c.year != null && abs(c.year - wanted) <= 1 }
    if (near.isEmpty()) return null
    val exact = near.filter { it.year == wanted }
    return when {
        exact.size == 1 -> exact.first()
        exact.isEmpty() && near.size == 1 -> near.first()
        else -> null
    }
}

/**
 * Eklentinin verdiği değerler her zaman önceliklidir; yalnız boş ya da yok olanlar [tmdb]'den doldurulur.
 *
 * @param providerBackdrop eklentinin yatay arka planı (afişle aynıysa çağıran null vermelidir)
 */
fun mergeDetail(
    providerPoster: String?,
    providerBackdrop: String?,
    providerPlot: String?,
    providerYear: Int?,
    providerScore: Double?,
    tmdb: TmdbInfo?,
): MergedDetail {
    val poster = providerPoster?.takeIf { it.isNotBlank() }
    val backdrop = providerBackdrop?.takeIf { it.isNotBlank() }
    val plot = providerPlot?.takeIf { it.isNotBlank() }
    val score = providerScore?.takeIf { it > 0.0 }
    val year = providerYear?.takeIf { it > 0 }
    return MergedDetail(
        posterUrl = poster ?: tmdb?.posterUrl,
        backdropUrl = backdrop ?: tmdb?.backdropUrl,
        plot = plot ?: tmdb?.overview?.takeIf { it.isNotBlank() },
        year = year ?: tmdb?.year,
        score = score ?: tmdb?.rating,
        posterFromTmdb = poster == null && tmdb?.posterUrl != null,
        backdropFromTmdb = backdrop == null && tmdb?.backdropUrl != null,
        scoreFromTmdb = score == null && tmdb?.rating != null,
    )
}

/** TMDB'nin `language` parametresi: arayüz Türkçeyse tr-TR, değilse en-US. */
fun tmdbLanguage(locale: Locale): String = if (locale.language == "tr") "tr-TR" else "en-US"

/**
 * Anahtarın v4 okuma erişim belirteci (JWT, `Authorization: Bearer`) mi yoksa v3 API anahtarı
 * (32 onaltılı hane, `api_key` sorgu parametresi) mı olduğunu biçimden ayırır.
 */
fun isBearerToken(key: String): Boolean = key.trim().let { it.startsWith("eyJ") && it.count { c -> c == '.' } == 2 }
