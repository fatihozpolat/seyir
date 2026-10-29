package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seyir.app.data.AppSettings
import seyir.app.data.LibraryStore
import seyir.app.data.SettingsStore
import seyir.app.i18n.I18n
import seyir.app.ui.HomeState
import seyir.core.DomainTracker
import seyir.core.SeyirLog
import seyir.core.net.Network
import seyir.core.playables
import seyir.core.withHardTimeout

/** Ekranda açık olan detay sayfasının yüklenmiş içeriği. */
data class LoadedDetail(val screen: Screen.Detail, val name: String, val posterUrl: String?, val items: List<seyir.core.Playable>)

/**
 * Uygulamanın bileşim kökü: depoları ve servisleri kurar, ekranlar arası paylaşılan durumu
 * (ayarlar, gezinme, sağlayıcı kayıt defteri, kapsam) tutar. İş mantığı servislerdedir:
 * [health], [probes], [pluginActions], [search], [playback], [downloads], [cast], [updates].
 */
class AppController(parentScope: CoroutineScope) {
    /**
     * Uygulama genelindeki işlerin kapsamı. [parentScope]'un bağlamını (Main dispatcher dahil) olduğu gibi
     * kullanır ama kendi `SupervisorJob`'u vardır: bir çocuk işin (ör. eklentiden sızan bir hata)
     * düşmesi kardeş işleri ve sonraki başlatmaları iptal etmez. Kapanışta [shutdown] iptal eder.
     */
    val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    val settingsStore = SettingsStore()
    val library = LibraryStore()
    val domains = DomainTracker()
    val plugins = PluginManager()
    val navigator = Navigator()
    val home = HomeState()
    val playback by lazy { PlaybackService(this) }
    val downloads by lazy { DownloadService(this) }
    val cast by lazy { CastService(this) }
    val updates by lazy { UpdateService(this) }

    /** İsteğe bağlı TMDB üst verisi; anahtar ayarlarda boşsa hiçbir şey yapmaz. */
    val tmdb by lazy {
        seyir.app.tmdb.TmdbClient(
            apiKey = { settings.tmdbApiKey },
            language = { seyir.app.tmdb.tmdbLanguage(I18n.current.locale) },
        )
    }

    /** Arama ekranı her açılışta ve Ctrl+F / "/" ile bu sayaç artınca arama kutusuna odaklanır. */
    var searchFocusTick by mutableIntStateOf(0)
        private set

    /** Arama ekranını açar (açıksa yalnız kutuya odaklanır). */
    fun openSearch() {
        if (navigator.current != Screen.Search) navigator.root(Screen.Search)
        searchFocusTick++
    }

    var settings by mutableStateOf(settingsStore.current)
        private set

    init {
        // Servislerin ilk iletileri de kayıtlı dilde olsun
        I18n.current = I18n.resolve(settingsStore.current.language)
        SeyirLog.setLevelByName(settingsStore.current.logLevel)
    }

    /** Sağlayıcı listesinin tek sahibi; liste, hazır olma ve durum iletisi yalnız burada yazılır. */
    val registry = ProviderRegistry(initialStatus = I18n.resolve(settings.language).loadingPlugins)

    // Kayıt defterine kısa yoldan erişim; değerler kayıt defterinin anlık görüntüsüdür
    val providers: List<MainAPI> get() = registry.providers
    val ready: Boolean get() = registry.ready
    val status: String get() = registry.status

    /** Bir sağlayıcının alan adı değişince artar; adres gösteren ekranlar bunu okuyarak yenilenir. */
    val domainsVersion: Int get() = registry.domainsVersion

    val enabledProviders: List<MainAPI>
        get() = providers.filter { it.name !in settings.disabledProviders }

    fun provider(name: String): MainAPI? = providers.firstOrNull { it.name == name }

    /** Sağlayıcıların sağlık kaydı. */
    val health = HealthService()

    /** Sına: kurulu eklentiyi uçtan uca deneme. */
    val probes = ProbeService(scope, domains, plugins, health) { providers }

    /** Eklenti kurulumu, depolar ve sağlayıcı listesinin yenilenmesi. */
    val pluginActions = PluginActions(
        scope = scope,
        plugins = plugins,
        registry = registry,
        domains = domains,
        probes = probes,
        health = health,
        settings = { settings },
        updateSettings = ::updateSettings,
        providers = { providers },
        enabledProviders = { enabledProviders },
    )

    /** Bütün etkin sağlayıcılarda arama; durumu [SearchService.state]'tedir. */
    val search = SearchService(scope, { providers }, { settings }, health)

    /** Eklentiler ekranında açık sekme (0: kurulu, 1: depolar); ekrandan çıkıp dönünce korunur. */
    var pluginsTab by mutableIntStateOf(0)

    /** Kaynak listesi penceresi açık olan öğe. */
    var linksRequest by mutableStateOf<PlayContext?>(null)

    /** Karttaki oynat düğmesiyle ayrıntısı yüklenen yapımın adresi (kartta bekleme göstergesi için). */
    var quickPlaying by mutableStateOf<String?>(null)
        private set

