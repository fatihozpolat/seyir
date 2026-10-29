package seyir.core

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse

/** Oynatılabilir tek bir öğe: film, bölüm veya canlı yayın. [data] doğrudan loadLinks'e verilir. */
data class Playable(
    val title: String,
    val data: String,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
)

/**
 * Bölüm başlıklarındaki kısa sezon/bölüm kodlarının biçimi ("S1 B2" ya da "S1 E2").
 * Çekirdek arayüz dilini bilmez; uygulama kendi dilindeki biçimi verir.
 */
interface EpisodeLabels {
    fun season(n: Int): String
    fun episode(n: Int): String
}

/** Varsayılan (komut satırı aracı için) Türkçe biçim: "S1 B2". */
object TurkishEpisodeLabels : EpisodeLabels {
    override fun season(n: Int) = "S$n"
    override fun episode(n: Int) = "B$n"
}

/** Bir detay yanıtındaki oynatılabilir öğeleri sezon/bölüm sırasıyla döndürür. */
fun LoadResponse.playables(labels: EpisodeLabels = TurkishEpisodeLabels): List<Playable> = when (this) {
    is MovieLoadResponse -> listOf(Playable(name, dataUrl, posterUrl = posterUrl))
    is LiveStreamLoadResponse -> listOf(Playable(name, dataUrl, posterUrl = posterUrl))
    is TorrentLoadResponse -> listOfNotNull(magnet ?: torrent)
        .map { Playable(name, it, posterUrl = posterUrl) }
    is TvSeriesLoadResponse -> episodes.toPlayables(name, labels)
    is AnimeLoadResponse -> episodes.values.flatten().toPlayables(name, labels)
    else -> emptyList()
}

internal fun List<com.lagradost.cloudstream3.Episode>.toPlayables(showName: String, labels: EpisodeLabels): List<Playable> =
    sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        .map { ep ->
            val label = buildString {
                if (ep.season != null) append(labels.season(ep.season!!)).append(' ')
                if (ep.episode != null) append(labels.episode(ep.episode!!))
                if (!ep.name.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(ep.name)
                }
            }.ifBlank { showName }
            Playable(label, ep.data, ep.season, ep.episode, ep.posterUrl)
        }

/**
 * Sıralı listede [current]'ten sonra oynatılacak öğe; yoksa null. Sezon sonunda sıradaki sezonun
 * ilk bölümüne geçer (liste sezon/bölüm sırasındadır). Eklentiler aynı bölüm adresini art arda
 * verebildiği için [current] ile aynı veriyi taşıyan öğeler atlanır. Tek öğeli listede (film)
 * ya da [current] listede yoksa sıradaki yoktur.
 */
fun List<Playable>.nextAfter(current: Playable): Playable? {
    if (size < 2) return null
    // Aynı adres listede birden çok kez geçebilir; sezon/bölüm numarası eşleşeni tercih et
    val index = indexOfFirst { it.data == current.data && it.season == current.season && it.episode == current.episode }
        .takeIf { it >= 0 } ?: indexOfFirst { it.data == current.data }
    if (index < 0) return null
    return drop(index + 1).firstOrNull { it.data != current.data }
}

/**
 * Sıralı listede [current]'ten önceki öğe; yoksa null. [nextAfter]'ın tersidir: sezon başında önceki
 * sezonun son bölümüne döner, [current] ile aynı veriyi taşıyan öğeler atlanır.
 */
fun List<Playable>.previousBefore(current: Playable): Playable? {
    if (size < 2) return null
    val index = indexOfFirst { it.data == current.data && it.season == current.season && it.episode == current.episode }
        .takeIf { it >= 0 } ?: indexOfFirst { it.data == current.data }
    if (index < 0) return null
    return take(index).lastOrNull { it.data != current.data }
}
