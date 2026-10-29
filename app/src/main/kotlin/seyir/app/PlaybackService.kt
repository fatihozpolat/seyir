package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import seyir.app.data.WatchEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import seyir.app.i18n.EnglishStrings
import seyir.app.i18n.I18n
import seyir.app.i18n.UiStrings
import seyir.app.player.EmbeddedPlayer
import seyir.app.player.SourceOption
import seyir.app.player.ThumbnailSource
import seyir.app.ui.episodeCode
import seyir.app.ui.orderedLinks
import seyir.core.EpisodeLabels
import seyir.core.Playable
import seyir.core.nextAfter
import seyir.core.previousBefore
import seyir.core.playables
import seyir.core.SeyirLog
import seyir.core.browser.BrowserChallengeSolver
import seyir.core.browser.BrowserLocator
import seyir.core.hls.HlsQuality
import seyir.core.hls.HlsVariant
import seyir.core.hls.HlsVariantFetcher
import seyir.core.hls.QualityPreference
import seyir.core.hls.QualityTarget
import seyir.core.net.Network
import seyir.core.player.MpvLocator
import seyir.core.player.MpvPlayer
import seyir.core.player.MpvUi
import seyir.core.player.PlayerUi
import seyir.core.player.PlayRequest
import seyir.core.player.PlaybackListener
import seyir.core.player.PlaybackSession
import seyir.core.player.PlaybackSummary
import seyir.core.player.PlayerMessages
import seyir.core.player.PlayerWindowState
import seyir.core.player.SubtitleTrack
import seyir.core.relay.StreamKind
import seyir.core.relay.StreamRelay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Oynatılacak öğenin bağlamı (geçmişe yazmak için). */
data class PlayContext(
    val provider: String,
    val loadUrl: String,
    val showName: String,
    val posterUrl: String?,
    val item: Playable,
    /** Aynı yapımın sıralı öğeleri; sıradaki bölümü bulmak için (boşsa gerektiğinde eklentiden yüklenir). */
    val siblings: List<Playable> = emptyList(),
)

/** Çekirdeğin bölüm başlıklarındaki sezon/bölüm kodlarını arayüz dilinde üretir ("S1 B2" / "S1 E2"). */
class UiEpisodeLabels(private val t: UiStrings) : EpisodeLabels {
    override fun season(n: Int) = t.seasonShort(n)
    override fun episode(n: Int) = t.episodeShort(n)
}

/** Oynatıcı katmanının hata metinlerini arayüz diline bağlar. */
private class UiPlayerMessages(private val t: UiStrings) : PlayerMessages {
    override fun startFailed(reason: String?) = t.playerStartFailed(reason)
    override fun exitedWithCode(code: Int, tail: String) = t.playerExitedWithCode(code, tail)
    override fun sessionFailed(reason: String?) = t.playerSessionFailed(reason)
    override val subtitleTitle = t.subtitleFallbackTitle
}

/**
 * Oynatma hattı: bağlantı yerel aktarıcıya kaydedilir (başlıklar, engel aşma, uzantı düzeltme
 * orada yapılır), mpv aktarıcının adresini açar, mpv'nin bildirdiği konum geçmişe yazılır.
 * Ayrıca Cloudflare çözücüsünü ve aktarıcının tarayıcıya verdiği CONNECT vekilini kurar.
 */
class PlaybackService(private val controller: AppController) : AutoCloseable {
    val relay = StreamRelay({ Network.client }, { host, port -> Network.openTunnel(host, port) })

    /** M3U8 ana listelerinin katmanları (kalite seçimi); kaynaklar penceresi ve oynatma ortak kullanır. */
    private val hlsVariants = HlsVariantFetcher({ Network.client })

    var nowPlaying by mutableStateOf<String?>(null)
        private set
    private val lastErrorState = mutableStateOf<String?>(null)

    /** Son oynatma hatası; alt şeritte gösterilir ve günlüğe yazılır. */
    var lastError: String?
        get() = lastErrorState.value
        set(value) {
            lastErrorState.value = value
            if (value != null) SeyirLog.w(TAG, value)
        }
    var playerInfo by mutableStateOf("")
        private set

    /** Uygulama içi oynatıcının ekran durumu (ayarlarda açıksa video Seyir penceresinde oynar). */
    val embedded = EmbeddedPlayer()

    /** Şu an oynayan (ya da en son başlatılan) denemesi; oynatıcı düğmeleri ve kapanış kararı için. */
    @Volatile
    private var activeAttempt: Attempt? = null
    var browserInfo by mutableStateOf("")
        private set

    @Volatile
    private var player: MpvPlayer? = null
    @Volatile
    private var session: PlaybackSession? = null

    /**
     * Ayarlar değişince yerini yenisine bırakan, ama hâlâ pencere açık olabilen eski oynatıcılar; bir sonraki
     * oynatmada (yeni pencere açılırken) kapatılır.
     */
    private val retiredPlayers = CopyOnWriteArrayList<MpvPlayer>()

    /** Oynatıcı penceresinin durumunu ayarlara seyrek yazar. */
    private val windowSaver = WindowStateSaver()

    /** Seyir oynatıcı arayüzünün klasörü; arayüz kapalıysa ya da mpv desteklemiyorsa null (düz mpv). */
    @Volatile
    private var uiDir: String? = null

