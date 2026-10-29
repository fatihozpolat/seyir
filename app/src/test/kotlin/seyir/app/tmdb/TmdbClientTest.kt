package seyir.app.tmdb

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ağ yerine sahte bir OkHttp yanıtlayıcısı kullanır; önbellek, kimlik doğrulama ve sessiz başarısızlık sınanır. */
class TmdbClientTest {
    private val body = """{"results":[{"id":1,"title":"Dune","release_date":"2021-09-15","poster_path":"/a.jpg","overview":"x","vote_average":8.0,"vote_count":10}]}"""

    private fun client(requests: MutableList<Request>, code: Int = 200, fail: Boolean = false): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            if (fail) throw IOException("çevrimdışı")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun tmdb(http: OkHttpClient, key: String, dir: java.io.File, now: () -> Long = System::currentTimeMillis) =
        TmdbClient(apiKey = { key }, language = { "tr-TR" }, http = { http }, cacheDir = dir, now = now)

    @Test
    fun v3KeyGoesInQueryAndResultsAreCached() = runBlocking {
        val requests = mutableListOf<Request>()
        val dir = Files.createTempDirectory("tmdb-test").toFile()
        val client = tmdb(client(requests), "0123456789abcdef0123456789abcdef", dir)
        assertEquals(1, client.lookup("Dune", 2021, "Movie")?.id)
        assertEquals(1, client.lookup("Dune", 2021, "Movie")?.id)
        assertEquals(1, requests.size)
        val url = requests.first().url
        assertEquals("0123456789abcdef0123456789abcdef", url.queryParameter("api_key"))
        assertEquals("tr-TR", url.queryParameter("language"))
        assertNull(requests.first().header("Authorization"))
        // Anahtar diskteki önbelleğe yazılmaz; yeni bir istemci diskten okur
        assertTrue(dir.listFiles().orEmpty().none { it.readText().contains("0123456789abcdef") })
        val second = tmdb(client(requests), "0123456789abcdef0123456789abcdef", dir)
        assertEquals(1, second.lookup("Dune", 2021, "Movie")?.id)
        assertEquals(1, requests.size)
    }

    @Test
    fun expiredDiskCacheIsRefetched() = runBlocking {
        val requests = mutableListOf<Request>()
        val dir = Files.createTempDirectory("tmdb-test").toFile()
        val key = "0123456789abcdef0123456789abcdef"
        tmdb(client(requests), key, dir).lookup("Dune", 2021, "Movie")
        val eightDays = System.currentTimeMillis() + 8L * 24 * 3600 * 1000
        tmdb(client(requests), key, dir, now = { eightDays }).lookup("Dune", 2021, "Movie")
        assertEquals(2, requests.size)
    }

    @Test
    fun v4TokenGoesInBearerHeader() = runBlocking {
        val requests = mutableListOf<Request>()
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2ln"
        tmdb(client(requests), token, Files.createTempDirectory("tmdb-test").toFile()).lookup("Dune", 2021, "Movie")
        assertEquals("Bearer $token", requests.single().header("Authorization"))
        assertNull(requests.single().url.queryParameter("api_key"))
    }

    @Test
    fun blankKeyDoesNothing() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = tmdb(client(requests), "  ", Files.createTempDirectory("tmdb-test").toFile())
        assertFalse(client.enabled)
        assertNull(client.lookup("Dune", 2021, "Movie"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun unreachableServerFailsSilentlyAndBacksOff() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = tmdb(client(requests, fail = true), "0123456789abcdef0123456789abcdef", Files.createTempDirectory("tmdb-test").toFile())
        assertNull(client.lookup("Dune", 2021, "Movie"))
        assertNull(client.lookup("Dune", 2021, "Movie"))
        assertEquals(1, requests.size)
    }

    @Test
    fun rejectedKeyStopsRequests() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = tmdb(client(requests, code = 401), "0123456789abcdef0123456789abcdef", Files.createTempDirectory("tmdb-test").toFile())
        assertNull(client.lookup("Dune", 2021, "Movie"))
        assertFalse(client.enabled)
        assertNull(client.lookup("Another", 2021, "Movie"))
        assertEquals(1, requests.size)
    }

    @Test
    fun fillPostersReportsFoundOnes() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = tmdb(client(requests), "0123456789abcdef0123456789abcdef", Files.createTempDirectory("tmdb-test").toFile())
        val found = java.util.concurrent.ConcurrentHashMap<String, String>()
        client.fillPosters(listOf(TmdbQuery("u1", "Dune (2021)", "Movie"), TmdbQuery("u2", "Başka Bir Film", "Movie"))) { k, p -> found[k] = p }
        assertEquals(mapOf("u1" to "https://image.tmdb.org/t/p/w500/a.jpg"), found.toMap())
    }
}
