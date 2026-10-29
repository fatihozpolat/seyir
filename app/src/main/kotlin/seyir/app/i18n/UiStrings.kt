package seyir.app.i18n

import java.util.Locale

/**
 * Arayüzdeki bütün kullanıcı metinleri. Her dil bu sınıfın bir nesnesidir ([TurkishStrings],
 * [EnglishStrings]); yeni bir metin eklerken üç dosyaya da aynı adla eklenir. Sayı alan metinler
 * işlev olarak tanımlanır, böylece çoğul eki dile göre seçilir.
 */
abstract class UiStrings {
    /** Sayı ve tarih biçimleri için dilin yerel ayarı. */
    abstract val locale: Locale

    // ---- Gezinme ve alt şerit ----
    abstract val navHome: String
    abstract val navSearch: String
    abstract val navPlugins: String
    abstract val navSettings: String
    abstract val navDownloads: String
    abstract val navLibrary: String
    abstract val continueWatching: String
    abstract val navContinue: String
    abstract val back: String
    abstract val nowPlaying: String
    abstract val stop: String
    abstract fun appReleased(version: String): String
    abstract val later: String
    abstract val update: String
    abstract val releasePage: String
    abstract val close: String
    abstract fun deviceConnecting(device: String): String
    abstract val seekBack10: String
    abstract val seekForward30: String
    abstract val resume: String
    abstract val pause: String

    // ---- Uygulama durumu ve ileti metinleri (AppController) ----
    abstract val loadingPlugins: String
    abstract fun providersReady(n: Int): String
    abstract fun pluginsUpdated(names: String): String
    abstract fun pluginsAdded(n: Int): String
    abstract val autoInstallNewPluginsTitle: String
    abstract val autoInstallNewPluginsHint: String
    abstract fun domainsUpdated(names: String): String
    abstract fun pluginsInstalled(n: Int): String
    abstract fun installFailed(reason: String?): String
    abstract val uiCrashed: String

    // ---- Oynatma, televizyon, indirme, güncelleme servisleri ----
    abstract fun playerInUse(description: String): String
    abstract val mpvNotFound: String
    abstract val browserNotFound: String
    abstract fun unsupportedLinkType(type: String): String
    abstract fun mpvMissingError(linux: Boolean): String
    abstract fun playbackFailed(reason: String): String
    abstract fun tvDeviceName(host: String): String
    abstract fun castFailed(reason: String): String
    abstract val downloadStarting: String
    abstract val downloadCancelled: String
    abstract fun downloadDone(sizeMb: Double): String
    abstract val ffmpegMissing: String
    abstract fun ffmpegFailed(tail: String): String
    abstract val noReleaseYet: String
    abstract fun releaseInfoFailed(code: Int): String
    abstract fun releaseAnnounced(version: String): String
    abstract fun upToDate(version: String): String
    abstract fun updateCheckFailed(reason: String?): String
    abstract fun updateDownloadFailed(reason: String?): String
    abstract fun updateAssetMissing(name: String): String
    abstract val updateSignatureInvalid: String
    abstract fun updateNotListed(name: String): String
    abstract fun updateHashMismatch(name: String): String

    // ---- Eklenti yöneticisi hataları ----
    abstract fun repoUnreadable(detail: String): String
    abstract val repoNotFoundNoToken: String
    abstract val repoNotFoundWithToken: String
    abstract val notARepository: String
    abstract fun cs3ConvertFailed(reason: String?): String
    abstract fun invalidPlugin(reason: String?): String
    abstract val manifestNoName: String
    abstract val manifestNoClass: String
    abstract fun needsAndroidResources(name: String): String
    abstract fun insecureUrl(url: String): String
    abstract val tooManyRedirects: String
    abstract fun integrityMismatch(name: String): String
    abstract fun pluginDownloadFailed(code: Int): String

    // ---- Ortak bileşenler ----
    abstract val scrollBack: String
    abstract val scrollForward: String
    abstract val clear: String
    abstract val statusWorking: String
    abstract val statusError: String
    abstract val retry: String
    abstract val typeMovie: String
    abstract val typeSeries: String
    abstract val typeAnime: String
    abstract val typeCartoon: String
    abstract val typeDocumentary: String
    abstract val typeAsianDrama: String
    abstract val typeLive: String
    abstract val typeTorrent: String
    abstract fun countTitles(n: Int): String
    abstract fun minutesShort(n: Int): String
    abstract fun seasonShort(n: Int): String
    abstract fun episodeShort(n: Int): String
    abstract fun episodeNumbered(n: Int): String

