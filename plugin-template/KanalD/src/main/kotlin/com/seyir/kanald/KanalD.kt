package com.seyir.kanald

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Kanal D (kanald.com.tr): dizilerin ve programların bölümleri sitede ücretsiz yayımlanır.
 * Bölüm sayfasındaki JSON-LD verisinde (`contentUrl`) doğrudan HLS adresi bulunur.
 */
class KanalD : MainAPI() {
    override var mainUrl = "https://www.kanald.com.tr"
    override var name = "Kanal D (örnek)"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Güncel Diziler",
        "$mainUrl/diziler/arsiv" to "Arşiv Dizileri",
        "$mainUrl/programlar" to "Programlar",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Yalnız arşiv sayfalıdır; öteki listeler tek sayfa
        val paged = request.data.endsWith("/arsiv")
        if (page > 1 && !paged) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val url = if (paged && page > 1) "${request.data}?page=$page" else request.data
        val items = listing(app.get(url).document)
        return newHomePageResponse(request.name, items, hasNext = paged && items.isNotEmpty())
    }

    /** Dizi/program kartları: `section.listing-holder div.item a`. */
    private fun listing(document: Document): List<SearchResponse> =
        document.select("section.listing-holder div.item a[href]")
            .mapNotNull { it.toSeries() }
            .distinctBy { it.url }

    private fun Element.toSeries(): SearchResponse? {
        val href = attr("href")
        // Yalnız "/dizi-adi" biçimindeki bağlantılar dizi sayfasıdır
        if (!Regex("""^/[a-z0-9-]+/?$""").matches(href)) return null
        val img = selectFirst("img[data-src]") ?: selectFirst("img")
        val title = img?.attr("alt")?.takeIf { it.isNotBlank() } ?: selectFirst("figcaption")?.text() ?: return null
        val poster = img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        return newTvSeriesSearchResponse(title, mainUrl + href.trimEnd('/'), TvType.TvSeries) {
            posterUrl = fixUrlNull(poster)
        }
    }

    // ---- arama ----

    @Volatile
    private var catalog: List<SearchResponse>? = null

    /**
     * Sitenin araması bölüm kartı döndürür (dikey afiş yok). Önce bütün dizi ve program listesi
     * (güncel, arşiv, programlar) bir kez okunur ve adla süzülür; bulunmazsa sitenin aramasına bakılır.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase(TR)
        val all = catalog ?: buildCatalog().also { catalog = it }
        val matches = all.filter { titleMatches(it.name, query) }
        if (matches.isNotEmpty()) return matches
        return siteSearch(query)
    }

    private suspend fun buildCatalog(): List<SearchResponse> {
        val pages = mutableListOf("$mainUrl/diziler", "$mainUrl/programlar", "$mainUrl/diziler/arsiv")
        val result = mutableListOf<SearchResponse>()
        for (url in pages) result += runCatching { listing(app.get(url).document) }.getOrDefault(emptyList())
        // Arşivin kalan sayfaları (boş sayfa gelene kadar, en çok 20)
        for (page in 2..20) {
            val items = runCatching { listing(app.get("$mainUrl/diziler/arsiv?page=$page").document) }.getOrDefault(emptyList())
            if (items.isEmpty() || items.all { item -> result.any { it.url == item.url } }) break
            result += items
        }
        return result.distinctBy { it.url }
    }

    private suspend fun siteSearch(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/arama", params = mapOf("q" to query)).document
        return document.select("a.video-card[href]").mapNotNull { card ->
            val slug = card.attr("href").trim('/').substringBefore('/').ifBlank { return@mapNotNull null }
            val title = card.selectFirst("p.spot")?.text()?.ifBlank { null } ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, "$mainUrl/$slug", TvType.TvSeries) {
                posterUrl = fixUrlNull(card.selectFirst("img[data-src]")?.attr("data-src"))
            }
        }.distinctBy { it.url }
    }

    // ---- dizi sayfası ----

    override suspend fun load(url: String): LoadResponse {
        val base = url.trimEnd('/')
        val document = app.get(base).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" İzle")?.substringBefore(" - Kanal D")?.trim()
            ?.ifBlank { null } ?: base.substringAfterLast('/')
        val slug = base.substringAfterLast('/')
        // Dikey afiş, sitenin menüsündeki dizi listesinde bu dizinin kartındadır (209x299)
        val poster = document.select("a[href=/$slug] img").firstNotNullOfOrNull { img ->
            listOf(img.attr("data-src"), img.attr("src")).firstOrNull { "x299/" in it || "x365/" in it }
        }
        // Geniş manşet görseli (1200x0) arka plan olur; yoksa paylaşım görseli
        val backdrop = document.select("img").firstNotNullOfOrNull { img ->
            listOf(img.attr("data-src"), img.attr("src")).firstOrNull { "/1200x0/" in it }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        val first = app.get("$base/bolumler").document
        // Sezon bağlantıları: /dizi/bolumler?activeSeasonNumber=2&seasonCount=3
        val seasonLinks = first.select("a[href*=activeSeasonNumber]").mapNotNull { a ->
            val number = Regex("""activeSeasonNumber=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            number?.let { it to a.attr("href") }
        }.distinctBy { it.first }.sortedBy { it.first }

        val episodes = mutableListOf<Episode>()
        if (seasonLinks.isEmpty()) {
            episodes += collectEpisodes(base, "$base/bolumler", season = null, firstPage = first)
        } else {
            for ((season, href) in seasonLinks) {
                episodes += collectEpisodes(base, mainUrl + href.replace("&amp;", "&"), season, firstPage = null)
            }
        }

        return newTvSeriesLoadResponse(title, base, TvType.TvSeries, episodes.distinctBy { it.data }) {
            posterUrl = poster ?: backdrop
            backgroundPosterUrl = backdrop
            this.plot = plot
        }
    }

    /** Bir sezonun bütün sayfalarındaki bölümler (boş ya da tekrar eden sayfa gelince durur). */
    private suspend fun collectEpisodes(base: String, seasonUrl: String, season: Int?, firstPage: Document?): List<Episode> {
        val slug = base.substringAfterLast('/')
        val seen = linkedMapOf<String, Episode>()
        for (page in 1..15) {
            val document = if (page == 1 && firstPage != null) firstPage else {
                val separator = if ('?' in seasonUrl) '&' else '?'
                app.get(if (page == 1) seasonUrl else "$seasonUrl${separator}page=$page").document
            }
            val cards = document.select("a[href^=/$slug/bolumler/]")
            val before = seen.size
            for (card in cards) {
                val href = mainUrl + card.attr("href")
                if (href in seen) continue
                val img = card.selectFirst("img[data-src]") ?: card.selectFirst("img")
                val name = img?.attr("alt")?.ifBlank { null } ?: card.selectFirst("h3")?.text()
                seen[href] = newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = episodeNumber(href, name)
                    this.posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
                }
            }
            if (seen.size == before) break
        }
        return seen.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun episodeNumber(href: String, name: String?): Int? =
        Regex("""(\d+)\.\s*Bölüm""").find(name.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""-(\d+)-bolum""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (href.endsWith("-ilk-bolum")) 1 else null

    // ---- video ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(data).text
        val path = Regex(""""contentUrl"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: return false
        // Eski bölümlerde adres görelidir ("S11/HLS_VOD/…/index.m3u8"); kökü sayfadaki MediaCdnDomainUrl verir
        val stream = if (path.startsWith("http")) path else {
            val cdn = Regex("""MediaCdnDomainUrl\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: DEFAULT_CDN
            "${cdn.trimEnd('/')}/${path.trimStart('/')}"
        }
        callback(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                referer = "$mainUrl/"
                quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private companion object {
        val TR: Locale = Locale.forLanguageTag("tr")
        const val DEFAULT_CDN = "https://kanaldvod.duhnet.tv"
    }
}
