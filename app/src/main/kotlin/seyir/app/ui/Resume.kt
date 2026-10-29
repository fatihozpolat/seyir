package seyir.app.ui

import seyir.app.data.WatchEntry
import seyir.app.i18n.I18n
import seyir.core.Playable

/**
 * Birincil düğmenin oynatacağı öğe: yarım kalan bölüm, bitmişse sıradaki, hiç izlenmemişse ilki.
 * [progress] öğe verisi → izleme kaydı eşlemesidir.
 */
fun resumeTarget(items: List<Playable>, progress: Map<String, WatchEntry>): Playable? {
    val last = progress.values.maxByOrNull { it.updatedAt }
    return when {
        last == null -> items.firstOrNull()
        !last.finished -> items.firstOrNull { it.data == last.itemData } ?: items.firstOrNull()
        else -> items.dropWhile { it.data != last.itemData }.drop(1).firstOrNull() ?: items.firstOrNull()
    }
}

/** Birincil düğme metni: "Devam et 12:04 · S1 B3", "İzle · S1 B1" ya da "İzle" (İngilizce: "Resume 12:04 · S1 E3"). */
fun playLabel(item: Playable, entry: WatchEntry?, isSeries: Boolean): String {
    val what = if (isSeries) episodeCode(item) else null
    val t = I18n.current
    val base = if (entry != null && !entry.finished && entry.position > 30) t.resumeAt(formatTime(entry.position)) else t.watch
    return listOfNotNull(base, what).joinToString(" · ")
}

/** Kısa bölüm kodu (S1 B3); numara yoksa öğenin adı. */
fun episodeCode(item: Playable): String = I18n.current.let { t ->
    when {
        item.season != null && item.episode != null -> "${t.seasonShort(item.season!!)} ${t.episodeShort(item.episode!!)}"
        item.episode != null -> t.episodeNumbered(item.episode!!)
        else -> item.title
    }
}

/** Bölüm listesinde gösterilecek ad: numara önekleri atılmış başlık. */
fun episodeName(item: Playable): String {
    val name = item.title.substringAfter(" · ", "")
    val t = I18n.current
    return when {
        // Sitenin verdiği "1. Sezon 3. Bölüm" adı sezon sekmesinde tekrar olur
        item.episode != null && GENERIC_EPISODE.matches(name) -> t.episodeNumbered(item.episode!!)
        name.isNotBlank() -> name
        item.episode != null -> t.episodeNumbered(item.episode!!)
        else -> item.title
    }
}

private val GENERIC_EPISODE = Regex("""(?i)^\s*\d+\.?\s*sezon\s*\d+\.?\s*bölüm\s*$""")

fun formatTime(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
