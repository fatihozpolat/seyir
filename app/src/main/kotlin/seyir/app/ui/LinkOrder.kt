package seyir.app.ui

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Kaynaklar penceresinde gösterilen sıra: kaliteye göre azalan, aynı adres tek kez. Oynatma
 * sırasındaki yedek kaynak geçişi de aynı sırayı izler.
 */
fun orderedLinks(links: List<ExtractorLink>): List<ExtractorLink> =
    links.sortedByDescending { qualityOf(it) }.distinctBy { it.url }

/** Bağlantının kalitesi; eklenti vermediyse adındaki "1080p" ya da "1920x1080" kalıbından okunur. */
fun qualityOf(link: ExtractorLink): Int {
    if (link.quality > 0 && link.quality != Qualities.Unknown.value) return link.quality
    val match = Regex("""(?:\d{3,4}x(\d{3,4}))|(?:\b(\d{3,4})p\b)""").find(link.name) ?: return 0
    return (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull() ?: 0
}
