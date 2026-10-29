package seyir.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toAwtImage
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import seyir.core.Playable
import seyir.core.SeyirLog

/**
 * Geliştirici senaryosu: `SEYIR_SCRIPT` ortam değişkenindeki adımları sırayla uygular; arayüzü
 * fare/klavye olayı göndermeden uçtan uca denemek için. Adımlar `|` ile ayrılır:
 *
 *   wait:<sn>              bekle
 *   screen:<ad>            home | search | history | bookmarks | downloads | plugins | settings
 *   home:<sağlayıcı>       ana sayfa sağlayıcısını seç
 *   search:<sorgu>         bütün sağlayıcılarda ara (bitene kadar bekler)
 *   open:<sağlayıcı>:<i>   o sağlayıcının i. arama sonucunu aç (detay yüklenene kadar bekler)
 *   links:<i>              açık detaydaki i. öğenin kaynak penceresini aç
 *   autoplay:on|off        en iyi kaynağı kendiliğinden oynat
 *   back                   geri
 *   playurl:<adres>        adresi (M3U8 ya da video) eklentisiz oynat
 *   player:<komut>[:<d>]   oynatıcı: pause | seek:<sn> | quality:<sıra> | sub:<kimlik|no> | speed:<x> | state | close
 *                          | menu:<subtitles|audio|settings|none> | full | episodes (sahte önceki/sonraki bölüm)
 *   ontop:on|off           pencereyi en üstte tut (ekran görüntüsü için)
 *   exit                   uygulamayı kapat
 *   shot:<ad>              pencerenin ekrandaki görüntüsünü SEYIR_DATA/shots/<ad>.png'ye kaydet
 *
 * Her adımdan sonra günlüğe `[script] <adım> tamam` yazılır.
 */
object DevScript {
    fun runIfRequested(controller: AppController) {
        val script = System.getenv("SEYIR_SCRIPT")?.takeIf { it.isNotBlank() } ?: return
        controller.scope.launch {
            while (!controller.ready) delay(200)
            for (step in script.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
                runCatching { run(controller, step) }
                    .onFailure { SeyirLog.w(TAG, "[script] $step başarısız: ${it.message}") }
                SeyirLog.w(TAG, "[script] $step tamam")
            }
        }
    }