    // ---- Ana sayfa ----
    abstract val noHomeProviders: String
    abstract val noHomeProvidersHint: String
    abstract val homeEmpty: String
    abstract fun providerLoading(name: String): String
    abstract fun homeLoadFailed(detail: String): String
    abstract val homeReturnedNothing: String
    abstract val homeFailureHint: String
    abstract val refresh: String
    abstract val details: String
    abstract val watch: String
    abstract val pluginLabel: String
    abstract fun historyFinished(what: String): String
    abstract val noPluginsYet: String
    abstract val welcomeHint: String
    abstract val welcomeRepositoryPlaceholder: String
    abstract val addAndInstall: String
    abstract val openPluginsScreen: String
    abstract fun resumeAt(time: String): String

    // ---- Devam et ve Listem ekranları ----
    abstract val nothingWatchedYet: String
    abstract val nothingWatchedHint: String
    abstract val removeFromHistory: String
    abstract val listEmpty: String
    abstract val listEmptyHint: String
    abstract val searchProviders: String
    abstract val noMatchingProvider: String

    // ---- Arama ekranı ----
    abstract val searchPlaceholder: String
    abstract fun searchScopeAll(n: Int): String
    abstract fun searchScopeSome(n: Int): String
    abstract fun searchWaiting(pending: Int, answered: Int): String
    abstract fun searchSummary(query: String, total: Int, providers: Int): String
    abstract val hideErrors: String
    abstract fun providersFailed(n: Int): String
    abstract val searchAllAtOnce: String
    abstract val searchHint: String
    abstract val searching: String
    abstract val noResults: String
    abstract val noResultsHint: String
    abstract fun resultsCount(n: Int): String
    abstract val allProviders: String
    abstract fun providersCount(n: Int): String
    abstract val groupByTitle: String
    abstract val chooseProvider: String
    abstract fun requestTimedOut(seconds: Int): String
    abstract val all: String

    // ---- İndirilenler ekranı ----
    abstract fun downloadsRunning(n: Int): String
    abstract val openFolder: String
    abstract val noDownloads: String
    abstract fun noDownloadsHint(directory: String): String
    abstract val cancel: String
    abstract val open: String

    // ---- Detay ekranı ----
    abstract fun providerNotLoaded(name: String): String
    abstract val providerEmptyResponse: String
    abstract fun opening(name: String): String
    abstract fun detailsFailed(message: String): String
    abstract val episodes: String
    abstract val similarTitles: String
    abstract fun episodesCount(n: Int): String
    abstract val castLabel: String
    abstract val inMyList: String
    abstract val addToMyList: String
    abstract val noPlayableItems: String
    abstract fun seasonLabel(n: Int): String
    abstract val watched: String
    abstract val markWatched: String
    abstract val markUnwatched: String
    abstract val play: String

    // ---- Kaynaklar penceresi ----
    abstract val providerNotInstalled: String
    abstract val noLinksFound: String
    abstract fun sourcesSearching(found: Int): String
    abstract fun sourcesSummary(sources: Int, subtitles: Int): String
    abstract val extractingLinks: String
    abstract val playOnTv: String
    abstract val noTvFound: String
    abstract val scanAgain: String
    abstract val download: String
    abstract val linkTypeFile: String

    // ---- Oynatma akışı: sıradaki bölüm, yedek kaynak, oynatıcı ve hata iletileri ----
    abstract fun nextEpisodeNotice(code: String): String
    abstract fun nextEpisodeNoLinks(code: String): String
    abstract fun previousEpisodeNotice(code: String): String
    abstract fun previousEpisodeNoLinks(code: String): String
    abstract fun sourceSwitched(name: String): String
    abstract fun allSourcesFailed(tried: Int, reason: String): String
    abstract fun playerStartFailed(reason: String?): String
    abstract fun playerExitedWithCode(code: Int, tail: String): String
    abstract fun playerSessionFailed(reason: String?): String
    abstract val subtitleFallbackTitle: String
    abstract val downloadQueued: String
    abstract val errorTimeout: String
    abstract val errorNoNetwork: String
    abstract val errorSsl: String
    abstract val errorForbidden: String
    abstract val errorNotFound: String
    abstract fun errorServer(code: Int): String
    abstract fun errorHttp(code: Int): String
    abstract val errorChallenge: String
    abstract val errorCancelled: String
    abstract fun errorGeneric(message: String): String