    /** Önizleme sürecinin kullanacağı mpv yolu (yalnız PATH dışındaki Linux kurulumları için). */
    @Volatile
    private var uiMpvPath: String? = null

    private val solver = BrowserChallengeSolver(
        browser = { BrowserLocator.find(controller.settings.browserPath.ifBlank { null }) },
        // Tarayıcı da engele takılmasın diye aktarıcının parçalamalı CONNECT tünelinden geçer
        proxy = { relay.proxyAddress },
    )

    fun start() {
        relay.start()
        onSettingsChanged()
    }

    /** Oynatıcıyı ve tarayıcıyı yeniden bulur; yavaş olabilir, arayüz iş parçacığından çağrılmaz. */
    @Synchronized
    fun onSettingsChanged() {
        val settings = controller.settings
        val command = MpvLocator.find(settings.mpvPath.ifBlank { null })
        // Aynı mpv ise oynatıcı korunur: açık pencere bir sonraki bölümde yeniden kullanılsın
        val existing = player
        if (existing == null || existing.command != command) {
            if (existing != null) retiredPlayers += existing
            player = command?.let { MpvPlayer(it) }
        }
        val uiFolder = if (settings.playerUi && command != null && MpvUi.commandSupportsUi(command)) MpvUi.locate() else null
        uiDir = uiFolder?.absolutePath
        uiMpvPath = command?.argv?.singleOrNull()?.takeIf { !seyir.core.AppDirs.isWindows }
        val t = I18n.current
        playerInfo = command?.let { t.playerInUse(it.description) } ?: t.mpvNotFound
        val browser = BrowserLocator.find(settings.browserPath.ifBlank { null })
        browserInfo = browser?.let { t.playerInUse(it.absolutePath) } ?: t.browserNotFound
        CloudflareKiller.solver = if (settings.solveCloudflare) solver else null
    }

    /**
     * Bağlantının aktarıcıdaki karşılığını üretir; oynatma, indirme ve televizyona gönderim ortak kullanır.
     * @param provider bağlantıyı veren eklenti; `getVideoInterceptor` ile istek değiştiriyorsa uygulanır.
     * @param lanHost televizyon için: adres bu bilgisayarın ağ adresiyle kurulur.
     */
    fun localUrl(link: ExtractorLink, provider: String? = null, lanHost: String? = null): String {
        val interceptor = provider?.let(controller::provider)?.let { api ->
            runCatching { api.getVideoInterceptor(link) }.getOrNull()
        }
        return relay.register(link.url, kindOf(link), link.headers, link.referer.ifBlank { null }, interceptor, lanHost)
    }

    /**
     * M3U8 bağlantının seçilebilir kalite katmanları (yüksekten alçağa). Ana liste bağlantının
     * üstbilgileri, Referer'ı ve eklentinin yakalayıcısıyla kısa zaman aşımıyla çekilir ve önbelleğe
     * alınır; M3U8 olmayan bağlantıda, tek katmanda ya da herhangi bir hatada boş liste döner.
     */
    suspend fun layersOf(link: ExtractorLink, provider: String?): List<HlsVariant> {
        if (link.type != ExtractorLinkType.M3U8) return emptyList()
        val interceptor = provider?.let(controller::provider)?.let { api ->
            runCatching { api.getVideoInterceptor(link) }.getOrNull()
        }
        return hlsVariants.layers(link.url, link.headers, link.referer.ifBlank { null }, interceptor)
    }

    /**
     * Oynatma başlarken mpv'ye verilecek kalite seçeneği. [explicit] kullanıcının bu oynatma zincirinde
     * açıkça seçtiği kalitedir; yoksa ayarlardaki varsayılan kalite uygulanır. "En yüksek" için ağa
     * çıkılmaz ve seçenek verilmez (mpv zaten en yükseği açar). Katmanlar alınamazsa seçenek verilmez.
     */
    private fun qualityArgs(link: ExtractorLink, provider: String, explicit: QualityTarget?): List<String> {
        val target = explicit ?: QualityPreference.fromId(controller.settings.defaultQuality).target
        if (target == QualityTarget.Highest || link.type != ExtractorLinkType.M3U8) return emptyList()
        val layers = runCatching { runBlocking { layersOf(link, provider) } }.getOrDefault(emptyList())
        return HlsQuality.mpvArgs(layers, target)
    }

    fun kindOf(link: ExtractorLink): StreamKind = when (link.type) {
        ExtractorLinkType.M3U8 -> StreamKind.HLS
        ExtractorLinkType.DASH -> StreamKind.DASH
        ExtractorLinkType.VIDEO -> StreamKind.FILE
        else -> throw IllegalArgumentException(I18n.current.unsupportedLinkType(link.type.toString()))
    }

    /** Öğenin kaldığı yer (bitmemişse 5 sn geriden); bilgisayarda ve televizyonda aynı. */
    fun resumePosition(context: PlayContext): Double {
        val previous = controller.library.entry(context.provider, context.item.data)
        return previous?.takeIf { !it.finished }?.position?.minus(5)?.coerceAtLeast(0.0) ?: 0.0
    }

    fun titleOf(context: PlayContext): String =
        if (context.item.title == context.showName) context.showName else "${context.showName} — ${context.item.title}"

