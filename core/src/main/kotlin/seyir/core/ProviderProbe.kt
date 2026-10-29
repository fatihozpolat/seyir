package seyir.core

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import seyir.core.net.Network
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Sınama adımları; sıra: liste (ana sayfa ya da arama) → ayrıntı → bağlantılar → bağlantı denetimi. */
enum class ProbeStepKind { MAIN_PAGE, SEARCH, LOAD, LINKS, LINK_CHECK }

enum class StepStatus { OK, FAILED, SKIPPED }

/**
 * Bir adımın neden başarısız olduğu ya da atlandığı; arayüz bunu kendi diline çevirir.
 * [CLOUDFLARE]: site Cloudflare insan doğrulaması istedi ve Sına tarayıcı çözümünü kapalı tuttuğu için
 * geçilemedi; kullanıcı sağlayıcıyı açınca çıkan pencerede doğrulayabilir, yani site bozuk değildir.
 */
enum class FailureKind {
    TIMEOUT, NETWORK, EMPTY, NO_PLAYABLE, HTTP, HTML, NOT_MEDIA, BAD_PLAYLIST,
    UNSUPPORTED, INCOMPATIBLE, ERROR, PREVIOUS_FAILED, UNCHECKABLE, CLOUDFLARE,
}

/** @property detail HTTP kodu, eksik sınıf adı ya da hata iletisi gibi teknik ayrıntı (kullanıcıya çevrilmez) */
data class ProbeFailure(val kind: FailureKind, val detail: String? = null)

/**
 * @property info başarılı adımın kısa notu (ör. sonuç sayısı, seçilen başlık); sınıflama dışı bilgidir
 */
data class StepResult(
    val step: ProbeStepKind,
    val status: StepStatus,
    val failure: ProbeFailure? = null,
    val info: String? = null,
    val millis: Long = 0,
)

/**
 * Genel sonuç: yeşil her şey çalışıyor, sarı arama ve ayrıntı çalışıyor ama oynatılabilir bağlantı doğrulanamadı
 * (ya da site Cloudflare doğrulaması istiyor), kırmızı erken aşamada başarısız.
 */
enum class ProbeStatus { GREEN, YELLOW, RED }

data class ProbeResult(
    val provider: String,
    val steps: List<StepResult>,
    /** Sınama sırasında bulunan bağlantılar (komut satırı oynatma denemesi için). */
    val links: List<ExtractorLink> = emptyList(),
    val at: Long = System.currentTimeMillis(),
) {
    val status: ProbeStatus get() = aggregateStatus(steps)

    /** Sağlayıcı Cloudflare insan doğrulamasında kaldı (bkz. [FailureKind.CLOUDFLARE]); arayüz buna özel etiket gösterir. */
    val needsChallenge: Boolean get() = blockedByChallenge(steps)

    /** İlk başarısız adımın nedeni (yoksa null). */
    val firstFailure: Pair<ProbeStepKind, ProbeFailure>?
        get() = steps.firstOrNull { it.status == StepStatus.FAILED && it.failure != null }?.let { it.step to it.failure!! }
}

/**
 * Adım sonuçlarından genel durumu çıkarır:
 * - liste (ana sayfa ya da arama) ya da ayrıntı çalışmıyorsa kırmızı,
 * - bağlantı denetimi başarılı ve hiçbir adım başarısız değilse yeşil,
 * - ötesi (bağlantı bulunamadı, doğrulanamadı, ana sayfa bozuk ama arama çalışıyor) sarı.
 *
 * İstisna: sınamayı bitiren son başarısız adım Cloudflare doğrulamasıysa ([blockedByChallenge]) sonuç
 * kırmızı değil sarıdır; site bozuk değil, kullanıcı sağlayıcıyı açınca çıkan pencerede doğrulayabilir.
 */
fun aggregateStatus(steps: List<StepResult>): ProbeStatus {
    val listingOk = steps.any { (it.step == ProbeStepKind.MAIN_PAGE || it.step == ProbeStepKind.SEARCH) && it.status == StepStatus.OK }
    val loadOk = steps.any { it.step == ProbeStepKind.LOAD && it.status == StepStatus.OK }
    if (!listingOk || !loadOk) return if (blockedByChallenge(steps)) ProbeStatus.YELLOW else ProbeStatus.RED
    val linkChecked = steps.any { it.step == ProbeStepKind.LINK_CHECK && it.status == StepStatus.OK }
    return if (linkChecked && steps.none { it.status == StepStatus.FAILED }) ProbeStatus.GREEN else ProbeStatus.YELLOW
}

