package seyir.core.relay

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Upstream'e giden isteğin üstbilgi kuralı. Aktarıcı ([StreamRelay]) ve oynatma öncesi ana liste
 * okuyucusu aynı kuralı kullanır; böylece bir kaynak oynatılabiliyorsa katmanları da okunabilir.
 */
internal object UpstreamRequest {
    /**
     * @param headers bağlantının kendi üstbilgileri (eklentinin verdiği)
     * @param referer bağlantının Referer'ı
     * @param origin oturumun kaydedilen ilk adresi; [originReferer] true ise Referer/Origin bundan türetilir
     * @param accept istemcinin Accept değeri; yoksa her türü kabul eder
     */
    fun builder(
        upstream: HttpUrl,
        headers: Map<String, String>,
        referer: String?,
        origin: String,
        originReferer: Boolean,
        accept: String? = null,
    ): Request.Builder {
        val builder = Request.Builder().url(upstream)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", StreamRelay.DEFAULT_USER_AGENT)
        }
        // OkHttp kendiliğinden Accept göndermez; bazı yayın sunucuları Accept'siz isteğe 200 ile hata metni döner
        if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
            builder.header("Accept", accept ?: "*/*")
        }
        if (originReferer) {
            builder.header("Referer", origin)
            origin.toHttpUrlOrNull()?.let { builder.header("Origin", "${it.scheme}://${it.host}") }
        } else {
            referer?.let { builder.header("Referer", it) }
        }
        return builder
    }
}
