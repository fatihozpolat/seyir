package seyir.app.tmdb

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import seyir.core.AppDirs
import seyir.core.SeyirLog
import seyir.core.net.Network
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Bir öneri satırı için afiş aranacak yapım: [key] sonucu çağırana geri eşler (genellikle adres). */
data class TmdbQuery(val key: String, val title: String, val typeName: String?)

/**
 * İsteğe bağlı TMDB üst veri istemcisi. Anahtar boşsa hiçbir ağ isteği atılmaz.
 *
 * - Anahtar biçimine göre v3 (`api_key` sorgu parametresi) ya da v4 belirteci (`Bearer`) kullanılır.
 * - Yanıtlar bellekte ve [cacheDir] altında [ttlMillis] (7 gün) boyunca önbelleğe alınır; anahtar
 *   hiçbir zaman diske yazılmaz (önbellek anahtarına girmez).
 * - Ağ ulaşılamazsa ya da TMDB hata verirse sessizce null döner; kısa bir süre yeniden denenmez.
 * - Bütün ağ ve disk işleri `Dispatchers.IO` üzerinde yapılır.
 */
class TmdbClient(
    private val apiKey: () -> String,
    private val language: () -> String,
    private val http: () -> OkHttpClient = { Network.client },
    private val cacheDir: File = File(AppDirs.data, "cache/tmdb"),
    private val ttlMillis: Long = TimeUnit.DAYS.toMillis(7),
    private val now: () -> Long = System::currentTimeMillis,
    private val baseUrl: String = "https://api.themoviedb.org/3",
) {
    private class Cached(val body: String, val at: Long)

    private val memory = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > MEMORY_ENTRIES
    }

    @Volatile
    private var backoffUntil = 0L

    /** TMDB'nin reddettiği anahtar; kullanıcı anahtarı değiştirene dek yeniden denenmez. */
    @Volatile
    private var rejectedKey: String? = null

    val enabled: Boolean get() = apiKey().isNotBlank() && apiKey().trim() != rejectedKey

    /**
     * [title] (+ [year], tür) için en iyi TMDB eşleşmesini verir; emin olunamıyorsa ya da TMDB'ye
     * ulaşılamıyorsa null. [episodeCount] türü belirsiz içeriklerde dizi/film ayrımına ipucu olur.
     */
    suspend fun lookup(title: String, year: Int?, typeName: String?, episodeCount: Int = 0): TmdbInfo? {
        if (!enabled || title.isBlank()) return null
        val kinds = kindsFor(typeName, episodeCount)
        if (kinds.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val candidates = kinds.flatMap { searchBlocking(it, searchTerm(title)) }
            pickBest(candidates, title, year)
        }
    }

    /**
     * Afişi olmayan öneriler için TMDB'den afiş bulur; bulunan her biri hemen [onFound]'a verilir
     * (anahtar, afiş adresi). En fazla [limit] sorgu, aynı anda en çok 3 istekle işlenir.
     */
    suspend fun fillPosters(queries: List<TmdbQuery>, limit: Int = 12, onFound: (String, String) -> Unit) {
        if (!enabled) return
        val gate = Semaphore(3)
        coroutineScope {
            queries.take(limit).map { q ->
                async {
                    gate.withPermit {
                        val info = lookup(q.title, yearInName(q.title), q.typeName)
                        info?.posterUrl?.let { onFound(q.key, it) }
                    }
                }
            }.awaitAll()
        }
    }

    /** Aramada yıl eki ve fazlalıklar ayıklanmış başlık: "El Camino (2019)" → "El Camino". */
    private fun searchTerm(title: String): String = title.replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "").trim().ifEmpty { title }

    private fun searchBlocking(kind: TmdbKind, term: String): List<TmdbInfo> {
        val body = fetch("/search/${kind.path}", mapOf("query" to term, "include_adult" to "false", "page" to "1"))
            ?: return emptyList()
        return parseSearch(body, kind)
    }

    /** Önbellek → disk → ağ sırasıyla yanıt gövdesini verir; başarısızsa null. */
    private fun fetch(path: String, params: Map<String, String>): String? {
        val all = params + ("language" to language())
        val cacheKey = path + "?" + all.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        readCache(cacheKey)?.let { return it }

        val key = apiKey().trim()
        if (key.isEmpty() || key == rejectedKey) return null
        if (now() < backoffUntil) return null

        return try {
            val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
                all.forEach { (k, v) -> addQueryParameter(k, v) }
                if (!isBearerToken(key)) addQueryParameter("api_key", key)
            }.build()
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .apply { if (isBearerToken(key)) header("Authorization", "Bearer $key") }
                .build()
            val client = http().newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> response.body.string().also { writeCache(cacheKey, it) }
                    response.code == 401 -> {
                        rejectedKey = key
                        SeyirLog.i(TAG, "TMDB anahtarı reddedildi (401); ayarlardan düzeltilene dek istek atılmayacak")
                        null
                    }
                    else -> {
                        backoffUntil = now() + BACKOFF_MILLIS
                        SeyirLog.i(TAG, "TMDB ${response.code} döndürdü; kısa süre beklenecek")
                        null
                    }
                }
            }
        } catch (e: IOException) {
            backoffUntil = now() + BACKOFF_MILLIS
            SeyirLog.d(TAG, "TMDB'ye ulaşılamadı: ${e.message}")
            null
        } catch (e: RuntimeException) {
            backoffUntil = now() + BACKOFF_MILLIS
            SeyirLog.d(TAG, "TMDB isteği başarısız: ${e.message}")
            null
        }
    }

    private fun readCache(cacheKey: String): String? {
        val t = now()
        synchronized(memory) { memory[cacheKey] }?.takeIf { t - it.at < ttlMillis }?.let { return it.body }
        val file = cacheFile(cacheKey)
        return runCatching {
            if (file.isFile && t - file.lastModified() < ttlMillis) {
                file.readText(Charsets.UTF_8).also { body -> synchronized(memory) { memory[cacheKey] = Cached(body, file.lastModified()) } }
            } else null
        }.getOrNull()
    }

    private fun writeCache(cacheKey: String, body: String) {
        synchronized(memory) { memory[cacheKey] = Cached(body, now()) }
        runCatching {
            cacheDir.mkdirs()
            cacheFile(cacheKey).writeText(body, Charsets.UTF_8)
        }.onFailure { SeyirLog.d(TAG, "TMDB önbelleği yazılamadı: ${it.message}") }
    }

    private fun cacheFile(cacheKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) }.take(40) + ".json")
    }

    companion object {
        private const val TAG = "Tmdb"
        private const val MEMORY_ENTRIES = 300
        private const val BACKOFF_MILLIS = 60_000L
        private const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"
        private const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
        private val mapper = ObjectMapper()

        /** `/search/{movie|tv}` yanıtını [TmdbInfo] listesine çevirir; bozuk yanıtta boş liste verir. */
        fun parseSearch(json: String, kind: TmdbKind): List<TmdbInfo> {
            val results = runCatching { mapper.readTree(json)?.get("results") }.getOrNull()
            if (results == null || !results.isArray) return emptyList()
            return results.mapNotNull { node -> parseItem(node, kind) }
        }

        private fun parseItem(node: JsonNode, kind: TmdbKind): TmdbInfo? {
            val id = node.get("id")?.asInt() ?: return null
            val title = text(node, if (kind == TmdbKind.Movie) "title" else "name") ?: return null
            val date = text(node, if (kind == TmdbKind.Movie) "release_date" else "first_air_date")
            val votes = node.get("vote_count")?.asInt() ?: 0
            val average = node.get("vote_average")?.asDouble() ?: 0.0
            return TmdbInfo(
                id = id,
                kind = kind,
                title = title,
                originalTitle = text(node, if (kind == TmdbKind.Movie) "original_title" else "original_name"),
                year = date?.take(4)?.toIntOrNull(),
                overview = text(node, "overview"),
                posterUrl = text(node, "poster_path")?.let { POSTER_BASE + it },
                backdropUrl = text(node, "backdrop_path")?.let { BACKDROP_BASE + it },
                rating = average.takeIf { votes > 0 && it > 0.0 },
                voteCount = votes,
            )
        }

        private fun text(node: JsonNode, field: String): String? =
            node.get(field)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