    /** Bir denemenin durumu: hangi öğe, hangi kaynak zinciri ve zincirde kaçıncı bağlantı. */
    private data class Attempt(
        val generation: Long,
        val context: PlayContext,
        val chain: List<ExtractorLink>,
        val subtitles: List<SubtitleFile>,
        val index: Int = 0,
        /** Kullanıcının kaynaklar penceresinde açıkça seçtiği kalite; sonraki bölüme ve yedek kaynağa taşınır. */
        val quality: QualityTarget? = null,
        /** Başlangıç konumu; null ise kaldığı yerden (oynarken kaynak değiştirilince o anki konum). */
        val startAt: Double? = null,
    )

    /** Her yeni oynatma isteğinde artar; eski oturumların geç gelen sonuçları böylece yok sayılır. */
    @Volatile
    private var generation = 0L
    private var nextJob: Job? = null

    /** Sıradaki bölümün önceden toplanan bağlantıları; hangi oynatma için toplandığı [generation]'da. */
    private class Prefetch(
        val generation: Long,
        val result: Deferred<NextEpisode?>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    /** Sıradaki bölüm ve bulunan bağlantıları ([links] boş olabilir). */
    private class NextEpisode(val item: Playable, val siblings: List<Playable>, val links: CollectedLinks)

    @Volatile
    private var prefetch: Prefetch? = null

    /**
     * Sürmekte olan bölüm aramasının jetonu ([playSibling]); yeni arama ya da yeni oynatma eskisini geçersiz kılar.
     * Arama başarısız olursa oynayan bölümün [generation]'ı değişmediği için onun düğmeleri çalışmaya devam eder.
     */
    @Volatile
    private var searchToken = 0L

    /**
     * Yeni bir oynatma başlıyor: önceki oturumun geç gelen sonuçları ve ön hazırlık geçersizleşir. [cancelSearch]
     * false ise sürmekte olan arama (yeni oturumu başlatanın kendisi) iptal edilmez.
     */
    @Synchronized
    private fun newGeneration(cancelSearch: Boolean = true): Long {
        if (cancelSearch) {
            nextJob?.cancel()
            nextJob = null
            searchToken++
        }
        prefetch?.result?.cancel()
        prefetch = null
        return ++generation
    }

    /** Denemeyi arka planda başlatır; beklenmedik bir hata oynatıcıyı dönen göstergede bırakmasın. */
    private fun launchAttempt(attempt: Attempt, notice: String?) {
        controller.scope.launch(Dispatchers.IO) {
            try {
                startAttempt(attempt, notice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SeyirLog.w(TAG, "Oynatma başlatılamadı", e)
                if (attempt.generation == generation) giveUp(attempt, userMessage(e, I18n.current))
            }
        }
    }

    /**
     * Seçilen bağlantıyı oynatır. [alternatives] kaynaklar penceresinde görünen sıradaki bütün
     * bağlantılardır; seçilen açılamazsa sıradakiler (pencere sırasıyla) kendiliğinden denenir.
     * Ağ ve disk işi arayüz iş parçacığında yapılmaz.
     */
    fun play(
        context: PlayContext,
        link: ExtractorLink,
        subtitles: List<SubtitleFile>,
        alternatives: List<ExtractorLink> = emptyList(),
        quality: QualityTarget? = null,
    ) {
        lastError = null
        val chain = PlaybackFlow.attemptOrder(alternatives, link) { it.url }
        val attempt = Attempt(newGeneration(), context, chain, subtitles, quality = quality)
        // Uygulama içi oynatıcı hemen açılır; kaynak hazırlanırken başlık ve dönen gösterge görünür
        if (controller.settings.embeddedPlayer && player?.canEmbed == true) openEmbedded(context)
        launchAttempt(attempt, notice = null)
    }

    private fun openEmbedded(context: PlayContext) {
        embedded.title = context.showName
        embedded.subtitle = context.item.title.takeIf { it != context.showName }?.let { episodeTitle(context.item) }
        embedded.preparing = true
        embedded.open = true
    }

    /** Oynatıcı ekranını kapatır ve dosyasız kalan mpv'yi bitirir (oynatılan dosyaya dokunmaz). */
    private fun closeEmbedded() {
        if (!embedded.open) return
        embedded.open = false
        embedded.preparing = false
        embedded.controls = null
        embedded.nextTitle = null
        embedded.previousTitle = null
        embedded.notice = null
        embedded.menu = null
        embedded.sources = emptyList()
        embedded.episodes = emptyList()
        embedded.currentEpisode = null
        embedded.thumbnails?.close()
        embedded.thumbnails = null
        player?.closeIdle()
    }

    /**
     * Oynatma bitti ya da başlatılamadı: başka bir şey hazırlanmıyorsa ve daha yeni bir oturum oynamıyorsa oynatıcı
     * ekranı kapanır. [finished] biten denemedir; null ise yalnız oynayan oturum yoksa kapanır.
     */
    private fun closeEmbeddedIfIdle(finished: Attempt?) {
        if (!embedded.open || embedded.preparing) return
        if (finished != null && activeAttempt !== finished) return
        if (finished == null && session?.isRunning == true) return
        closeEmbedded()
    }

    /** Oynatıcıdaki "sonraki bölüm" düğmesi. */
    fun playerNext() {
        activeAttempt?.takeIf { it.generation == generation && embedded.nextTitle != null }?.let { playNext(it) }
    }

    /** Oynatıcıdaki "önceki bölüm" düğmesi. */
    fun playerPrevious() {
        activeAttempt?.takeIf { it.generation == generation && embedded.previousTitle != null }?.let { playPrevious(it) }
    }

    /** Oynatıcının kaynaklar menüsü: zincirdeki [index]'inci kaynağa o anki konumdan geçer. */
    fun playerSwitchSource(index: Int) {
        val current = activeAttempt?.takeIf { it.generation == generation } ?: return
        if (index == current.index || index !in current.chain.indices) return
        val position = embedded.controls?.state?.value?.position
        val gen = newGeneration()
        embedded.preparing = true
        val next = current.copy(generation = gen, index = index, startAt = position)
        val notice = I18n.current.sourceSwitched(sourceName(current.chain[index]))
        SeyirLog.i(TAG, "Oynatıcıdan kaynak değiştirildi: ${sourceName(current.chain[index])}")
        launchAttempt(next, notice)
    }

    /** Oynatıcının bölüm listesinden seçilen bölümü açar (bağlantıları o an aranır). */
    fun playerPlayEpisode(item: Playable) {
        val current = activeAttempt?.takeIf { it.generation == generation } ?: return
        if (item.data == current.context.item.data) return
        playSibling(current, forward = true, target = item)
    }

    /** Zincirdeki [Attempt.index]'inci bağlantıyı mpv'de açar; [notice] pencerede kısa süre gösterilir. */
    private fun startAttempt(attempt: Attempt, notice: String?) {
        if (attempt.generation != generation) return
        val t = I18n.current
        val context = attempt.context
        val link = attempt.chain[attempt.index]
        val mpv = player ?: run {
            lastError = t.mpvMissingError(System.getProperty("os.name").startsWith("Linux"))
            closeEmbedded()
            return
        }
        val url = try {
            localUrl(link, context.provider)
        } catch (e: Throwable) {
            SeyirLog.w(TAG, "Bağlantı hazırlanamadı: ${link.name}", e)
            failAttempt(attempt, userMessage(e, t))
            return
        }
        // Katmanlı (HLS) kaynakta istenen kalite; ağ yalnız "en yüksek" dışında ve en çok birkaç sn bekler
        val qualityOptions = qualityArgs(link, context.provider, attempt.quality)
        if (attempt.generation != generation) return
        if (qualityOptions.isNotEmpty()) SeyirLog.i(TAG, "Kalite seçimi: ${qualityOptions.joinToString(" ")}")
        // Geçersiz adresli bir altyazı oynatmayı engellemesin
        val tracks = attempt.subtitles.mapNotNull { sub ->
            runCatching {
                SubtitleTrack(
                    url = relay.registerSubtitle(sub.url, sub.headers ?: emptyMap(), link.referer.ifBlank { null }),
                    title = sub.lang,
                    lang = sub.langTag,
                )
            }.getOrNull()
        }

        // Yalnız görüntü içeren bağlantılarda ses ayrı gelir; o da aktarıcıdan geçer (başlıklar, engel aşma)
        val audio = link.audioTracks.mapNotNull { track ->
            runCatching { relay.register(track.url, StreamKind.FILE, track.headers ?: link.headers, link.referer.ifBlank { null }) }.getOrNull()
        }
        val previous = controller.library.entry(context.provider, context.item.data)
        val start = attempt.startAt ?: resumePosition(context)
        val title = titleOf(context)
        // Oynatıcı ekranı açıksa mpv onun tuvaline çizer; tuval gelmezse ayrı pencereye dönülür
        val embedId = if (embedded.open && mpv.canEmbed) {
            embedded.surface.awaitWindowId(SURFACE_WAIT_MS).also {
                if (it == null) SeyirLog.w(TAG, "Video tuvali hazır değil; ayrı mpv penceresi açılıyor")
            }
        } else {
            null
        }
        if (attempt.generation != generation) return
        if (embedId != null) {
            embedded.title = context.showName
            embedded.subtitle = context.item.title.takeIf { it != context.showName }?.let { episodeTitle(context.item) }
            embedded.nextTitle = null
            embedded.previousTitle = null
            embedded.showNotice(notice)
            embedded.controls = mpv.controls
            embedded.sources = attempt.chain.mapIndexed { i, l -> SourceOption(i, sourceName(l), sourceDetail(l)) }
            embedded.currentSource = attempt.index
            embedded.currentEpisode = context.item.data
            embedded.provider = context.provider
            if (context.siblings.isNotEmpty()) embedded.episodes = context.siblings
            embedded.thumbnails?.close()
            embedded.thumbnails = ThumbnailSource(mpv.command, url)
        } else {
            closeEmbedded()
        }

        // Eski oynatıcılar kapanır; aynı oynatıcıdaki açık pencere kapanmaz, yeni dosya ona yüklenir
        retiredPlayers.forEach { runCatching { it.close() } }
        retiredPlayers.clear()
        controller.cast.stop()
        // Oynatma başlar başlamaz geçmişe yaz ki "Devam et" listesinde hemen görünsün
        record(context, start, previous?.duration, finished = false)
        nowPlaying = title
        SeyirLog.i(
            TAG,
            "Oynatılıyor: $title ($url, başlangıç ${start.toInt()} sn, ${tracks.size} altyazı, ${audio.size} ayrı ses, " +
                "kaynak ${attempt.index + 1}/${attempt.chain.size})",
        )
        // Bu arada durdurulduysa ya da başka bir oynatma başladıysa mpv açılmaz
        synchronized(this) {
            if (attempt.generation != generation) return
            activeAttempt = attempt
        }
        val started = mpv.play(
            PlayRequest(
                url = url,
                title = title,
                startSeconds = start,
                subtitles = tracks,
                preferredSubtitleLang = controller.settings.subtitleLang,
                audioFiles = audio,
                // Geliştirme/deneme için ek mpv seçenekleri (ör. --mute=yes)
                extraArgs = qualityOptions + subtitleArgs() + System.getenv("SEYIR_MPV_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
                osdMessage = notice,
                messages = UiPlayerMessages(t),
                ui = playerUi(t),
                window = controller.settings.playerWindow,
                embed = embedId,
            ),
            object : PlaybackListener {
                private var lastSaved = 0L

                override fun onWindowState(state: PlayerWindowState, final: Boolean) = windowSaver.offer(state, final)

                override fun onProgress(position: Double, duration: Double?) {
                    // Açılışta toplanan bağlantılar bölüm sonuna kadar eskimiş olabilir (süreli imzalı adresler);
                    // bitime bir dakika kala, eskidiyse sessizce yenilenir
                    if (duration != null && duration > 0 && duration - position <= PREFETCH_BEFORE_END_S) {
                        session?.let { refreshPrefetch(attempt, it) }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSaved < SAVE_INTERVAL_MS) return
                    lastSaved = now
                    record(context, position, duration, finished = false)
                }

                override fun onExit(position: Double?, duration: Double?, reachedEnd: Boolean, error: String?) {
                    SeyirLog.i(TAG, "mpv kapandı: konum=$position süre=$duration sona_erdi=$reachedEnd hata=${error?.take(200)}")
                    if (position != null && position > 0) record(context, position, duration, finished = reachedEnd)
                }

                override fun onFinished(summary: PlaybackSummary) {
                    handleFinished(attempt, summary)
                    if (embedId != null && !summary.replaced) closeEmbeddedIfIdle(attempt)
                }

                override fun onNextRequested() {
                    // Eski oturumun ya da çift basılan düğmenin isteği yok sayılır
                    if (attempt.generation != generation) return
                    SeyirLog.i(TAG, "Oynatıcıdaki sonraki bölüm düğmesine basıldı")
                    playNext(attempt)
                }

                override fun onPreviousRequested() {
                    if (attempt.generation != generation) return
                    SeyirLog.i(TAG, "Oynatıcıdaki önceki bölüm düğmesine basıldı")
                    playPrevious(attempt)
                }
            },
        )
        session = started
        if (attempt.generation != generation) {
            // mpv açılırken durduruldu: görünmez kalmasın
            started.stop()
            return
        }
        // Oturum açılır açılmaz bittiyse karar (yedek kaynak, vazgeçme) dinleyicide verildi; onu ezme
        if (started.isRunning) embedded.preparing = false else closeEmbeddedIfIdle(attempt)
        // Bölüm listesine hemen bakılır: önceki bölüm varsa düğmesi gösterilir, sıradakinin bağlantıları aranır
        startPrefetch(attempt, started)
    }

    /** Ayarlardaki altyazı boyutu (varsayılan boyutta seçenek verilmez). */
    private fun subtitleArgs(): List<String> {
        val scale = controller.settings.subtitleScale
        return if (kotlin.math.abs(scale - 1.0) < 0.01) emptyList() else listOf("--sub-scale=" + String.format(java.util.Locale.ROOT, "%.2f", scale))
    }

    /** Kaynaklar menüsünde kaynağın altındaki satır: kalite ve tür. */
    private fun sourceDetail(link: ExtractorLink): String? = listOfNotNull(
        seyir.app.ui.linkQualityLabel(seyir.app.ui.qualityOf(link)),
        when (link.type) {
            ExtractorLinkType.M3U8 -> "HLS"
            ExtractorLinkType.DASH -> "DASH"
            else -> null
        },
        link.source.takeIf { it.isNotBlank() && it != link.name },
    ).joinToString(" · ").ifBlank { null }

    /** Oynatıcı arayüzü açıksa mpv'ye verilecek arayüz ayarı. */
    private fun playerUi(t: UiStrings): PlayerUi? = uiDir?.let { dir ->
        PlayerUi(dir, if (t === EnglishStrings) "en" else "tr", uiMpvPath)
    }

    /**
     * Oynatma açılınca çağrılır: önceki bölüm varsa düğmesini gösterir, sıradaki bölümün bağlantılarını
     * arka planda toplar. Bağlantı bulunursa "sonraki bölüm" düğmesi o zaman gösterilir; böylece düğme
     * yalnız açılabilecek bir bölüm için görünür ve basınca (ya da bölüm bitince) beklemeden başlar.
     */
    @Synchronized
    private fun startPrefetch(attempt: Attempt, target: PlaybackSession) {
        if (attempt.generation != generation || prefetch?.generation == attempt.generation) return
        prefetch = Prefetch(attempt.generation, prefetchNext(attempt, target, announcePrevious = true))
    }

    /** Açılışta toplanan bağlantılar [PREFETCH_MAX_AGE_MS]'den eskiyse yeniden toplar (bölüm sonuna doğru). */
    @Synchronized
    private fun refreshPrefetch(attempt: Attempt, target: PlaybackSession) {
        val current = prefetch?.takeIf { it.generation == attempt.generation } ?: return startPrefetch(attempt, target)
        if (!current.result.isCompleted || System.currentTimeMillis() - current.createdAt < PREFETCH_MAX_AGE_MS) return
        SeyirLog.i(TAG, "Sıradaki bölümün bağlantıları eskidi, yenileniyor")
        prefetch = Prefetch(attempt.generation, prefetchNext(attempt, target, announcePrevious = false))
    }

    private fun prefetchNext(attempt: Attempt, target: PlaybackSession, announcePrevious: Boolean): Deferred<NextEpisode?> =
        controller.scope.async(Dispatchers.IO) {
            try {
                val siblings = siblingsOf(attempt.context) ?: return@async null
                if (attempt.generation == generation) embedded.episodes = siblings
                if (announcePrevious) {
                    siblings.previousBefore(attempt.context.item)?.let { previous ->
                        if (attempt.generation == generation) {
                            if (uiDir != null) target.setPreviousEpisode(episodeTitle(previous))
                            embedded.previousTitle = episodeTitle(previous)
                        }
                    }
                }
                findNext(attempt.context, siblings)?.also { next ->
                    SeyirLog.i(TAG, "Sıradaki bölüm önceden hazırlandı: ${next.item.title} (${next.links.links.size} bağlantı)")
                    if (next.links.links.isNotEmpty() && attempt.generation == generation) {
                        if (uiDir != null) target.setNextEpisode(episodeTitle(next.item))
                        embedded.nextTitle = episodeTitle(next.item)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Ön hazırlık yalnızca bir kolaylıktır; bölüm bitince yeniden denenir
                SeyirLog.w(TAG, "Sıradaki bölüm önceden hazırlanamadı", e)
                null
            }
        }

    /** Düğmedeki başlık: "S1 B2 — Ad" (ad yoksa yalnız kod). */
    private fun episodeTitle(item: Playable): String {
        val code = episodeCode(item)
        return if (item.title.isBlank() || item.title == code) code else "$code — ${item.title}"
    }

    /** Öğenin bölüm listesi (gerekirse eklentiden yüklenir); sağlayıcı yoksa null. */
    private suspend fun siblingsOf(context: PlayContext): List<Playable>? {
        val api = controller.provider(context.provider) ?: return null
        return context.siblings.ifEmpty { api.load(context.loadUrl)?.playables(UiEpisodeLabels(I18n.current)).orEmpty() }
    }

    /** [pick] ile seçilen bölümü (varsayılan: sıradaki) ve bağlantılarını bulur; bölüm yoksa null. */
    private suspend fun findNext(
        context: PlayContext,
        known: List<Playable>? = null,
        pick: (List<Playable>, Playable) -> Playable? = { list, current -> list.nextAfter(current) },
    ): NextEpisode? {
        val api = controller.provider(context.provider) ?: return null
        val siblings = known ?: siblingsOf(context) ?: return null
        val next = pick(siblings, context.item) ?: return null
        SeyirLog.i(TAG, "Bölüm aranıyor: ${next.title} (${next.data})")
        return NextEpisode(next, siblings, collectLinks(api, next))
    }

    /** Kaynak açılamadı: zincirde sıradakine geç, kalmadıysa hatayı göster. */
    private fun failAttempt(attempt: Attempt, reason: String) {
        val t = I18n.current
        val next = attempt.index + 1
        if (next < attempt.chain.size) {
            startAttempt(attempt.copy(index = next), notice = t.sourceSwitched(sourceName(attempt.chain[next])))
        } else {
            giveUp(attempt, reason)
        }
    }

    private fun giveUp(attempt: Attempt, reason: String) {
        val t = I18n.current
        lastError = if (attempt.chain.size > 1) t.allSourcesFailed(attempt.chain.size, reason) else t.playbackFailed(reason)
        nowPlaying = null
        embedded.preparing = false
        closeIdleWindow()
        closeEmbeddedIfIdle(null)
    }

    /** Başka dosya gelmeyecekse, dosyasız kalan oynatıcı penceresini kapatır (oynatılan dosya varsa dokunmaz). */
    private fun closeIdleWindow() {
        player?.closeIdle()
    }

    private fun sourceName(link: ExtractorLink) = link.name.ifBlank { link.source }

    /** mpv'nin son çıktısından kullanıcıya gösterilecek kısa neden. */
    private fun failureReason(summary: PlaybackSummary): String {
        val t = I18n.current
        val raw = summary.fileError?.takeIf { it.isNotBlank() }
            ?: summary.error?.lines()?.lastOrNull { it.isNotBlank() }
            ?: return t.errorGeneric("?")
        return knownMessage(raw, t) ?: briefly(raw)
    }

    /** Oturum bitti (sıradaki bölüm, yedek kaynak ya da hata kararı burada verilir). */
    private fun handleFinished(attempt: Attempt, summary: PlaybackSummary) {
        // Başka bir oynatma başladıysa ya da kullanıcı durdurduysa eski oturumun sonucu önemsizdir; pencereye
        // başka bir dosya yüklendiyse (replaced) bu bir bitiş ya da hata değildir
        if (summary.replaced || attempt.generation != generation) return
        // Kullanıcı başka bir bölüm istedi ve aranıyor: bu bölümün bitişi o isteği ezmesin (arama sonucu karar verir)
        if (nextJob?.isActive == true) return
        val t = I18n.current
        when (val decision = PlaybackFlow.decide(summary, attempt.index, attempt.chain.size, controller.settings.autoplayNext)) {
            is PlaybackFlow.Decision.TrySource -> {
                SeyirLog.w(TAG, "Kaynak açılamadı (${failureReason(summary)}), sıradaki deneniyor")
                val next = attempt.copy(index = decision.index)
                val notice = t.sourceSwitched(sourceName(next.chain[next.index]))
                if (embedded.open) embedded.preparing = true
                launchAttempt(next, notice)
            }
            PlaybackFlow.Decision.Exhausted -> giveUp(attempt, failureReason(summary))
            PlaybackFlow.Decision.PlayNext -> {
                nowPlaying = null
                playNext(attempt)
            }
            PlaybackFlow.Decision.Done -> {
                nowPlaying = null
                if (summary.position == null && summary.error != null) lastError = t.playbackFailed(failureReason(summary))
                closeIdleWindow()
            }
        }
    }

    /** Bölüm sonuna kadar izlendi: sıradaki bölümün bağlantılarını bulur ve en iyisiyle başlatır. */
    private fun playNext(previous: Attempt) = playSibling(previous, forward = true)

    /** Önceki bölümü açar (bağlantıları o an aranır). */
    private fun playPrevious(current: Attempt) = playSibling(current, forward = false)

    /**
     * Sıradaki ([forward]) ya da önceki bölümü, [target] verilmişse o bölümü açar. Önceden toplanan bağlantılar
     * yalnız sıradaki bölüm içindir; [target] bunlarla aynı bölümse ve toplama bitmişse onlar kullanılır.
     */
    private fun playSibling(previous: Attempt, forward: Boolean, target: Playable? = null) {
        val context = previous.context
        // Sıradaki bölüm için önceden toplanan bağlantılar varsa (ya da toplanıyorsa) onlar kullanılır
        // Arama bitene kadar oynayan bölüm sürer ve kuşağı değişmez; yeni kuşak ancak yeni oturum başlarken alınır
        val (token, ready) = synchronized(this) {
            nextJob?.cancel()
            val usable = prefetch?.takeIf { it.generation == previous.generation }
                ?.takeIf { if (target == null) forward else it.result.isCompleted }
            if (usable != null) prefetch = null
            ++searchToken to usable?.result
        }
        if (embedded.open) embedded.preparing = true
        nextJob = controller.scope.launch(Dispatchers.IO) {
            val t = I18n.current
            fun current() = token == searchToken
            try {
                val prepared = ready?.await()?.takeIf { target == null || it.item.data == target.data }
                // Önceden bağlantı bulunamadıysa bir kez daha aranır (geçici bir hata olabilir)
                val nextEpisode = prepared?.takeIf { it.links.links.isNotEmpty() }
                    ?: when {
                        target != null -> findNext(context) { list, _ -> list.firstOrNull { it.data == target.data } ?: target }
                        forward -> findNext(context)
                        else -> findNext(context) { list, current -> list.previousBefore(current) }
                    }
                if (nextEpisode == null) {
                    // Gidilecek bölüm yok: bölüm bitmişse boşta kalan pencere kapanır, bitmemişse oynatma sürer
                    if (current()) searchFailed(null)
                    return@launch
                }
                if (prepared === nextEpisode) SeyirLog.i(TAG, "Sıradaki bölüm önceden hazırlanan bağlantılarla açılıyor")
                val next = nextEpisode.item
                val siblings = nextEpisode.siblings
                val code = episodeCode(next)
                val found = nextEpisode.links
                if (!current()) return@launch
                if (found.links.isEmpty()) {
                    lastError = found.failure?.let { userMessage(it, t) } ?: if (forward) t.nextEpisodeNoLinks(code) else t.previousEpisodeNoLinks(code)
                    searchFailed(lastError)
                    return@launch
                }
                val nextContext = context.copy(item = next, siblings = siblings)
                val notice = when {
                    target != null -> t.playerEpisodeNotice(code)
                    forward -> t.nextEpisodeNotice(code)
                    else -> t.previousEpisodeNotice(code)
                }
                val gen = synchronized(this@PlaybackService) {
                    if (!current()) return@launch
                    newGeneration(cancelSearch = false)
                }
                startAttempt(Attempt(gen, nextContext, orderedLinks(found.links), found.subtitles, quality = previous.quality), notice = notice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Eklenti Error'ları (NoSuchMethodError vb.) uygulama kapsamını düşürmesin
                SeyirLog.w(TAG, "Sıradaki bölüm açılamadı", e)
                if (current()) {
                    lastError = userMessage(e, t)
                    searchFailed(lastError)
                }
            }
        }
    }

    /**
     * Bölüm araması sonuçsuz bitti: oynayan bölüm (varsa) olduğu gibi sürer, oynatıcıda kısa ileti görünür; bölüm
     * zaten bitmişse boşta kalan pencere ve oynatıcı ekranı kapanır.
     */
    private fun searchFailed(message: String?) {
        embedded.preparing = false
        embedded.showNotice(message)
        closeIdleWindow()
        closeEmbeddedIfIdle(null)
    }

    private class CollectedLinks(val links: List<ExtractorLink>, val subtitles: List<SubtitleFile>, val failure: Throwable?)

    /**
     * Öğenin bağlantılarını toplar. İlk bağlantıdan [NEXT_GRACE_MS] sonra (yavaş bir kaynak
     * beklenmesin) ya da arama bitince döner; hiçbir şey gelmezse [NEXT_MAX_MS] sonra vazgeçer.
     */
    private suspend fun collectLinks(api: MainAPI, item: Playable): CollectedLinks {
        val links = CopyOnWriteArrayList<ExtractorLink>()
        val subs = CopyOnWriteArrayList<SubtitleFile>()
        val failure = AtomicReference<Throwable?>()
        val job = controller.scope.async(Dispatchers.IO) {
            try {
                api.loadLinks(
                    item.data,
                    false,
                    { sub -> synchronized(subs) { if (subs.none { it.url == sub.url }) subs.add(sub) } },
                    { link -> synchronized(links) { if (links.none { it.url == link.url }) links.add(link) } },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Eklenti Error'ları async'ten kapsama sızıp bütün uygulama işlerini iptal etmesin
                failure.set(e)
            }
        }
        try {
            val startedAt = System.currentTimeMillis()
            var firstLinkAt = 0L
            while (job.isActive) {
                delay(NEXT_POLL_MS)
                val now = System.currentTimeMillis()
                if (links.isNotEmpty() && firstLinkAt == 0L) firstLinkAt = now
                if (firstLinkAt != 0L && now - firstLinkAt >= NEXT_GRACE_MS) break
                if (now - startedAt >= NEXT_MAX_MS) break
            }
        } finally {
            job.cancel()
        }
        return CollectedLinks(links.toList(), subs.toList(), failure.get())
    }

    fun stop() {
        // Bekleyen sıradaki bölüm aramasını ve eski oturumun sonuç kararlarını iptal eder
        newGeneration()
        session?.stop()
        player?.closeIdle()
        nowPlaying = null
        closeEmbedded()
    }

    fun record(context: PlayContext, position: Double, duration: Double?, finished: Boolean) {
        runCatching {
            controller.library.record(
                WatchEntry(
                    provider = context.provider,
                    loadUrl = context.loadUrl,
                    showName = context.showName,
                    posterUrl = context.posterUrl,
                    itemTitle = context.item.title,
                    itemData = context.item.data,
                    season = context.item.season,
                    episode = context.item.episode,
                    position = position,
                    duration = duration,
                    finished = finished,
                )
            )
            controller.libraryChanged()
        }.onFailure { SeyirLog.w(TAG, "Geçmiş yazılamadı", it) }
    }

    override fun close() {
        // Önizleme mpv'si kendiliğinden kapanmaz
        runCatching { embedded.thumbnails?.close() }
        runCatching { session?.stop() }
        // Bitmiş ama pencere açık kalan oturumda da mpv kapansın
        runCatching { player?.close() }
        retiredPlayers.forEach { runCatching { it.close() } }
        runCatching { relay.close() }
    }

    /**
     * Oynatıcı penceresinin durumunu ([PlayerWindowState]) ayarlara yazar. Sürükleme/boyutlandırma sırasında
     * durum her saniye değişir; diske en çok [WINDOW_SAVE_INTERVAL_MS]'de bir yazılır, son durum gecikmeli
     * olarak, pencere kapanırken (final) hemen yazılır.
     */
    private inner class WindowStateSaver {
        private var pending: PlayerWindowState? = null
        private var lastWriteAt = 0L
        private var flushScheduled = false

        @Synchronized
        fun offer(state: PlayerWindowState, final: Boolean) {
            if (state == controller.settings.playerWindow) {
                pending = null
                return
            }
            pending = state
            val sinceLast = System.currentTimeMillis() - lastWriteAt
            if (final || sinceLast >= WINDOW_SAVE_INTERVAL_MS) {
                write()
            } else if (!flushScheduled) {
                flushScheduled = true
                controller.scope.launch(Dispatchers.IO) {
                    delay(WINDOW_SAVE_INTERVAL_MS - sinceLast)
                    flush()
                }
            }
        }

        @Synchronized
        private fun flush() {
            flushScheduled = false
            write()
        }

        private fun write() {
            val state = pending ?: return
            pending = null
            lastWriteAt = System.currentTimeMillis()
            runCatching { controller.updateSettings { it.copy(playerWindow = state) } }
                .onFailure { SeyirLog.w(TAG, "Pencere durumu yazılamadı", it) }
        }
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val SAVE_INTERVAL_MS = 10_000L

        /** Pencere durumu ayarlara bu aralıktan sık yazılmaz. */
        const val WINDOW_SAVE_INTERVAL_MS = 5_000L

        /** Sıradaki bölümde ilk bağlantıdan sonra en çok bu kadar beklenir (diğer kaynaklar gelsin diye). */
        const val NEXT_GRACE_MS = 8_000L
        const val NEXT_MAX_MS = 45_000L
        /** Bitime bu kadar saniye kala, açılışta toplanan bağlantılar eskidiyse yenilenir. */
        const val PREFETCH_BEFORE_END_S = 60.0
        /** Bu süreden eski önceden toplanmış bağlantılar bölüm sonunda yenilenir. */
        const val PREFETCH_MAX_AGE_MS = 10 * 60_000L
        const val NEXT_POLL_MS = 250L

        /** Oynatıcı ekranı yeni açıldıysa video tuvalinin ekrana gelmesi için beklenen en uzun süre. */
        const val SURFACE_WAIT_MS = 5_000L
    }
}
