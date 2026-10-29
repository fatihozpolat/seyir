package seyir.core.browser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import seyir.core.AppDirs
import seyir.core.ChallengeResult
import seyir.core.ChallengeSolver
import seyir.core.SeyirLog
import java.io.File
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Cloudflare sınamasını, sistemde kurulu Chromium tabanlı tarayıcıyı (Edge/Chrome/Brave/Chromium)
 * Chrome DevTools Protocol ile sürerek çözer. Tarayıcı headless değil, görünür pencerede açılır:
 * Cloudflare headless kipini yakalıyor, ayrıca gerekirse kullanıcı Turnstile kutusunu elle tıklayabilir.
 *
 * Profil klasörü ([PROFILE_DIR_NAME]) çözümler arasında kalıcıdır; böylece çerezler yeniden kullanılır.
 * Aynı anda gelen istekler sıraya girer, aynı alan adı kısa süre önce çözüldüyse önbellekten dönülür.
 *
 * @param browser tarayıcı çalıştırılabilir dosyası (her çözümde yeniden sorulur, ayar değişebilir)
 * @param proxy tarayıcının kullanacağı vekil (`host:port` ya da `http://host:port`); null ise vekilsiz
 * @param timeoutMs bir çözüm için toplam bekleme süresi (tarayıcı açılışı dahil)
 * @param cacheTtlMs başarılı bir sonucun alan adı için önbellekte tutulma süresi
 */