/**
 * Sınamayı Cloudflare doğrulaması bitirdiyse true: son başarısız adım doğrulamaya takıldı ve ondan sonra başarılı
 * adım yok (ana sayfa takılıp arama, ayrıntı ve bağlantılar çalıştıysa sağlayıcı doğrulama beklemiyor demektir).
 */
fun blockedByChallenge(steps: List<StepResult>): Boolean {
    val last = steps.indexOfLast { it.status == StepStatus.FAILED }
    if (last < 0 || steps[last].failure?.kind != FailureKind.CLOUDFLARE) return false
    return steps.drop(last + 1).none { it.status == StepStatus.OK }
}

/** Bağlantı denetiminin hükmü: yanıt gerçek bir medya (ya da oynatma listesi) mi, değilse neden. */
enum class LinkVerdict {
    MEDIA, PLAYLIST, HTML, NOT_MEDIA, BAD_PLAYLIST, HTTP_ERROR,

    /** Yanıt bir Cloudflare insan doğrulaması ([CloudflareChallenge]); [classifyLinkResponse] bunu üretmez, ağ katmanı üretir. */
    CHALLENGE;

    val ok: Boolean get() = this == MEDIA || this == PLAYLIST
}

/**
 * Bağlantıya yapılan kısa isteğin yanıtını sınıflar (ağ gerektirmez, tek başına sınanabilir).
 *
 * @param code HTTP durum kodu (2xx ve 206 başarılıdır)
 * @param contentType yanıtın Content-Type üstbilgisi
 * @param head gövdenin ilk baytları (en çok birkaç KB)
 * @param expectsPlaylist bağlantı türü m3u8 ise true; `#EXTM3U` ile başlamayan gövde geçersiz sayılır
 */
fun classifyLinkResponse(code: Int, contentType: String?, head: ByteArray, expectsPlaylist: Boolean = false): LinkVerdict {
    if (code !in 200..299) return LinkVerdict.HTTP_ERROR
    val text = head.toString(Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
    val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    // Oynatma listesi, Content-Type'ı text/html olsa bile gövdesinden tanınır
    if (text.startsWith("#EXTM3U")) return LinkVerdict.PLAYLIST
    val looksHtml = type.contains("html") ||
        text.take(256).let { it.startsWith("<!doctype html", ignoreCase = true) || it.startsWith("<html", ignoreCase = true) || it.contains("<head", ignoreCase = true) || it.contains("<body", ignoreCase = true) }
    if (looksHtml) return LinkVerdict.HTML
    if (expectsPlaylist || type.contains("mpegurl")) return LinkVerdict.BAD_PLAYLIST
    // DASH bildirimi (MPD) de oynatılabilir bir listedir
    if (type.contains("dash+xml") || text.contains("<MPD")) return LinkVerdict.PLAYLIST
    if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) return LinkVerdict.NOT_MEDIA
    // video/*, audio/*, octet-stream, sunucunun bildirmediği türler: medya sayılır
    return LinkVerdict.MEDIA
}

/**
 * Bir sağlayıcıyı mpv olmadan uçtan uca dener (eklentinin kodunu çalıştırır, bu yüzden yalnız kurulu
 * eklentilere uygulanır): ana sayfa ya da arama → ilk sonucun ayrıntısı → ilk öğenin bağlantıları →
 * ilk bağlantıya kısa istek. Her adımın kendi zaman aşımı vardır.
 *
 * Adımlar [withHardTimeout] ile ayrı bir işte ([scope]) çalışır: iptale bakmayan engelleyici eklenti
 * kodu süreyi aşıp sınamayı takılı bırakamaz. İşler [NoSolve] bayrağıyla çalışır; sınama sırasında
 * Cloudflare yakalayıcısı kullanıcıya tarayıcı penceresi açmaz. Bayrak yüzünden çözülmeyen bir Cloudflare
 * doğrulaması adımın başarısızlığına yol açtıysa neden [FailureKind.CLOUDFLARE] olarak kaydedilir.
 */