    // ---- Eklentiler ekranı ----
    abstract val chooseFileTitle: String
    abstract fun pluginInstalled(name: String?, version: Int): String
    abstract fun pluginsEnabledCount(enabled: Int, total: Int): String
    abstract val installFromFile: String
    abstract val addRepoToUpdate: String
    abstract val pluginsUpToDate: String
    abstract fun updatedList(names: String): String
    abstract val installed: String
    abstract val tabRepositories: String
    abstract val filterPlugins: String
    abstract val checkDomains: String
    abstract val domainsChecking: String
    abstract val noMatchingPlugin: String
    abstract val noMatchingPluginHint: String
    abstract fun pluginRemoved(name: String?): String
    abstract fun pluginRemoveFailed(reason: String?): String
    abstract val installedByYou: String
    abstract val pluginDelisted: String
    abstract fun lastErrorLabel(error: String): String
    abstract val removePlugin: String
    abstract fun pluginInstallFailed(name: String, reason: String?): String
    abstract val repoPlaceholder: String
    abstract val add: String
    abstract val pluginsRunCodeWarning: String
    abstract val noRepos: String
    abstract val noReposHint: String
    abstract fun pluginsCount(n: Int): String
    abstract val reloadRepo: String
    abstract val removeRepo: String
    abstract fun updateToVersion(from: Int, to: Int): String
    abstract val install: String

    // ---- Ayarlar ekranı ----
    abstract val saved: String
    abstract val save: String
    abstract val sectionGeneral: String
    abstract val languageTitle: String
    abstract val languageHint: String
    abstract val languageSystem: String
    abstract val languageTurkish: String
    abstract val languageEnglish: String
    abstract val sectionNetwork: String
    abstract val fragmentTitle: String
    abstract val fragmentHint: String
    abstract val dnsTitle: String
    abstract val dnsHint: String
    abstract val systemDns: String
    abstract val dohAdguard: String
    abstract val proxyTitle: String
    abstract val proxyHint: String
    abstract val proxyPlaceholder: String
    abstract val sectionPlayer: String
    abstract val playerUiTitle: String
    abstract val playerUiHint: String
    abstract val embeddedPlayerTitle: String
    abstract val embeddedPlayerHint: String