class BrowserChallengeSolver(
    private val browser: () -> File?,
    private val proxy: () -> String?,
    private val timeoutMs: Long = 60_000,
    private val cacheTtlMs: Long = 5 * 60_000,
) : ChallengeSolver {
    companion object {
        const val TAG = "BrowserChallengeSolver"
        const val PROFILE_DIR_NAME = "browser-profile"
        private const val POLL_INTERVAL_MS = 500L
        private const val REVEAL_AFTER_MS = 8_000L
        private const val STARTUP_TIMEOUT_MS = 20_000L
    }

    private class CachedResult(val result: ChallengeResult, val at: Long)

    private val mapper = jacksonObjectMapper()
    private val cache = HashMap<String, CachedResult>()

    private val http = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY) // DevTools adresi yereldir, sistem vekilinden geçmemeli
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Synchronized
    override fun solve(url: String): ChallengeResult? {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()
        if (host == null) {
            SeyirLog.w(TAG, "Geçersiz adres, sınama çözülemedi: $url")
            return null
        }

        cache[host]?.let {
            if (System.currentTimeMillis() - it.at < cacheTtlMs) {
                SeyirLog.d(TAG, "Önbellekten döndü: $host")
                return it.result
            }
            cache.remove(host)
        }

        val executable = browser()
        if (executable == null) {
            SeyirLog.w(TAG, "Chromium tabanlı tarayıcı bulunamadı; sınama çözülemiyor")
            return null
        }

        val result = try {
            runSession(executable, url)
        } catch (e: Exception) {
            SeyirLog.w(TAG, "Tarayıcıyla sınama çözülemedi: $url", e)
            null
        }
        if (result != null) cache[host] = CachedResult(result, System.currentTimeMillis())
        return result
    }

    /** Tarayıcıyı başlatır, sınama geçilene kadar bekler ve her durumda süreci kapatır. */
    private fun runSession(executable: File, url: String): ChallengeResult? {
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        val profileDir = File(AppDirs.data, PROFILE_DIR_NAME).apply { mkdirs() }
        val portFile = File(profileDir, "DevToolsActivePort")
        portFile.delete() // önceki oturumdan kalan eski port dosyası yanıltmasın

        val command = buildCommand(executable, profileDir, proxy(), url)
        SeyirLog.i(TAG, "Tarayıcı başlatılıyor: ${executable.name} → $url")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

        var connection: CdpConnection? = null
        try {
            val active = waitForActivePort(portFile, process, minOf(deadline, started + STARTUP_TIMEOUT_MS))
                ?: run {
                    SeyirLog.w(TAG, "Tarayıcı hata ayıklama portunu açmadı")
                    return null
                }

            val version = fetchJson("http://127.0.0.1:${active.port}/json/version")
            val wsUrl = version.get("webSocketDebuggerUrl")?.asText()
                ?: "ws://127.0.0.1:${active.port}${active.browserPath ?: ""}"
            val userAgent = CdpMessages.fixUserAgent(version.get("User-Agent")?.asText())

            connection = CdpConnection(wsUrl, http)
            connection.awaitOpen(5_000)

            val targetId = findPageTarget(connection, url, deadline)
            val sessionId = connection.call(
                "Target.attachToTarget",
                mapOf("targetId" to targetId, "flatten" to true),
            )?.get("sessionId")?.asText() ?: throw CdpException("Sayfa oturumu alınamadı")

            return pollForClearance(connection, process, targetId, sessionId, url, userAgent, deadline, started)
        } finally {
            shutdown(connection, process)
        }
    }

    /**
     * Her ~500 ms çerezlere bakar. `cf_clearance` var, sayfa başlığı artık sınama sayfası değil
     * ve bu durum art arda iki yoklamada sürüyorsa başarı sayar. (Profilde kalan eski çerez ile
     * sayfa yüklenmeden dönmemek için başlık/adres denetimi gerekli.)
     */
    private fun pollForClearance(
        connection: CdpConnection,
        process: Process,
        targetId: String,
        sessionId: String,
        url: String,
        userAgent: String?,
        deadline: Long,
        started: Long,
    ): ChallengeResult? {
        var stable = 0
        var lastTitle: String? = null
        var revealed = false
        while (System.currentTimeMillis() < deadline) {
            if (!revealed && System.currentTimeMillis() - started > REVEAL_AFTER_MS) {
                revealed = true
                revealWindow(connection, targetId)
            }
            if (!process.isAlive) {
                SeyirLog.w(TAG, "Tarayıcı sınama bitmeden kapandı")
                return null
            }
            val cookies = CdpMessages.cookiesToMap(
                connection.call("Network.getCookies", mapOf("urls" to listOf(url)), sessionId),
            )
            val info = pageInfo(connection, targetId)
            lastTitle = info?.get("title")?.asText()
            val pageUrl = info?.get("url")?.asText().orEmpty()
            val ready = pageUrl.startsWith("http") && !CdpMessages.isChallengeTitle(lastTitle)

            if (cookies.containsKey("cf_clearance") && ready) {
                if (++stable >= 2) {
                    val seconds = (System.currentTimeMillis() - started) / 1000.0
                    SeyirLog.i(TAG, "Sınama geçildi (%.1f sn): %s".format(seconds, url))
                    return ChallengeResult(cookies, userAgent)
                }
            } else {
                stable = 0
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        SeyirLog.w(TAG, "Sınama zaman aşımına uğradı ($timeoutMs ms), son sayfa başlığı: $lastTitle")
        return null
    }

    /** Kendiliğinden geçmeyen sınamada (Turnstile kutusu) pencereyi kullanıcının görebileceği yere taşır. */
    private fun revealWindow(connection: CdpConnection, targetId: String) {
        runCatching {
            val windowId = connection.call("Browser.getWindowForTarget", mapOf("targetId" to targetId))
                ?.get("windowId")?.asInt() ?: return
            connection.call(
                "Browser.setWindowBounds",
                mapOf("windowId" to windowId, "bounds" to mapOf("left" to 240, "top" to 140, "width" to 520, "height" to 640)),
            )
            SeyirLog.i(TAG, "Sınama kendiliğinden geçmedi; tarayıcı penceresi gösterildi")
        }.onFailure { SeyirLog.d(TAG, "Pencere gösterilemedi: ${it.message}") }
    }

    private fun pageInfo(connection: CdpConnection, targetId: String): JsonNode? =
        connection.call("Target.getTargets")?.get("targetInfos")
            ?.firstOrNull { it.get("targetId")?.asText() == targetId }

    /** Komut satırında verilen adresi açan sayfa hedefini bulur; yoksa yeni sekme açar. */
    private fun findPageTarget(connection: CdpConnection, url: String, deadline: Long): String {
        val giveUpAt = minOf(deadline, System.currentTimeMillis() + 5_000)
        while (System.currentTimeMillis() < giveUpAt) {
            val pages = connection.call("Target.getTargets")?.get("targetInfos")
                ?.filter { it.get("type")?.asText() == "page" && it.get("url")?.asText()?.startsWith("devtools:") != true }
                .orEmpty()
            pages.firstOrNull()?.get("targetId")?.asText()?.let { return it }
            Thread.sleep(200)
        }
        return connection.call("Target.createTarget", mapOf("url" to url))?.get("targetId")?.asText()
            ?: throw CdpException("Sayfa hedefi açılamadı")
    }

    private fun waitForActivePort(portFile: File, process: Process, until: Long): DevToolsActivePort? {
        while (System.currentTimeMillis() < until) {
            if (!process.isAlive) return null
            if (portFile.isFile) {
                CdpMessages.parseActivePort(runCatching { portFile.readText() }.getOrDefault(""))?.let { return it }
            }
            Thread.sleep(100)
        }
        return null
    }

    private fun fetchJson(url: String): JsonNode {
        http.newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful) { "$url ${it.code} döndü" }
            return mapper.readTree(it.body.string())
        }
    }

    /** Önce tarayıcıyı nazikçe kapatır (çerezler diske yazılsın), olmazsa süreç ağacını öldürür. */
    private fun shutdown(connection: CdpConnection?, process: Process) {
        if (connection != null) {
            runCatching { connection.call("Browser.close", timeoutMs = 2_000) }
            connection.close()
        }
        runCatching { process.waitFor(3, TimeUnit.SECONDS) }
        killTree(process)
        runCatching { process.waitFor(5, TimeUnit.SECONDS) }
    }

    private fun killTree(process: Process) {
        val handle = process.toHandle()
        val pid = handle.pid()
        val descendants = handle.descendants().toList()
        if (AppDirs.isWindows && handle.isAlive) {
            // Windows'ta alt süreçleri (GPU, renderer) tek hamlede almanın en güvenilir yolu
            runCatching {
                ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(5, TimeUnit.SECONDS)
            }
        }
        descendants.forEach { runCatching { it.destroyForcibly() } }
        if (handle.isAlive) handle.destroyForcibly()
    }

    internal fun buildCommand(executable: File, profileDir: File, proxy: String?, url: String): List<String> {
        val args = mutableListOf(
            executable.absolutePath,
            "--remote-debugging-port=0",
            "--user-data-dir=${profileDir.absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-blink-features=AutomationControlled",
            "--window-size=520,640",
            // Önce ekran dışında açılır; sınama kendiliğinden geçmezse [revealWindow] ile görünür yapılır.
            // Ekran dışı pencere örtülü sayılıp yavaşlatılmasın diye arka plan kısıtları kapatılır.
            "--window-position=-32000,-32000",
            "--disable-features=CalculateNativeWinOcclusion",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
        )
        proxyArgument(proxy)?.let { args += it }
        // Chromium Linux'ta root kullanıcısıyla kum havuzu olmadan açılmayı reddediyor
        if (!AppDirs.isWindows && System.getProperty("user.name") == "root") args += "--no-sandbox"
        args += url
        return args
    }

    private fun proxyArgument(proxy: String?): String? {
        val value = proxy?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "--proxy-server=" + if ("://" in value) value else "http://$value"
    }
}