class ProviderProbe(
    private val stepTimeoutMs: Long = 20_000,
    private val domains: DomainTracker? = null,
    private val client: () -> OkHttpClient = { Network.client },
    private val fallbackQueries: List<String> = DEFAULT_QUERIES,
    private val scope: CoroutineScope = defaultScope,
) {
    /**
     * @param query verilirse ana sayfa atlanır ve doğrudan bu sorguyla aranır
     * @param onStep her adım bitince çağrılır (komut satırı ilerleme çıktısı için)
     */
    suspend fun run(
        api: MainAPI,
        query: String? = null,
        onStep: (StepResult) -> Unit = {},
        onLink: (ExtractorLink) -> Unit = {},
        onSubtitle: (SubtitleFile) -> Unit = {},
    ): ProbeResult {
        val steps = mutableListOf<StepResult>()
        // Doğrulama yalnız sağlayıcının kendi sitesinde görüldüyse adımın nedeni sayılır (ilgisiz bir alt istek değil)
        val siteHost = hostOf(api.mainUrl)
        fun record(step: StepResult) {
            steps += step
            onStep(step)
        }
        fun skipRest(from: List<ProbeStepKind>) = from.forEach {
            record(StepResult(it, StepStatus.SKIPPED, ProbeFailure(FailureKind.PREVIOUS_FAILED)))
        }

        // 1. ana sayfa ya da arama
        var candidates: List<SearchResponse> = emptyList()
        if (query == null && api.hasMainPage && api.mainPage.isNotEmpty()) {
            val (step, items) = timed(ProbeStepKind.MAIN_PAGE, siteHost, info = { it.firstOrNull()?.name }) { mainPageItems(api) }
            record(step)
            candidates = items.orEmpty()
        }
        if (candidates.isEmpty()) {
            val (step, items) = timed(ProbeStepKind.SEARCH, siteHost, info = { it.firstOrNull()?.name }) { searchItems(api, query) }
            record(step)
            candidates = items.orEmpty()
        }
        if (candidates.isEmpty()) {
            skipRest(listOf(ProbeStepKind.LOAD, ProbeStepKind.LINKS, ProbeStepKind.LINK_CHECK))
            return ProbeResult(api.name, steps)
        }

        // 2-3. ayrıntı ve bağlantılar. Sitenin ilk kaydı videosuz ya da bölümsüz olabilir (henüz yayınlanmamış dizi,
        // kaldırılmış video); o zaman sıradaki içerikler denenir. Hiçbirinde bağlantı çıkmazsa en ileri giden deneme raporlanır.
        var best: Attempt? = null
        for (candidate in candidates.take(MAX_CANDIDATES)) {
            val attempt = attempt(api, candidate, siteHost)
            if (attempt.links.isNotEmpty()) {
                attempt.steps.forEach(::record)
                attempt.subtitles.forEach(onSubtitle)
                attempt.links.forEach(onLink)
                // 4. bağlantı denetimi
                record(checkLinks(api, attempt.links))
                return ProbeResult(api.name, steps, attempt.links)
            }
            if (best == null || attempt.progress > best.progress) best = attempt
        }
        val failed = best!!.steps
        failed.forEach(::record)
        if (failed.none { it.step == ProbeStepKind.LINKS }) skipRest(listOf(ProbeStepKind.LINKS))
        skipRest(listOf(ProbeStepKind.LINK_CHECK))
        return ProbeResult(api.name, steps)
    }

    /** Bir içeriğin ayrıntı ve bağlantı adımları; bağlantılar ve altyazılar yalnız bu deneme seçilirse bildirilir. */
    private class Attempt(val steps: List<StepResult>, val links: List<ExtractorLink>, val subtitles: List<SubtitleFile>) {
        val progress: Int get() = steps.count { it.status == StepStatus.OK }
    }

    private suspend fun attempt(api: MainAPI, candidate: SearchResponse, siteHost: String?): Attempt {
        val (loadStep, loaded) = timed(ProbeStepKind.LOAD, siteHost, info = { it.title }) { loadItem(api, candidate) }
        if (loadStep.status != StepStatus.OK) return Attempt(listOf(loadStep), emptyList(), emptyList())
        if (loaded == null || loaded.data.isBlank()) {
            return Attempt(listOf(loadStep, StepResult(ProbeStepKind.LINKS, StepStatus.SKIPPED, ProbeFailure(FailureKind.NO_PLAYABLE))), emptyList(), emptyList())
        }
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val (linksStep, _) = timed(ProbeStepKind.LINKS, siteHost, partial = { synchronized(links) { links.isNotEmpty() } }, info = { it }) {
            api.loadLinks(
                loaded.data,
                false,
                { sub -> synchronized(subtitles) { subtitles += sub } },
                { link -> synchronized(links) { if (links.none { it.url == link.url }) links += link } },
            )
            val names = synchronized(links) { links.map { it.name }.distinct().take(3) }
            if (names.isEmpty()) throw ProbeException(ProbeFailure(FailureKind.EMPTY))
            names.joinToString(", ")
        }
        return Attempt(listOf(loadStep, linksStep), synchronized(links) { links.toList() }, synchronized(subtitles) { subtitles.toList() })
    }

    private class ProbeException(val failure: ProbeFailure) : RuntimeException(failure.kind.name)

    /** [block]'u zaman aşımıyla çalıştırır; sonucu adım kaydına çevirir. Dönen değer null ise adım başarısızdır. */
    private suspend fun <T> timed(
        step: ProbeStepKind,
        siteHost: String?,
        partial: (() -> Boolean)? = null,
        info: (T) -> String? = { null },
        block: suspend () -> T,
    ): Pair<StepResult, T?> {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start
        // Adımın isteklerinde çözülmeyip geçilen Cloudflare doğrulaması buraya düşer
        val sighting = ChallengeSighting()
        fun failed(failure: ProbeFailure) = StepResult(step, StepStatus.FAILED, maskedByChallenge(failure, sighting, siteHost), millis = elapsed())
        return try {
            val value = withHardTimeout(scope, stepTimeoutMs, NoSolve.context(sighting)) { block() }
            StepResult(step, StepStatus.OK, info = info(value)?.take(120), millis = elapsed()) to value
        } catch (e: TimeoutCancellationException) {
            // Zaman aşımına kadar bağlantı gelmişse adım başarılı sayılır
            if (partial?.invoke() == true) StepResult(step, StepStatus.OK, millis = elapsed()) to null
            else failed(ProbeFailure(FailureKind.TIMEOUT)) to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProbeException) {
            failed(e.failure) to null
        } catch (t: Throwable) {
            failed(classifyFailure(t)) to null
        }
    }

    private suspend fun mainPageItems(api: MainAPI): List<SearchResponse> {
        val first = api.mainPage.first()
        var request = MainPageRequest(first.name, first.data, first.horizontalImages)
        domains?.let { request = it.rewrite(api, request) }
        val response = api.getMainPage(1, request) ?: throw ProbeException(ProbeFailure(FailureKind.EMPTY))
        val items = response.items.flatMap { it.list }
        if (items.isEmpty()) throw ProbeException(ProbeFailure(FailureKind.EMPTY))
        return items
    }

    /** Verilen sorguyla, yoksa genel kelimelerle arar; ilk dolu sonuç kazanır (adım zaman aşımı toplam süredir). */
    private suspend fun searchItems(api: MainAPI, query: String?): List<SearchResponse> {
        var lastError: Throwable? = null
        for (word in if (query != null) listOf(query) else fallbackQueries) {
            try {
                val items = api.search(word).orEmpty()
                if (items.isNotEmpty()) return items
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
            }
        }
        lastError?.let { throw it }
        throw ProbeException(ProbeFailure(FailureKind.EMPTY))
    }

    /** Bağlantı isteğinin yanıtı: kod, Content-Type, gövde başı ve Cloudflare doğrulaması mı. */
    private class Fetched(val code: Int, val type: String?, val head: ByteArray, val challenge: Boolean)

    private class LoadedItem(val title: String, val data: String)

    /** İçeriği yükler; ilk oynatılabilir öğenin (dizide ilk bölümün) verisini döndürür, yoksa veri boştur. */
    private suspend fun loadItem(api: MainAPI, candidate: SearchResponse): LoadedItem {
        val response = api.load(candidate.url) ?: throw ProbeException(ProbeFailure(FailureKind.EMPTY))
        return LoadedItem(response.name, response.playables().firstOrNull()?.data.orEmpty())
    }

    private suspend fun checkLinks(api: MainAPI, links: List<ExtractorLink>): StepResult {
        val start = System.currentTimeMillis()
        val checkable = links.filter {
            it.url.startsWith("http", ignoreCase = true) &&
                it.type in setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)
        }.take(MAX_CHECKED_LINKS)
        if (checkable.isEmpty()) {
            return StepResult(ProbeStepKind.LINK_CHECK, StepStatus.SKIPPED, ProbeFailure(FailureKind.UNCHECKABLE))
        }
        var firstFailure: ProbeFailure? = null
        // Doğrulama, nedeni bildirilen bağlantının kendi sunucusunda görüldüyse sayılır
        var failureHost: String? = null
        val sighting = ChallengeSighting()
        try {
            withHardTimeout(scope, stepTimeoutMs, NoSolve.context(sighting)) {
                for (link in checkable) {
                    val verdict = try {
                        fetchVerdict(api, link)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        if (firstFailure == null) {
                            firstFailure = classifyFailure(t)
                            failureHost = hostOf(link.url)
                        }
                        continue
                    }
                    if (verdict.first.ok) {
                        return@withHardTimeout StepResult(
                            ProbeStepKind.LINK_CHECK, StepStatus.OK, info = link.name, millis = System.currentTimeMillis() - start,
                        )
                    }
                    if (firstFailure == null) {
                        firstFailure = verdictFailure(verdict.first, verdict.second)
                        failureHost = hostOf(link.url)
                    }
                }
                null
            }?.let { return it }
        } catch (e: TimeoutCancellationException) {
            if (firstFailure == null) firstFailure = ProbeFailure(FailureKind.TIMEOUT)
        }
        return StepResult(
            ProbeStepKind.LINK_CHECK, StepStatus.FAILED, firstFailure?.let { maskedByChallenge(it, sighting, failureHost) },
            millis = System.currentTimeMillis() - start,
        )
    }

    private fun verdictFailure(verdict: LinkVerdict, code: Int) = when (verdict) {
        LinkVerdict.HTTP_ERROR -> ProbeFailure(FailureKind.HTTP, code.toString())
        LinkVerdict.HTML -> ProbeFailure(FailureKind.HTML)
        LinkVerdict.NOT_MEDIA -> ProbeFailure(FailureKind.NOT_MEDIA)
        LinkVerdict.BAD_PLAYLIST -> ProbeFailure(FailureKind.BAD_PLAYLIST)
        LinkVerdict.CHALLENGE -> ProbeFailure(FailureKind.CLOUDFLARE)
        else -> ProbeFailure(FailureKind.ERROR)
    }

    /**
     * Bağlantının ilk baytlarını ister: videolarda `Range: bytes=0-1023`, oynatma listelerinde düz GET.
     * Bağlantının üstbilgileri ve Referer'ı uygulanır; eklenti yakalayıcı verdiyse o da eklenir.
     * 401-403 gelirse Referer olarak sitenin kökü ile bir kez daha denenir (aktarıcıdaki gibi).
     */
    private suspend fun fetchVerdict(api: MainAPI, link: ExtractorLink): Pair<LinkVerdict, Int> {
        val interceptor = runCatching { api.getVideoInterceptor(link) }.getOrNull()
        val http = client().let { base -> interceptor?.let { base.newBuilder().addInterceptor(it).build() } ?: base }
        val isPlaylist = link.type == ExtractorLinkType.M3U8
        val origin = link.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" }

        fun build(originReferer: Boolean): Request {
            val builder = Request.Builder().url(link.url)
            link.headers.forEach { (k, v) -> builder.header(k, v) }
            if (link.headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", USER_AGENT)
            // Oynatıcı gibi Accept gönderilir; bazı sunucular Accept'siz isteği reddeder
            if (link.headers.keys.none { it.equals("Accept", true) }) builder.header("Accept", "*/*")
            val referer = if (originReferer) origin else link.referer.ifBlank { null }
            if (referer != null) builder.header("Referer", referer)
            if (!isPlaylist) builder.header("Range", "bytes=0-1023")
            return builder.build()
        }

        fun execute(request: Request): Fetched =
            http.newCall(request).execute().use { response ->
                val cfMitigated = response.header("cf-mitigated")
                val server = response.header("Server")
                val source = response.body?.source() ?: return@use Fetched(
                    response.code, response.header("Content-Type"), ByteArray(0),
                    CloudflareChallenge.matches(response.code, cfMitigated, server) { "" },
                )
                source.request(HEAD_BYTES)
                val size = minOf(source.buffer.size, HEAD_BYTES)
                val head = source.buffer.snapshot(size.toInt()).toByteArray()
                // Sınama sayfasının başlığı ve betikleri gövdenin başında olur; kural yalnız o kısma uygulanır
                val challenge = CloudflareChallenge.matches(response.code, cfMitigated, server) { head.toString(Charsets.UTF_8) }
                Fetched(response.code, response.header("Content-Type"), head, challenge)
            }

        var fetched = execute(build(originReferer = false))
        if (fetched.code in 401..403 && origin != null) {
            execute(build(originReferer = true)).let { retry -> if (retry.code in 200..299) fetched = retry }
        }
        if (fetched.challenge) return LinkVerdict.CHALLENGE to fetched.code
        return classifyLinkResponse(fetched.code, fetched.type, fetched.head, expectsPlaylist = isPlaylist) to fetched.code
    }

    companion object {
        /** Sınama adımlarının çalıştığı ortak kapsam; bir adımın düşmesi ötekileri iptal etmez. */
        private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Sorgusuz sağlayıcılar için siteden bağımsız, yaygın aramalar. */
        val DEFAULT_QUERIES = listOf("love", "the", "aşk", "man")
        private const val MAX_CHECKED_LINKS = 3
        /** Bağlantı çıkmazsa denenecek en çok içerik sayısı (ana sayfa ya da arama sırasıyla). */
        private const val MAX_CANDIDATES = 3
        private const val HEAD_BYTES = 4096L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /**
         * Adım bayrak yüzünden çözülmeyen bir Cloudflare doğrulamasına takıldıysa ([sighting]) ve başarısızlık
         * böyle bir sayfanın olağan sonucuysa (boş dönüş, HTTP/HTML hatası, eklentinin ayrıştırma hatası)
         * gerçek nedeni [FailureKind.CLOUDFLARE] olarak bildirir. Ağ hatası, zaman aşımı, uyumsuzluk gibi
         * doğrulamayla ilgisi olmayan nedenler olduğu gibi kalır.
         */
        internal fun maskedByChallenge(failure: ProbeFailure, sighting: ChallengeSighting, expectedHost: String? = null): ProbeFailure {
            val host = sighting.host ?: return failure
            // Doğrulama başka bir sunucudaysa (görsel, izleme betiği, yedek ayna) asıl hata gizlenmesin
            if (expectedHost != null && !sameSite(host, expectedHost)) return failure
            return if (failure.kind in CHALLENGE_SYMPTOMS) ProbeFailure(FailureKind.CLOUDFLARE, host) else failure
        }

        /** İki ad aynı site mi: eşit ya da biri ötekinin alt alan adı ("www.site.tv" ile "site.tv"). */
        internal fun sameSite(a: String, b: String): Boolean {
            val x = a.lowercase().removePrefix("www.")
            val y = b.lowercase().removePrefix("www.")
            return x == y || x.endsWith(".$y") || y.endsWith(".$x")
        }

        private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

        private val CHALLENGE_SYMPTOMS = setOf(
            FailureKind.EMPTY, FailureKind.HTTP, FailureKind.HTML, FailureKind.NOT_MEDIA, FailureKind.BAD_PLAYLIST, FailureKind.ERROR,
        )

        /** İstisnayı kullanıcıya çevrilebilecek bir nedene indirger. */
        fun classifyFailure(t: Throwable): ProbeFailure = when (t) {
            is TimeoutCancellationException, is java.net.SocketTimeoutException -> ProbeFailure(FailureKind.TIMEOUT)
            is InterruptedIOException ->
                ProbeFailure(if (t.message?.contains("timeout", ignoreCase = true) == true) FailureKind.TIMEOUT else FailureKind.NETWORK, t.message)
            is UnknownHostException, is ConnectException, is SSLException, is SocketException, is IOException ->
                ProbeFailure(FailureKind.NETWORK, t.message)
            is NotImplementedError, is UnsupportedOperationException -> ProbeFailure(FailureKind.UNSUPPORTED, t.message)
            // Eklenti çalışırken Seyir'de olmayan bir sınıfa ya da yönteme ulaştı
            is NoClassDefFoundError, is NoSuchMethodError, is NoSuchFieldError, is ClassNotFoundException, is AbstractMethodError ->
                ProbeFailure(FailureKind.INCOMPATIBLE, t.message?.replace('/', '.'))
            else -> ProbeFailure(FailureKind.ERROR, t.message ?: t::class.simpleName)
        }
    }
}