    // ---- Uygulama içi oynatıcı ----
    abstract val playerPreparing: String
    abstract val playerForward10: String
    abstract val playerMute: String
    abstract val playerUnmute: String
    abstract val playerNextEpisode: String
    abstract val playerPreviousEpisode: String
    abstract val playerSubtitles: String
    abstract val playerSubtitlesOff: String
    abstract val playerAudio: String
    abstract val playerQuality: String
    abstract val playerSpeed: String
    abstract val playerSpeedNormal: String
    abstract val playerSettings: String
    abstract val playerFullscreen: String
    abstract val playerExitFullscreen: String
    abstract val playerUpNext: String
    abstract fun playerTrack(n: Int): String
    abstract val playerSources: String
    abstract val playerEpisodes: String
    abstract fun playerEpisodeNotice(code: String): String
    abstract val playerSubtitleSize: String
    abstract val subtitleSizeTitle: String
    abstract val subtitleSizeHint: String
    abstract val subtitleSizeSmall: String
    abstract val subtitleSizeNormal: String
    abstract val subtitleSizeLarge: String
    abstract val subtitleSizeHuge: String
    abstract val mpvPathTitle: String
    abstract val mpvPathHint: String
    abstract val automatic: String
    abstract val subtitleLangTitle: String
    abstract val subtitleLangHint: String
    abstract val autoPlayTitle: String
    abstract val autoPlayHint: String
    abstract val autoplayNextTitle: String
    abstract val autoplayNextHint: String
    abstract val defaultQualityTitle: String
    abstract val defaultQualityHint: String
    abstract val qualityHighest: String
    abstract val qualityDataSaver: String
    abstract fun qualityChipHint(label: String): String
    abstract val tvAddressTitle: String
    abstract val tvAddressHint: String
    abstract val tvAddressPlaceholder: String
    abstract val sectionCloudflare: String
    abstract val solveChallengesTitle: String
    abstract val solveChallengesHint: String
    abstract val browserPathTitle: String
    abstract val browserPathHint: String
    abstract val autoUpdatePluginsTitle: String
    abstract val autoUpdatePluginsHint: String
    abstract val githubTokenTitle: String
    abstract val githubTokenHint: String
    abstract val githubTokenPlaceholder: String
    abstract val sectionMetadata: String
    abstract val tmdbKeyTitle: String
    abstract val tmdbKeyHint: String
    abstract val tmdbKeyPlaceholder: String
    abstract fun heroSlide(n: Int): String
    abstract val sectionApp: String
    abstract fun releasesHint(repository: String): String
    abstract val checkForUpdates: String
    abstract val checkOnStartupTitle: String
    abstract val checkOnStartupHint: String
    abstract val logLevelTitle: String
    abstract val logLevelHint: String
    abstract val logLevelError: String
    abstract val logLevelWarn: String
    abstract val logLevelInfo: String
    abstract val logLevelDebug: String
    abstract val logFolderTitle: String
    abstract val logFolderHint: String
    abstract val openLogFolder: String
    abstract val sectionData: String
    abstract val dataFolder: String

    // ---- Eklenti seçim penceresi (depo eklenince) ----
    abstract fun pickerSubtitle(total: Int, installed: Int): String
    abstract val selectAll: String
    abstract val selectNone: String
    abstract val updatable: String
    abstract fun installSelected(count: Int): String
    abstract fun installingProgress(done: Int, total: Int, name: String): String
    abstract val pickerEmptySelection: String
    abstract val selectAndInstall: String
    abstract val loadingRepository: String

    // ---- Eklenti uyumluluk denetimi (kurmadan önce, kod çalıştırmadan) ----
    abstract fun pluginIncompatible(classes: String): String
    abstract val incompatibleBadge: String
    abstract fun compatChecking(done: Int, total: Int): String
    abstract fun missingClassesLine(classes: String): String
    abstract val needsResourcesLine: String

    // ---- Eklenti sınaması ("Sına": kurulu eklentiyi uçtan uca dener) ----
    abstract val probeAction: String
    abstract val probeAll: String
    abstract fun probeRunning(done: Int, total: Int): String
    abstract val probeStatusGreen: String
    abstract val probeStatusPartial: String
    abstract val probeStatusRed: String
    abstract val probeStatusChallenge: String
    abstract val probeStepMainPage: String
    abstract val probeStepSearch: String
    abstract val probeStepLoad: String
    abstract val probeStepLinks: String
    abstract val probeStepLinkCheck: String
    abstract val probeStepOk: String
    abstract val probeStepFailed: String
    abstract val probeStepSkipped: String
    abstract val probeFailTimeout: String
    abstract val probeFailNetwork: String
    abstract val probeFailEmpty: String
    abstract val probeFailNoPlayable: String
    abstract fun probeFailHttp(code: String): String
    abstract val probeFailHtml: String
    abstract val probeFailNotMedia: String
    abstract val probeFailBadPlaylist: String
    abstract val probeFailUnsupported: String
    abstract fun probeFailIncompatible(detail: String): String
    abstract fun probeFailError(detail: String): String
    abstract val probeFailCloudflare: String
    abstract val probeSkippedPrevious: String
    abstract val probeSkippedUncheckable: String
    abstract fun probeCounts(working: Int, partial: Int, problem: Int): String
    abstract fun pluginsProbed(n: Int): String
    abstract fun probingInstalled(done: Int, total: Int): String
    abstract val removeFailing: String
    abstract val removeFailingTitle: String
    abstract fun removeFailingBody(names: String): String
    abstract val remove: String
    abstract fun pluginsRemovedCount(n: Int): String
}