    private suspend fun run(controller: AppController, step: String) {
        val parts = step.split(':', limit = 3)
        val nav = controller.navigator
        when (parts[0]) {
            "wait" -> delay((parts[1].toDouble() * 1000).toLong())
            "back" -> nav.back()
            "screen" -> nav.root(
                when (parts[1]) {
                    "search" -> Screen.Search
                    "history" -> Screen.History
                    "bookmarks" -> Screen.Bookmarks
                    "downloads" -> Screen.Downloads
                    "plugins" -> Screen.Plugins
                    "settings" -> Screen.Settings
                    else -> Screen.Home
                }
            )
            "home" -> {
                controller.updateSettings { it.copy(homeProvider = parts[1]) }
                nav.root(Screen.Home)
            }
            "autoplay" -> controller.updateSettings { it.copy(autoPlayBest = parts[1] == "on") }
            "search" -> {
                nav.root(Screen.Search)
                controller.search.run(parts[1])
                delay(300)
                waitUntil(90_000) { !controller.search.state.running }
            }
            "open" -> {
                val results = controller.search.state.results.firstOrNull { it.provider.equals(parts[1], ignoreCase = true) }?.items
                    ?: error("${parts[1]} için arama sonucu yok")
                val item = results[parts.getOrElse(2) { "0" }.toInt()]
                controller.openDetail = null
                nav.open(Screen.Detail(results.let { parts[1] }.let { name ->
                    controller.providers.first { it.name.equals(name, ignoreCase = true) }.name
                }, item.url, item.name, item.posterUrl))
                waitUntil(60_000) { controller.openDetail != null }
            }
            "links" -> {
                val detail = controller.openDetail ?: error("Açık detay yok")
                val item = detail.items[parts[1].toInt()]
                controller.linksRequest = PlayContext(detail.screen.provider, detail.screen.url, detail.name, detail.posterUrl, item)
            }
            "download" -> {
                // Açık detaydaki i. öğenin en iyi bağlantısını indirmeye başlar
                val detail = controller.openDetail ?: error("Açık detay yok")
                val item = detail.items[parts[1].toInt()]
                val api = controller.provider(detail.screen.provider) ?: error("Sağlayıcı yok")
                val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.loadLinks(item.data, false, {}, { synchronized(links) { links += it } })
                }
                val best = links.maxByOrNull { it.quality } ?: error("Bağlantı yok")
                controller.downloads.start(PlayContext(detail.screen.provider, detail.screen.url, detail.name, detail.posterUrl, item), best)
            }
            "cancel-downloads" -> controller.downloads.items.forEach { controller.downloads.cancel(it) }
            "playurl" -> {
                val url = step.substringAfter(':')
                val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val link = newExtractorLink("Deneme", "Deneme", url, type)
                val item = Playable("Bölüm 1", url, season = 1, episode = 1)
                controller.playback.play(PlayContext("Deneme", url, "Deneme Yayını", null, item), link, emptyList())
            }
            "player" -> {
                val controls = controller.playback.embedded.controls ?: error("Oynatıcı açık değil")
                val arg = parts.getOrNull(2)
                when (parts[1]) {
                    "pause" -> controls.togglePause()
                    "seek" -> controls.seekTo(arg!!.toDouble())
                    "quality" -> controls.selectQuality(controls.state.value.qualities[arg!!.toInt()].editionId)
                    "sub" -> controls.selectSubtitle(arg?.toIntOrNull())
                    "speed" -> controls.setSpeed(arg!!.toDouble())
                    "volume" -> controls.setVolume(arg!!.toDouble())
                    "close" -> controller.playback.stop()
                    "menu" -> controller.playback.embedded.menu =
                        seyir.app.player.PlayerMenu.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }
                    "full" -> controller.playback.embedded.toggleFullscreen?.invoke()
                    "episodes" -> {
                        val embedded = controller.playback.embedded
                        embedded.previousTitle = "S1 B1 — Pilot"
                        embedded.nextTitle = "S1 B3 — Uzun bir bölüm adı burada"
                        embedded.episodes = (1..3).flatMap { s ->
                            (1..6).map { e -> Playable(if (e == 2) "Uzun bir bölüm adı, iki satıra taşabilir mi bakalım" else "Bölüm $e", "s$s-e$e", s, e) }
                        }
                        embedded.currentEpisode = "s1-e2"
                        embedded.sources = listOf(
                            seyir.app.player.SourceOption(0, "Deneme", "1080p · HLS"),
                            seyir.app.player.SourceOption(1, "Yedek kaynak", "720p"),
                        )
                    }
                    "thumb" -> {
                        val source = controller.playback.embedded.thumbnails ?: error("Önizleme yok")
                        val started = System.currentTimeMillis()
                        val done = kotlinx.coroutines.CompletableDeferred<androidx.compose.ui.graphics.ImageBitmap>()
                        source.request(arg!!.toDouble(), controls.state.value) { done.complete(it) }
                        val bitmap = kotlinx.coroutines.withTimeoutOrNull(20_000) { done.await() } ?: error("Önizleme gelmedi")
                        SeyirLog.w(TAG, "[script] önizleme ${bitmap.width}x${bitmap.height} ${System.currentTimeMillis() - started} ms")
                        val file = java.io.File(java.io.File(seyir.core.AppDirs.data, "shots").apply { mkdirs() }, "thumb-$arg.png")
                        javax.imageio.ImageIO.write(bitmap.toAwtImage(), "png", file)
                    }
                    "notice" -> controller.playback.embedded.showNotice(arg)
                    "episode" -> controller.playback.playerPlayEpisode(controller.playback.embedded.episodes[arg!!.toInt()])
                    "source" -> controller.playback.playerSwitchSource(arg!!.toInt())
                    "next" -> controller.playback.playerNext()
                    "state" -> SeyirLog.w(TAG, "[script] oynatıcı: ${controls.state.value}")
                }
            }
            "ontop" -> mainWindow()?.isAlwaysOnTop = parts[1] == "on"
            "exit" -> {
                controller.shutdown()
                kotlin.system.exitProcess(0)
            }
            "wininfo" -> java.awt.Window.getWindows().forEach { w ->
                val frame = w as? java.awt.Frame
                SeyirLog.w(
                    TAG,
                    "[script] pencere ${w.javaClass.simpleName} görünür=${w.isVisible} sınır=${w.bounds} " +
                        "durum=${frame?.extendedState} üstte=${w.isAlwaysOnTop} sahip=${w.owner?.javaClass?.simpleName}",
                )
            }
            "shot" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val window = mainWindow() ?: error("Pencere yok")
                // Başka bir uygulama öndeyse onun görüntüsü alınmasın
                if (!ownsScreenAt(window)) error("Seyir penceresi önde değil; görüntü alınmadı")
                val image = java.awt.Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen, window.size))
                val dir = java.io.File(seyir.core.AppDirs.data, "shots").apply { mkdirs() }
                javax.imageio.ImageIO.write(image, "png", java.io.File(dir, "${parts[1]}.png"))
            }
            // Adres ':' içerdiği için adımın geri kalanı bütün olarak alınır: repo:https://…/repo.json
            "repo" -> controller.updateSettings { it.copy(repositories = (it.repositories + step.substringAfter(':')).distinct()) }
            "tab" -> controller.pluginsTab = parts[1].toInt()
            // Eklenti seçim penceresini açar: pick:https://…
            "pick" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                controller.pluginActions.pickerRepository = controller.plugins.fetchRepository(step.substringAfter(':'))
            }
            // Bütün eklentileri seçmeden kurar: repo-install:https://…
            "repo-install" -> {
                var done = false
                controller.pluginActions.addRepositoryAndInstall(step.substringAfter(':')) { SeyirLog.w(TAG, "[script] repo-install: $it"); done = true }
                waitUntil(300_000) { done }
            }
            // Kurulu bütün sağlayıcıları sınar ve özeti günlüğe yazar
            "probe-all" -> {
                var done = false
                controller.probes.probeAll { SeyirLog.w(TAG, "[script] probe-all: $it"); done = true }
                waitUntil(900_000) { done }
                controller.probes.results.values.sortedBy { it.provider }.forEach { SeyirLog.w(TAG, "[script] sına ${it.provider}: ${it.status}") }
            }
            "install" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val (repoUrl, entry) = controller.settings.repositories.firstNotNullOfOrNull { repo ->
                    runCatching { controller.plugins.fetchRepository(repo) }.getOrNull()?.plugins?.firstOrNull { it.internalName == parts[1] }?.let { repo to it }
                } ?: error("${parts[1]} depolarda yok")
                controller.plugins.install(entry, repoUrl)
                controller.pluginActions.reloadProviders()
            }
            "uninstall" -> {
                val plugin = controller.plugins.plugins.firstOrNull { it.manifest.name == parts[1] } ?: error("${parts[1]} kurulu değil")
                controller.plugins.uninstall(plugin)
                controller.pluginActions.reloadProviders()
            }
            else -> error("Bilinmeyen adım")
        }
    }

    private fun mainWindow(): java.awt.Frame? =
        java.awt.Frame.getFrames().firstOrNull { it is androidx.compose.ui.awt.ComposeWindow && it.isVisible }

    /** Pencerenin ortasındaki noktada bu uygulamanın bir penceresi mi var (yalnız Windows; başka yerde true). */
    private fun ownsScreenAt(window: java.awt.Window): Boolean {
        if (!seyir.core.AppDirs.isWindows) return true
        val scale = window.graphicsConfiguration.defaultTransform.scaleX
        val p = window.locationOnScreen
        val x = ((p.x + window.width / 2) * scale).toLong()
        val y = ((p.y + window.height / 2) * scale).toLong()
        val hit = User32Hit.lib.WindowFromPoint((y shl 32) or (x and 0xffffffffL)) ?: return false
        val root = com.sun.jna.Pointer.nativeValue(User32Hit.lib.GetAncestor(hit, 2) ?: return false)
        val ours = java.awt.Window.getWindows().filter { it.isDisplayable }.map { com.sun.jna.Native.getWindowID(it) }
        return root in ours
    }

    private interface User32Hit : com.sun.jna.Library {
        fun WindowFromPoint(point: Long): com.sun.jna.Pointer?
        fun GetAncestor(hwnd: com.sun.jna.Pointer?, flags: Int): com.sun.jna.Pointer?

        companion object {
            val lib: User32Hit by lazy { com.sun.jna.Native.load("user32", User32Hit::class.java) }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) delay(250)
    }

    private const val TAG = "DevScript"
}
