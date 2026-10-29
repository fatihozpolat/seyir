package seyir.core

import okhttp3.Response

/**
 * Cloudflare insan doğrulaması ("Just a moment", Turnstile) yanıtını tanıma kuralı. Hem
 * [com.lagradost.cloudstream3.network.CloudflareKiller] (çözücüyü ne zaman başlatacağına karar verir)
 * hem de Sına (sağlayıcıyı "doğrulama gerekiyor" diye işaretler) aynı kuralı kullanır.
 *
 * Kural: `cf-mitigated: challenge` başlığı kesin kanıttır. Başlık yoksa yanıt cloudflare sunucusundan,
 * 403/503 koduyla gelmeli ve gövdesi yalnız sınama sayfasına özgü bir işaret taşımalıdır. Gövdeye bakılması
 * Cloudflare arkasındaki CDN'lerin düz 403'lerinin (ör. Referer beklenen video segmentleri) sınama sayılmasını
 * önler. Zayıf işaretler kullanılmaz: "Just a moment" bakım sayfalarında da geçer, `challenge-platform` ise
 * Cloudflare'in JS algılaması açıkken sıradan hata sayfalarına eklenen `scripts/jsd` betiğinde de bulunur.
 * 429 (hız sınırı) yalnız başlıkla birlikte sınama sayılır.
 */
object CloudflareChallenge {
    private val ERROR_CODES = setOf(403, 503)
    private val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")

    /** Yalnız sınama ("Just a moment", Turnstile) sayfalarında bulunan işaretler (küçük harfle). */
    private val MARKERS = listOf(
        "cf_chl_opt", "cf-chl-widget", "cf-turnstile", "challenges.cloudflare.com/turnstile",
        "challenge-platform/h/", "<title>just a moment",
    )

    /** Gövdede işaret aranan en çok bayt. */
    const val PEEK_BYTES = 256L * 1024

    /**
     * @param cfMitigated `cf-mitigated` başlığı
     * @param server `Server` başlığı
     * @param body gövdenin başı; yalnız gerekirse (başlık kesin değilse ve kod/sunucu uyuyorsa) çağrılır
     */
    fun matches(code: Int, cfMitigated: String?, server: String?, body: () -> String): Boolean {
        if (cfMitigated?.trim().equals("challenge", ignoreCase = true)) return true
        if (code !in ERROR_CODES || server?.trim()?.lowercase() !in CLOUDFLARE_SERVERS) return false
        val text = body().lowercase()
        return MARKERS.any { text.contains(it) }
    }

    /** [response] bir Cloudflare sınaması mı; gövde tüketilmeden (peek) okunur. */
    fun isChallenge(response: Response): Boolean =
        matches(response.code, response.header("cf-mitigated"), response.header("Server")) {
            runCatching { response.peekBody(PEEK_BYTES).string() }.getOrDefault("")
        }
}