    /**
     * Ayrıntı sayfasına gitmeden oynatır: yapımı yükler, yarım kalan bölümü (bitmişse sıradakini, hiç
     * izlenmemişse ilkini) seçer ve kaynaklar penceresini açar. Yükleme başarısızsa alt şeritte hata çıkar.
     */
    fun quickPlay(target: Screen.Detail) {
        if (quickPlaying != null) return
        val api = provider(target.provider) ?: run {
            playback.lastError = I18n.current.providerNotInstalled
            return
        }
        quickPlaying = target.url
        scope.launch {
            val t = I18n.current
            try {
                val response = withContext(Dispatchers.IO) {
                    withHardTimeout(scope, QUICK_PLAY_TIMEOUT_MS) { api.load(target.url) }
                } ?: throw IllegalStateException(t.providerEmptyResponse)
                val items = response.playables(UiEpisodeLabels(t))
                val item = seyir.app.ui.resumeTarget(items, library.entriesFor(target.provider, target.url))
                    ?: throw IllegalStateException(t.noPlayableItems)
                withContext(Dispatchers.Main) {
                    linksRequest = PlayContext(
                        provider = target.provider,
                        loadUrl = target.url,
                        showName = response.name,
                        posterUrl = response.posterUrl ?: target.posterUrl,
                        item = item,
                        siblings = items,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Eklenti Error'ları da yakalanır; kapsam düşmesin
                SeyirLog.w(TAG, "Karttan oynatma açılamadı: ${target.name}", e)
                withContext(Dispatchers.Main) { playback.lastError = t.detailsFailed(userMessage(e, t)) }
            } finally {
                withContext(Dispatchers.Main) { quickPlaying = null }
            }
        }
    }
    var openDetail by mutableStateOf<LoadedDetail?>(null)

    /** Geçmiş/listem değiştiğinde artar; ekranlar bunu okuyarak yenilenir. */
    var libraryVersion by mutableIntStateOf(0)
        private set

    fun start() {
        scope.launch(Dispatchers.IO) {
            Network.install(settings.networkSettings())
            playback.start()
            plugins.githubToken = { settings.githubToken }
            plugins.loadAll()
            val apis = pluginActions.publishProviders(markReady = true)
            registry.announce(I18n.current.providersReady(apis.size))
            pluginActions.refreshDomains(apis)
            DevScript.runIfRequested(this@AppController)
            if (settings.autoUpdatePlugins || settings.autoInstallNewPlugins) pluginActions.autoUpdatePlugins()
            if (settings.checkAppUpdates) updates.check()
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val old = settings
        settings = settingsStore.update(transform)
        // Dil değiştiyse servislerin ürettiği metinler de hemen yeni dile geçsin
        I18n.current = I18n.resolve(settings.language)
        if (old.networkSettings() != settings.networkSettings()) {
            Network.install(settings.networkSettings())
            SeyirLog.i(TAG, "Ağ ayarları yeniden uygulandı")
        }
        SeyirLog.setLevelByName(settings.logLevel)
        // Oynatıcı/tarayıcı aramaları (Linux'ta flatpak sorgusu saniyeler sürebilir) yalnız ilgili
        // ayar değişince ve arayüz iş parçacığının dışında yapılır
        if (old.playerKey() != settings.playerKey()) {
            scope.launch(Dispatchers.IO) { playback.onSettingsChanged() }
        }
    }

    /** Oynatıcı ve tarayıcı kurulumunu etkileyen ayarlar; dil durum metinlerini de değiştirir. */
    private fun AppSettings.playerKey() = listOf(language, mpvPath, browserPath, solveCloudflare)

    /** Sayaç Compose durumudur; mpv ve indirme iş parçacıklarından çağrılsa da artış ana iş parçacığında yapılır. */
    fun libraryChanged() {
        scope.launch(Dispatchers.Main) { libraryVersion++ }
    }

    /** Arayüzde yakalanmamış bir hata sonrası alt şeritte gösterilen uyarı. */
    var uiError by mutableStateOf<String?>(null)

    private val uiCrashTimes = ArrayDeque<Long>()

    /**
     * Arayüzde (genellikle eklentiden gelen beklenmedik veri yüzünden) hata çıktığında çağrılır.
     * Hatalı ekrandan çıkılır; kısa sürede üst üste tekrar ederse ana sayfaya dönülür ki
     * pencere aynı hatayla döngüye girmesin.
     */
    fun recoverFromUiError(error: Throwable) {
        SeyirLog.e(TAG, "Arayüz hatası, pencere yeniden kuruluyor", error)
        val now = System.currentTimeMillis()
        uiCrashTimes.addLast(now)
        while (uiCrashTimes.isNotEmpty() && now - uiCrashTimes.first() > 30_000) uiCrashTimes.removeFirst()
        linksRequest = null
        if (uiCrashTimes.size >= 3) navigator.root(Screen.Home) else navigator.back()
        uiError = I18n.current.uiCrashed
    }

    fun shutdown() {
        runCatching { cast.stop() }
        runCatching { playback.close() }
        // Birleştirilmiş yazımlar (geçmiş, sağlık durumu) kapanmadan önce diske işlenir
        runCatching { seyir.app.data.JsonStore.flushAll() }
        scope.cancel()
    }

    private companion object {
        const val TAG = "AppController"

        /** Karttan oynatmada yapımın ayrıntısını yüklemeye tanınan en uzun süre. */
        const val QUICK_PLAY_TIMEOUT_MS = 30_000L
    }
}
