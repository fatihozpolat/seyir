package com.lagradost.cloudstream3.network

import com.lagradost.nicehttp.getHeaders
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import seyir.core.ChallengeSolver
import seyir.core.CloudflareChallenge
import seyir.core.NO_SOLVE_HEADER
import seyir.core.NoSolve
import seyir.core.SeyirLog
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * CloudStream uygulamasındaki CloudflareKiller'ın masaüstü karşılığı. Cloudflare sınama sayfası
 * dönerse sınama çözülür, alınan çerezler o alan adı için saklanır ve sonraki isteklere eklenir.
 * Sınamayı WebView yerine [solver] çözer. Orijinalden farkı: sınama gövdeden de doğrulanır ve
 * çözülemeyen alan adı bir süre yeniden denenmez (bkz. [isChallenge]).
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private const val FAILURE_BACKOFF_MS = 10 * 60_000L

        /** Masaüstü uygulaması açılışta atar. */
        @Volatile
        var solver: ChallengeSolver? = null

        /** Ortak HTTP istemcisine eklenen paylaşımlı örnek (bkz. Network.buildClient). */
        val global: CloudflareKiller by lazy { CloudflareKiller() }

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = ConcurrentHashMap()

    /** Çerezleri ve sınamayı çözen tarayıcının user-agent'ını içeren başlıklar. */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) } ?: emptyMap()
        return getHeaders(userAgentHeaders, null, savedCookies[URI(url).host] ?: emptyMap())
    }

    /** Sınaması çözülemeyen alan adları → son deneme zamanı; tarayıcı her istekte yeniden beklenmesin. */
    private val failedHosts: MutableMap<String, Long> = ConcurrentHashMap()

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        // Arka plan denetimi: kayıtlı çerezler uygulanır ama sınama için tarayıcı açılmaz
        // Başlık ya da bağlam bayrağı ([NoSolve]; eklenti kodunun isteklerinde başlık eklenemez)
        val noSolve = request.header(NO_SOLVE_HEADER) != null || NoSolve.isMarked(chain.call())
        if (request.header(NO_SOLVE_HEADER) != null) request = request.newBuilder().removeHeader(NO_SOLVE_HEADER).build()
        val host = request.url.host

        savedCookies[host]?.let { cookies ->
            val saved = proceed(chain, request, cookies)
            // Kayıtlı çerez süresi dolmuş olabilir; arka plan denetimi bunu "doğrulama gerekiyor" diye bildirir
            if (noSolve && isChallenge(saved)) NoSolve.reportChallenge(chain.call())
            return saved
        }

        val response = chain.proceed(request)
        if (noSolve) {
            // Çözüm kapalı: tarayıcı açılmaz, ama sınama görüldüğü denetimin sahibine bildirilir
            if (isChallenge(response)) NoSolve.reportChallenge(chain.call())
            return response
        }
        if (solver == null || !isChallenge(response)) return response
        val failedAt = failedHosts[host]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_BACKOFF_MS) return response

        response.close()
        val cookies = bypassCloudflare(request)
        if (cookies != null) {
            failedHosts.remove(host)
            SeyirLog.d(TAG, "Cloudflare aşıldı: ${request.url}")
            return proceed(chain, request, cookies)
        }

        failedHosts[host] = System.currentTimeMillis()
        SeyirLog.w(TAG, "Cloudflare aşılamadı: ${request.url}")
        return chain.proceed(request)
    }

    /**
     * Cloudflare sınama yanıtı mı? Kural [CloudflareChallenge]'de. Orijinalde gövdeye bakılmıyor; ancak
     * burada yakalayıcı bütün isteklere takılı olduğu için Cloudflare arkasındaki CDN'lerin düz 403'leri
     * (ör. Referer beklenen video segmentleri) tarayıcıyı boş yere açıp dakikalarca bekletiyordu.
     */
    private fun isChallenge(response: Response): Boolean = CloudflareChallenge.isChallenge(response)

    private fun proceed(chain: Interceptor.Chain, request: Request, cookies: Map<String, String>): Response {
        val requestCookies = request.header("Cookie")?.let { parseCookieMap(it) } ?: emptyMap()
        val userAgent = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) } ?: emptyMap()
        val headers = getHeaders(request.headers.toMap() + userAgent, null, cookies + requestCookies)
        return chain.proceed(request.newBuilder().headers(headers).build())
    }

    private fun bypassCloudflare(request: Request): Map<String, String>? {
        val solver = solver ?: return null
        SeyirLog.i(TAG, "Cloudflare sınaması çözülüyor: ${request.url}")
        val result = runCatching { solver.solve(request.url.toString()) }
            .onFailure { SeyirLog.w(TAG, "Sınama çözücü hata verdi", it) }
            .getOrNull() ?: return null
        if (result.cookies["cf_clearance"] == null) return null

        result.userAgent?.let { WebViewResolver.webViewUserAgent = it }
        savedCookies[request.url.host] = result.cookies
        return result.cookies
    }
}
