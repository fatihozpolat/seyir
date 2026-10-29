package seyir.core.hls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import seyir.core.SeyirLog
import seyir.core.relay.UpstreamRequest
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * M3U8 bağlantısının ana listesini çekip katmanlarını çıkarır. Oynatmayı hiçbir koşulda engellememesi
 * için her hata (ağ, zaman aşımı, ana liste olmayan yanıt) sessizce "katman yok" olur.
 *
 * İstek, aktarıcıyla aynı kuralla kurulur ([UpstreamRequest]): bağlantının üstbilgileri, Referer,
 * `Accept` ve eklentinin `getVideoInterceptor` yakalayıcısı. Aktarıcı gibi, 401-403 yanıtında
 * kaydedilen ilk adres Referer yapılarak bir kez daha denenir.
 *
 * Sonuçlar bellekte önbelleğe alınır; başarısızlıklar kısa süre hatırlanır ki aynı bozuk adrese art arda
 * gidilmesin. Eşzamanlı istek sayısı [maxConcurrent] ile sınırlıdır.
 *
 * @param client ağ istemcisi (parçalama/DoH/vekil dahil)
 * @param timeoutMs bir ana liste isteğinin toplam süre sınırı
 */
class HlsVariantFetcher(
    private val client: () -> OkHttpClient,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    maxConcurrent: Int = MAX_CONCURRENT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val layers: List<HlsVariant>, val expiresAt: Long)

    private val permits = Semaphore(maxConcurrent)
    private val cache: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_CACHE
        },
    )

    /** Önbellekteki katmanlar; çekilmediyse ya da süresi dolduysa null (ağa çıkmaz). */
    fun cached(url: String, referer: String?): List<HlsVariant>? {
        val key = cacheKey(url, referer)
        val entry = cache[key] ?: return null
        if (clock() >= entry.expiresAt) {
            cache.remove(key)
            return null
        }
        return entry.layers
    }

    /**
     * Bağlantının seçilebilir katmanlarını ([HlsQuality.layers]) döndürür; katman bulunamadıysa ya da
     * herhangi bir hata olduysa boş liste. İptal edilirse [CancellationException] fırlatır.
     */
    suspend fun layers(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        interceptor: Interceptor? = null,
    ): List<HlsVariant> {
        cached(url, referer)?.let { return it }
        return permits.withPermit {
            // Sıra beklerken başka bir istek aynı adresi getirmiş olabilir
            cached(url, referer)?.let { return@withPermit it }
            val result = withContext(Dispatchers.IO) { fetchBlocking(url, headers, referer, interceptor) }
            val ttl = if (result != null) SUCCESS_TTL_MS else FAILURE_TTL_MS
            val layers = result?.let(HlsQuality::layers).orEmpty()
            cache[cacheKey(url, referer)] = Entry(layers, clock() + ttl)
            layers
        }
    }

    /** Ağdan ana listeyi okur; hata olursa null (iptal hariç). */
    private suspend fun fetchBlocking(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        interceptor: Interceptor?,
    ): List<HlsVariant>? {
        val upstream = url.toHttpUrlOrNull() ?: return null
        val http = client().newBuilder().apply {
            callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            interceptor?.let { addInterceptor(it) }
        }.build()
        try {
            fun request(originReferer: Boolean) = UpstreamRequest.builder(upstream, headers, referer, url, originReferer).build()

            var response = http.newCall(request(originReferer = false)).await()
            val currentReferer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: referer
            if (response.code in 401..403 && currentReferer != url) {
                response.close()
                response = http.newCall(request(originReferer = true)).await()
            }
            response.use { r ->
                if (!r.isSuccessful) return null
                val text = readLimited(r) ?: return null
                if (!HlsMasterParser.isMaster(text)) return emptyList()
                return HlsMasterParser.parse(text, r.request.url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SeyirLog.d(TAG, "Ana liste okunamadı $url: ${e.message}")
            return null
        }
    }

    /** İstek eşzamansız yapılır; korutin iptal edilince süren çağrı da kesilir. */
    private suspend fun okhttp3.Call.await(): okhttp3.Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                // İptal edilmişse (denetim ile teslim arasında iptal edilse bile) yanıtı kimse alamaz; bağlantı sızmasın
                cont.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

    private fun readLimited(response: okhttp3.Response): String? {
        val source = response.body.source()
        // MAX_BYTES'tan büyük yanıt ana liste değildir (video dosyası vb.); okumadan vazgeç
        if (source.request(MAX_BYTES + 1)) return null
        return source.buffer.readUtf8()
    }

    private fun cacheKey(url: String, referer: String?) = "$url\n${referer.orEmpty()}"

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val MAX_CONCURRENT = 3
        private const val SUCCESS_TTL_MS = 10 * 60 * 1000L
        private const val FAILURE_TTL_MS = 30 * 1000L
        private const val MAX_CACHE = 256
        private const val MAX_BYTES = 2L * 1024 * 1024
        private const val TAG = "HlsVariantFetcher"
    }
}
