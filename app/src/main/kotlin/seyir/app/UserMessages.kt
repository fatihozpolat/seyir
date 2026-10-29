package seyir.app

import kotlinx.coroutines.TimeoutCancellationException
import seyir.app.i18n.I18n
import seyir.app.i18n.UiStrings
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * Hata nesnelerini kullanıcının anlayacağı, arayüz diline uygun cümlelere çevirir. Ham
 * `Throwable.message` çoğunlukla İngilizce ve teknik olduğu için doğrudan gösterilmez.
 *
 * HTTP katmanı (NiceHttp/OkHttp) 4xx/5xx yanıtlarında kendiliğinden istisna fırlatmaz; durum
 * kodu, eklentinin ya da indirme servisinin kendi istisna iletisinde ("HTTP 403") görünür. Bu yüzden
 * kod, istisna türlerinin yanında ileti metninden de okunur.
 */
fun userMessage(error: Throwable, t: UiStrings = I18n.current): String {
    // Sarmalanmış hatalarda asıl neden iç zincirdedir; ilk tanınan nedene göre karar verilir
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        classify(current, current.message, t)?.let { return it }
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    return t.errorGeneric(briefly(error.message ?: error::class.simpleName.orEmpty()))
}

/** Hata nesnesi olmayan ham metin (ör. mpv'nin son çıktı satırı) için aynı eşleme. */
fun userMessage(message: String?, t: UiStrings = I18n.current): String {
    val text = message?.trim().orEmpty()
    return classify(null, text, t) ?: t.errorGeneric(briefly(text.ifEmpty { "?" }))
}

/** Metin bilinen bir hata türüne uyuyorsa kullanıcı cümlesini, uymuyorsa null döndürür (genel sarmalayıcı eklemez). */
fun knownMessage(message: String?, t: UiStrings = I18n.current): String? = classify(null, message.orEmpty(), t)

/** Tanınan bir hata türü varsa kullanıcı metnini, yoksa null döndürür. */
private fun classify(error: Throwable?, message: String?, t: UiStrings): String? {
    val text = message.orEmpty()

    // Zaman aşımı, iptalden önce gelir (TimeoutCancellationException bir iptal istisnasıdır)
    if (error is SocketTimeoutException || error is TimeoutException || error is TimeoutCancellationException ||
        (error is InterruptedIOException && !text.contains("cancel", ignoreCase = true)) ||
        TIMEOUT.containsMatchIn(text)
    ) return t.errorTimeout
    if (error is CancellationException || text.equals("Canceled", ignoreCase = true) || text.equals("Cancelled", ignoreCase = true)) {
        return t.errorCancelled
    }
    if (error is UnknownHostException || error is NoRouteToHostException || error is ConnectException || NO_NETWORK.containsMatchIn(text)) {
        return t.errorNoNetwork
    }
    // Cloudflare, SSL'den ve durum kodundan önce: engel sayfası genellikle 403 ile gelir
    if (CHALLENGE.containsMatchIn(text)) return t.errorChallenge
    if (error is SSLException || error is CertificateException || SSL.containsMatchIn(text)) return t.errorSsl

    val code = STATUS.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
    return when (code) {
        null -> null
        403 -> t.errorForbidden
        404 -> t.errorNotFound
        in 500..599 -> t.errorServer(code)
        in 400..499 -> t.errorHttp(code)
        else -> null
    }
}

/** Metnin ilk dolu satırı, en çok 160 karakter. */
internal fun briefly(text: String): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length > MAX_DETAIL) line.take(MAX_DETAIL - 1) + "…" else line
}

private const val MAX_CAUSE_DEPTH = 6
private const val MAX_DETAIL = 160

private val TIMEOUT = Regex("""(?i)time[d ]?-?out|timed out|zaman aşımı""")
private val NO_NETWORK = Regex("""(?i)unable to resolve host|unknownhost|no address associated|network is unreachable|no route to host|nodename nor servname|adres bulunamadı""")
private val CHALLENGE = Regex("""(?i)cloudflare|just a moment|cf-chl|challenge|captcha|attention required""")
private val SSL = Regex("""(?i)\bssl\b|handshake|pkix|certificate|sertifika""")

/** "HTTP 403", "HTTP error 404", "status code: 502", "code=503" ve "403 Forbidden" kalıpları. */
private val STATUS = Regex("""(?i)(?:\bhttp(?:\s+error)?|status(?:\s+code)?|\bcode)\s*[:=]?\s*([1-5]\d{2})\b|\b([45]\d{2})\s+(?:forbidden|not found|bad gateway|service unavailable|gateway time-?out|internal server error|too many requests)""")
