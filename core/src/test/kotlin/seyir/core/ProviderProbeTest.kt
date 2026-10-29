package seyir.core

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderProbeTest {
    private fun ok(step: ProbeStepKind) = StepResult(step, StepStatus.OK)
    private fun failed(step: ProbeStepKind) = StepResult(step, StepStatus.FAILED, ProbeFailure(FailureKind.ERROR, "x"))
    private fun skipped(step: ProbeStepKind) = StepResult(step, StepStatus.SKIPPED, ProbeFailure(FailureKind.PREVIOUS_FAILED))

    // ---- genel durum ----

    @Test
    fun everythingWorkingIsGreen() {
        val steps = listOf(ok(ProbeStepKind.MAIN_PAGE), ok(ProbeStepKind.LOAD), ok(ProbeStepKind.LINKS), ok(ProbeStepKind.LINK_CHECK))
        assertEquals(ProbeStatus.GREEN, aggregateStatus(steps))
    }

    @Test
    fun unverifiedLinkIsYellow() {
        val noLinks = listOf(ok(ProbeStepKind.SEARCH), ok(ProbeStepKind.LOAD), failed(ProbeStepKind.LINKS), skipped(ProbeStepKind.LINK_CHECK))
        assertEquals(ProbeStatus.YELLOW, aggregateStatus(noLinks))
        val badLink = listOf(ok(ProbeStepKind.SEARCH), ok(ProbeStepKind.LOAD), ok(ProbeStepKind.LINKS), failed(ProbeStepKind.LINK_CHECK))
        assertEquals(ProbeStatus.YELLOW, aggregateStatus(badLink))
        val uncheckable = listOf(ok(ProbeStepKind.SEARCH), ok(ProbeStepKind.LOAD), ok(ProbeStepKind.LINKS), skipped(ProbeStepKind.LINK_CHECK))
        assertEquals(ProbeStatus.YELLOW, aggregateStatus(uncheckable))
    }

    @Test
    fun brokenMainPageWithWorkingSearchIsYellowNotRed() {
        val steps = listOf(
            failed(ProbeStepKind.MAIN_PAGE), ok(ProbeStepKind.SEARCH), ok(ProbeStepKind.LOAD), ok(ProbeStepKind.LINKS), ok(ProbeStepKind.LINK_CHECK),
        )
        assertEquals(ProbeStatus.YELLOW, aggregateStatus(steps))
    }

    @Test
    fun earlyFailureIsRed() {
        val noListing = listOf(failed(ProbeStepKind.SEARCH), skipped(ProbeStepKind.LOAD), skipped(ProbeStepKind.LINKS), skipped(ProbeStepKind.LINK_CHECK))
        assertEquals(ProbeStatus.RED, aggregateStatus(noListing))
        val noLoad = listOf(ok(ProbeStepKind.MAIN_PAGE), failed(ProbeStepKind.LOAD), skipped(ProbeStepKind.LINKS), skipped(ProbeStepKind.LINK_CHECK))
        assertEquals(ProbeStatus.RED, aggregateStatus(noLoad))
        assertEquals(ProbeStatus.RED, aggregateStatus(emptyList()))
    }

    @Test
    fun firstFailureSkipsNothingElse() {
        val result = ProbeResult("x", listOf(ok(ProbeStepKind.MAIN_PAGE), failed(ProbeStepKind.LOAD)))
        assertEquals(ProbeStepKind.LOAD, result.firstFailure?.first)
    }

    // ---- bağlantı yanıtı sınıflaması ----

    private fun bytes(text: String) = text.toByteArray()

    @Test
    fun videoContentTypeIsMedia() {
        assertEquals(LinkVerdict.MEDIA, classifyLinkResponse(206, "video/mp4", ByteArray(16)))
        assertEquals(LinkVerdict.MEDIA, classifyLinkResponse(200, "application/octet-stream", ByteArray(16)))
        // Sunucu tür bildirmese de ikili gövde medya sayılır
        assertEquals(LinkVerdict.MEDIA, classifyLinkResponse(200, null, ByteArray(16) { 0x47 }))
    }

    @Test
    fun htmlPageIsRejectedEvenWithOkStatus() {
        assertEquals(LinkVerdict.HTML, classifyLinkResponse(200, "text/html; charset=utf-8", bytes("<!DOCTYPE html><html>")))
        // Content-Type yanlış (video) ama gövde sayfa
        assertEquals(LinkVerdict.HTML, classifyLinkResponse(200, "video/mp4", bytes("  \n<html><body>Hata</body></html>")))
    }

    @Test
    fun playlistIsRecognizedFromBodyOnly() {
        assertEquals(LinkVerdict.PLAYLIST, classifyLinkResponse(200, "application/vnd.apple.mpegurl", bytes("#EXTM3U\n#EXT-X-VERSION:3"), expectsPlaylist = true))
        assertEquals(LinkVerdict.PLAYLIST, classifyLinkResponse(200, "text/html", bytes("﻿#EXTM3U\n")))
        assertEquals(LinkVerdict.BAD_PLAYLIST, classifyLinkResponse(200, "application/x-mpegurl", bytes("hello"), expectsPlaylist = true))
        assertEquals(LinkVerdict.HTML, classifyLinkResponse(200, "application/x-mpegurl", bytes("<html></html>"), expectsPlaylist = true))
    }

    @Test
    fun dashManifestAndTextTypes() {
        assertEquals(LinkVerdict.PLAYLIST, classifyLinkResponse(200, "application/dash+xml", bytes("<?xml version=\"1.0\"?><MPD>")))
        assertEquals(LinkVerdict.NOT_MEDIA, classifyLinkResponse(200, "application/json", bytes("{\"error\":1}")))
        assertEquals(LinkVerdict.NOT_MEDIA, classifyLinkResponse(200, "text/plain", bytes("denied")))
    }

    @Test
    fun errorStatusesAreHttpErrors() {
        assertEquals(LinkVerdict.HTTP_ERROR, classifyLinkResponse(403, "video/mp4", ByteArray(4)))
        assertEquals(LinkVerdict.HTTP_ERROR, classifyLinkResponse(404, "text/html", bytes("<html>")))
        assertEquals(LinkVerdict.HTTP_ERROR, classifyLinkResponse(301, null, ByteArray(0)))
        assertTrue(LinkVerdict.MEDIA.ok && LinkVerdict.PLAYLIST.ok)
        assertFalse(LinkVerdict.HTML.ok || LinkVerdict.HTTP_ERROR.ok)
    }

    // ---- istisna sınıflaması ----

    @Test
    fun exceptionsMapToFailureKinds() {
        assertEquals(FailureKind.NETWORK, ProviderProbe.classifyFailure(java.net.UnknownHostException("x")).kind)
        assertEquals(FailureKind.TIMEOUT, ProviderProbe.classifyFailure(java.net.SocketTimeoutException("x")).kind)
        assertEquals(FailureKind.NETWORK, ProviderProbe.classifyFailure(IOException("boom")).kind)
        assertEquals(FailureKind.UNSUPPORTED, ProviderProbe.classifyFailure(NotImplementedError()).kind)
        val missing = ProviderProbe.classifyFailure(NoClassDefFoundError("android/view/View"))
        assertEquals(FailureKind.INCOMPATIBLE, missing.kind)
        assertEquals("android.view.View", missing.detail)
        assertEquals(FailureKind.ERROR, ProviderProbe.classifyFailure(IllegalStateException("kırık")).kind)
    }

    // ---- Cloudflare doğrulaması ----

    private fun challenge(code: Int = 403, cfMitigated: String? = null, server: String? = "cloudflare", body: String = "") =
        CloudflareChallenge.matches(code, cfMitigated, server) { body }

    @Test
    fun cloudflareChallengeDetection() {
        // Başlık tek başına yeter (kod ve sunucu önemsiz)
        assertTrue(challenge(code = 200, cfMitigated = "challenge", server = null))
        assertTrue(challenge(cfMitigated = "Challenge"))
        // cloudflare + hata kodu + gövde işareti
        assertTrue(challenge(body = "<html><title>Just a moment...</title></html>"))
        assertTrue(challenge(code = 503, body = "<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script>"))
        assertTrue(challenge(code = 503, server = "cloudflare-nginx", body = "window._cf_chl_opt = {}"))
        assertTrue(challenge(body = "<div class=\"cf-turnstile\"></div>"))
        // Hız sınırı (429) yalnız başlıkla sınama sayılır
        assertFalse(challenge(code = 429, server = "cloudflare-nginx", body = "window._cf_chl_opt = {}"))
        assertTrue(challenge(code = 429, cfMitigated = "challenge"))
        // Bakım sayfasındaki "Just a moment" ve JS algılama betiği sınama değil
        assertFalse(challenge(code = 503, body = "<h1>Just a moment, we are updating the site</h1>"))
        assertFalse(challenge(code = 403, body = "<script src=\"/cdn-cgi/challenge-platform/scripts/jsd/main.js\"></script>"))
        // işaretsiz düz 403, başka sunucu, başarılı kod: sınama değil
        assertFalse(challenge(body = "Forbidden"))
        assertFalse(challenge(server = "nginx", body = "Just a moment"))
        assertFalse(challenge(code = 200, body = "Just a moment"))
        assertFalse(challenge(code = 404, body = "cf_chl_opt"))
        assertFalse(challenge(cfMitigated = "other", body = ""))
    }

    @Test
    fun challengeOnlyCountsOnTheProvidersOwnSite() {
        val sighting = ChallengeSighting().apply { record("cdn.tracker.example") }
        // Başka sunucudaki doğrulama asıl hatayı gizlemez
        assertEquals(FailureKind.EMPTY, ProviderProbe.maskedByChallenge(ProbeFailure(FailureKind.EMPTY), sighting, "site.example").kind)
        val own = ChallengeSighting().apply { record("www.site.example") }
        assertEquals(FailureKind.CLOUDFLARE, ProviderProbe.maskedByChallenge(ProbeFailure(FailureKind.EMPTY), own, "site.example").kind)
        assertTrue(ProviderProbe.sameSite("api.site.example", "www.site.example"))
        assertFalse(ProviderProbe.sameSite("othersite.example", "site.example"))
    }

    @Test
    fun challengeLabelOnlyWhenItEndedTheProbe() {
        val blocked = StepResult(ProbeStepKind.MAIN_PAGE, StepStatus.FAILED, ProbeFailure(FailureKind.CLOUDFLARE, "site.example"))
        // Ana sayfa takıldı ama arama, ayrıntı ve bağlantılar çalıştı: doğrulama beklenmiyor
        val later = listOf(blocked, ok(ProbeStepKind.SEARCH), ok(ProbeStepKind.LOAD), ok(ProbeStepKind.LINKS), ok(ProbeStepKind.LINK_CHECK))
        assertFalse(blockedByChallenge(later))
    }

    @Test
    fun challengeStatusAndLabel() {
        val blocked = StepResult(ProbeStepKind.SEARCH, StepStatus.FAILED, ProbeFailure(FailureKind.CLOUDFLARE, "site.example"))
        val rest = listOf(ProbeStepKind.LOAD, ProbeStepKind.LINKS, ProbeStepKind.LINK_CHECK).map(::skipped)
        // Liste adımı doğrulamaya takılırsa kırmızı değil sarı
        val early = ProbeResult("x", listOf(blocked) + rest)
        assertEquals(ProbeStatus.YELLOW, early.status)
        assertTrue(early.needsChallenge)
        // Ayrıntı adımında takılırsa da
        val onLoad = listOf(ok(ProbeStepKind.SEARCH), blocked.copy(step = ProbeStepKind.LOAD)) + rest.drop(1)
        assertEquals(ProbeStatus.YELLOW, aggregateStatus(onLoad))
        // Bağlantı denetimi doğrulamaya takılırsa zaten sarıydı; etiket yine "doğrulama gerekiyor"
        val onLinks = listOf(ok(ProbeStepKind.SEARCH), ok(ProbeStepKind.LOAD), ok(ProbeStepKind.LINKS), blocked.copy(step = ProbeStepKind.LINK_CHECK))
        assertEquals(ProbeStatus.YELLOW, aggregateStatus(onLinks))
        assertTrue(ProbeResult("x", onLinks).needsChallenge)
        // Başka nedenle kırılan sağlayıcı kırmızı kalır ve doğrulama beklemez
        val plain = ProbeResult("x", listOf(failed(ProbeStepKind.SEARCH)) + rest)
        assertEquals(ProbeStatus.RED, plain.status)
        assertFalse(plain.needsChallenge)
    }

    @Test
    fun sightingMasksOnlyChallengeSymptoms() {
        val sighting = ChallengeSighting()
        // Doğrulama görülmediyse hiçbir neden değişmez
        assertEquals(FailureKind.EMPTY, ProviderProbe.maskedByChallenge(ProbeFailure(FailureKind.EMPTY), sighting).kind)
        sighting.record("site.example")
        for (kind in listOf(FailureKind.EMPTY, FailureKind.HTTP, FailureKind.HTML, FailureKind.ERROR)) {
            val masked = ProviderProbe.maskedByChallenge(ProbeFailure(kind, "x"), sighting)
            assertEquals(FailureKind.CLOUDFLARE, masked.kind)
            assertEquals("site.example", masked.detail)
        }
        // Ağ hatası, zaman aşımı ve uyumsuzluk doğrulamayla ilgisiz
        for (kind in listOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.INCOMPATIBLE, FailureKind.UNSUPPORTED)) {
            assertEquals(kind, ProviderProbe.maskedByChallenge(ProbeFailure(kind), sighting).kind)
        }
    }

    // ---- Sınama uçtan uca (yerel sahte sunucu) ----

    private lateinit var server: HttpServer

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        fun route(path: String, code: Int, contentType: String, body: String) {
            server.createContext(path) { ex ->
                ex.responseHeaders.add("Server", "cloudflare")
                ex.responseHeaders.add("Content-Type", contentType)
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        route("/challenge", 403, "text/html", "<html><title>Just a moment...</title><script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script></html>")
        route("/plain403", 403, "text/plain", "Forbidden")
        route("/video.mp4", 200, "video/mp4", "x".repeat(64))
        route("/video-blocked.mp4", 403, "text/html", "<html><title>Just a moment...</title></html>")
        route("/video-forbidden.mp4", 403, "text/plain", "Forbidden")
        server.start()
    }

    @AfterTest
    fun stopServer() = server.stop(0)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    /** Sınamanın ortak istemcisini taklit eder: Cloudflare yakalayıcısı ve çağrı işaretleyicisi takılıdır. */
    private val testClient: OkHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor(CloudflareKiller()).eventListenerFactory(NoSolve.listenerFactory).build()
    }

    /** Aramayı [path]'e giden bir istekle yapan, sayfayı ayrıştıramayıp boş dönen eklenti. */
    private inner class BlockedSearchApi(private val path: String, private val enqueue: Boolean = false) : MainAPI() {
        override var name = "Engelli"
        override var mainUrl = "http://127.0.0.1"
        override suspend fun search(query: String): List<SearchResponse>? {
            val request = Request.Builder().url(url(path)).build()
            if (enqueue) {
                kotlin.coroutines.suspendCoroutine<Unit> { cont ->
                    testClient.newCall(request).enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: IOException) = cont.resumeWith(Result.failure(e))
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            response.close()
                            cont.resumeWith(Result.success(Unit))
                        }
                    })
                }
            } else {
                testClient.newCall(request).execute().close()
            }
            return emptyList()
        }
    }

    private fun probe(api: MainAPI) = runBlocking {
        ProviderProbe(stepTimeoutMs = 10_000, client = { testClient }, fallbackQueries = listOf("a")).run(api)
    }

    @Test
    fun challengeSeenDuringSearchIsReportedAsCloudflare() {
        for (enqueue in listOf(false, true)) {
            val result = probe(BlockedSearchApi("/challenge", enqueue))
            val (step, failure) = assertNotNull(result.firstFailure)
            assertEquals(ProbeStepKind.SEARCH, step)
            assertEquals(FailureKind.CLOUDFLARE, failure.kind, "enqueue=$enqueue")
            assertEquals("127.0.0.1", failure.detail)
            assertEquals(ProbeStatus.YELLOW, result.status)
            assertTrue(result.needsChallenge)
        }
    }

    @Test
    fun plainForbiddenStaysEmptyAndRed() {
        val result = probe(BlockedSearchApi("/plain403"))
        assertEquals(FailureKind.EMPTY, result.firstFailure?.second?.kind)
        assertEquals(ProbeStatus.RED, result.status)
        assertFalse(result.needsChallenge)
    }

    /** Arama ve ayrıntı çalışır; tek bağlantı [videoPath]'e gider. */
    private inner class LinkApi(private val videoPath: String) : MainAPI() {
        override var name = "Bağlantılı"
        override var mainUrl = "http://127.0.0.1"
        override suspend fun search(query: String): List<SearchResponse>? =
            listOf(newMovieSearchResponse("Film", url("/film"), TvType.Movie))

        override suspend fun load(url: String) = newMovieLoadResponse("Film", url, TvType.Movie, "veri")

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            callback(newExtractorLink("Kaynak", "Kaynak", url(videoPath), ExtractorLinkType.VIDEO))
            return true
        }
    }

    @Test
    fun linkBehindChallengeIsReportedAsCloudflare() {
        val blocked = probe(LinkApi("/video-blocked.mp4"))
        val step = blocked.steps.single { it.step == ProbeStepKind.LINK_CHECK }
        assertEquals(StepStatus.FAILED, step.status)
        assertEquals(FailureKind.CLOUDFLARE, step.failure?.kind)
        assertEquals(ProbeStatus.YELLOW, blocked.status)
        assertTrue(blocked.needsChallenge)

        // Cloudflare arkasında ama sınama olmayan düz 403 sıradan HTTP hatasıdır
        val forbidden = probe(LinkApi("/video-forbidden.mp4"))
        assertEquals(FailureKind.HTTP, forbidden.steps.single { it.step == ProbeStepKind.LINK_CHECK }.failure?.kind)
        assertFalse(forbidden.needsChallenge)

        // Sağlam bağlantı yeşil
        assertEquals(ProbeStatus.GREEN, probe(LinkApi("/video.mp4")).status)
    }

    /** [empty] kadar ilk içerik videosuz (bağlantı vermez), sonrakiler sağlam bağlantı verir. */
    private inner class FirstEmptyApi(private val empty: Int) : MainAPI() {
        override var name = "İlkiBoş"
        override var mainUrl = "http://127.0.0.1"
        override suspend fun search(query: String): List<SearchResponse>? =
            (1..4).map { newMovieSearchResponse("Film $it", url("/film$it"), TvType.Movie) }

        override suspend fun load(url: String) = newMovieLoadResponse("Film", url, TvType.Movie, url)

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val index = data.substringAfterLast("/film").toInt()
            if (index > empty) callback(newExtractorLink("Kaynak", "Kaynak", url("/video.mp4"), ExtractorLinkType.VIDEO))
            return true
        }
    }

    @Test
    fun videolessFirstItemFallsBackToNextOnes() {
        // İlk iki içerik videosuz: üçüncüsü denenir, sonuç yeşil ve adımlar tek deneme gibi raporlanır
        val result = probe(FirstEmptyApi(empty = 2))
        assertEquals(ProbeStatus.GREEN, result.status)
        assertEquals(1, result.steps.count { it.step == ProbeStepKind.LOAD })
        assertEquals(1, result.links.size)

        // En çok üç içerik denenir; hepsi videosuzsa bağlantı adımı EMPTY ile düşer
        val failed = probe(FirstEmptyApi(empty = 3))
        assertEquals(FailureKind.EMPTY, failed.steps.single { it.step == ProbeStepKind.LINKS }.failure?.kind)
        assertEquals(StepStatus.SKIPPED, failed.steps.single { it.step == ProbeStepKind.LINK_CHECK }.status)
    }
}
