package com.lagradost.cloudstream3.network

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import seyir.core.ChallengeSolver
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudflareKillerTest {
    private lateinit var server: HttpServer
    private val solverCalls = AtomicInteger()
    private var previousSolver: ChallengeSolver? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        fun route(path: String, code: Int, contentType: String, body: String, extra: Map<String, String> = emptyMap()) {
            server.createContext(path) { ex ->
                ex.responseHeaders.add("Server", "cloudflare")
                ex.responseHeaders.add("Content-Type", contentType)
                extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        route("/segment", 403, "text/plain", "Forbidden")
        route("/blocked-html", 403, "text/html", "<html><body>Erişim yok</body></html>")
        route("/challenge", 403, "text/html", "<html><script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script></html>")
        route("/mitigated", 403, "text/plain", "", mapOf("cf-mitigated" to "challenge"))
        server.start()
        previousSolver = CloudflareKiller.solver
        CloudflareKiller.solver = ChallengeSolver { solverCalls.incrementAndGet(); null }
    }

    @AfterTest
    fun tearDown() {
        CloudflareKiller.solver = previousSolver
        server.stop(0)
    }

    private fun get(killer: CloudflareKiller, path: String): Int {
        val client = OkHttpClient.Builder().addInterceptor(killer).build()
        val url = "http://127.0.0.1:${server.address.port}$path"
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }

    @Test
    fun plainCloudflare403DoesNotOpenBrowser() {
        val killer = CloudflareKiller()
        assertEquals(403, get(killer, "/segment"))
        assertEquals(403, get(killer, "/blocked-html"))
        assertEquals(0, solverCalls.get())
    }

    @Test
    fun challengePageIsSolved() {
        assertEquals(403, get(CloudflareKiller(), "/challenge"))
        assertEquals(1, solverCalls.get())
        assertEquals(403, get(CloudflareKiller(), "/mitigated"))
        assertEquals(2, solverCalls.get())
    }

    @Test
    fun failedHostIsNotRetriedImmediately() {
        val killer = CloudflareKiller()
        get(killer, "/challenge")
        get(killer, "/challenge")
        get(killer, "/mitigated")
        assertEquals(1, solverCalls.get())
    }

    @Test
    fun noSolveHeaderSkipsSolverAndIsStripped() {
        var seenHeader: String? = "yok"
        server.createContext("/echo") { ex ->
            seenHeader = ex.requestHeaders.getFirst(seyir.core.NO_SOLVE_HEADER)
            ex.responseHeaders.add("Server", "cloudflare")
            ex.responseHeaders.add("Content-Type", "text/html")
            val bytes = "<html><script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script></html>".toByteArray()
            ex.sendResponseHeaders(403, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val client = OkHttpClient.Builder().addInterceptor(CloudflareKiller()).build()
        val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/echo")
            .header(seyir.core.NO_SOLVE_HEADER, "1").build()
        assertEquals(403, client.newCall(request).execute().use { it.code })
        assertEquals(0, solverCalls.get())
        assertEquals(null, seenHeader)
    }

    /** Çağrıyı `enqueue` ile (NiceHttp gibi OkHttp'nin kendi iş parçacığında) yürütür. */
    private suspend fun enqueueCode(client: OkHttpClient, path: String): Int = kotlin.coroutines.suspendCoroutine { cont ->
        val request = Request.Builder().url("http://127.0.0.1:${server.address.port}$path").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = cont.resumeWith(Result.failure(e))
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = cont.resumeWith(Result.success(response.use { it.code }))
        })
    }

    @Test
    fun noSolveContextSkipsSolverForEnqueuedCalls() {
        val client = OkHttpClient.Builder().addInterceptor(CloudflareKiller()).eventListenerFactory(seyir.core.NoSolve.listenerFactory).build()
        val code = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + seyir.core.NoSolve.context()) { enqueueCode(client, "/challenge") }
        }
        assertEquals(403, code)
        assertEquals(0, solverCalls.get())
        // Bağlam dışında aynı istek çözücüyü tetikler
        kotlinx.coroutines.runBlocking { enqueueCode(client, "/challenge") }
        assertEquals(1, solverCalls.get())
    }

    @Test
    fun noSolveFlagAppliesToSynchronousExecute() {
        val client = OkHttpClient.Builder().addInterceptor(CloudflareKiller()).build()
        val code = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + seyir.core.NoSolve.context()) {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/challenge").build()).execute().use { it.code }
            }
        }
        assertEquals(403, code)
        assertEquals(0, solverCalls.get())
    }
}
