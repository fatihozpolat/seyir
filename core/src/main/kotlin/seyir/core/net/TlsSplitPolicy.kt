package seyir.core.net

import okhttp3.Interceptor
import okhttp3.Response
import seyir.core.SeyirLog
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * Bölünmüş ClientHello'yu kabul etmeyen sunucuları tutar. TLS buna izin verse de bazı sunucular
 * el sıkışmayı tek kayıt bekleyerek kesiyor; DPI engeli olmayan bu sunuculara bölmeden bağlanılır.
 */
internal object TlsSplitPolicy {
    val unsplitHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

/**
 * Bölünmüş bağlantı el sıkışmada koparsa isteği bir kez bölmeden dener. Bölmesiz deneme
 * başarılıysa sunucu [TlsSplitPolicy.unsplitHosts]'a kalır; o da koparsa (DPI engeli) işaret
 * geri alınır ve ilk hata fırlatılır. Uygulama yakalayıcısı olduğu için yeniden deneme serbesttir.
 */
internal class SplitFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host.lowercase()
        if (!request.isHttps || host in TlsSplitPolicy.unsplitHosts) return chain.proceed(request)
        try {
            return chain.proceed(request)
        } catch (e: IOException) {
            if (!isHandshakeFailure(e) || chain.call().isCanceled()) throw e
            TlsSplitPolicy.unsplitHosts += host
            try {
                return chain.proceed(request).also {
                    SeyirLog.i(TAG, "$host bölünmüş TLS'i kabul etmiyor; bölmeden bağlanılacak")
                }
            } catch (retry: IOException) {
                TlsSplitPolicy.unsplitHosts -= host
                e.addSuppressed(retry)
                throw e
            }
        }
    }

    /** Zaman aşımı yeniden denenmez (bir 20 sn daha bekletir); yalnız kopma ve TLS uyarıları. */
    private fun isHandshakeFailure(e: IOException): Boolean =
        e is SSLException || e is EOFException || (e is SocketException && e !is java.net.ConnectException)

    private companion object {
        const val TAG = "TlsSplit"
    }
}
