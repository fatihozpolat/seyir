package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import seyir.app.data.AppSettings
import seyir.app.i18n.I18n
import seyir.core.DomainTracker
import seyir.core.ProbeStatus
import seyir.core.SeyirLog

/**
 * Eklenti kurulumu, depolar ve sağlayıcı listesinin yenilenmesi. Sağlayıcı listesinin kendisi
 * [ProviderRegistry]'de durur; burası onu eklenti değişikliklerinden sonra günceller.
 */
class PluginActions(
    private val scope: CoroutineScope,
    private val plugins: PluginManager,
    private val registry: ProviderRegistry,
    private val domains: DomainTracker,
    private val probes: ProbeService,
    private val health: HealthService,
    private val settings: () -> AppSettings,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val providers: () -> List<MainAPI>,
    private val enabledProviders: () -> List<MainAPI>,
) {
    /** Eklenti seçim penceresinde açık olan depo (ilk açılış ekranı ve Depolar sekmesi ortak kullanır). */
    var pickerRepository by mutableStateOf<PluginRepository?>(null)

    /** Kaynak deposundan çıkarılmış kurulu eklentiler (son depo okumasına göre; Eklentiler ekranında işaretlenir). */
    var delisted by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Açılışta bir kez, arka planda: kurulu eklentilerin yeni sürümlerini ve (ayar açıksa) depolara sonradan eklenen
     * eklentileri kurar. Kullanıcının kaldırdığı ya da seçim penceresinde bilerek seçmediği eklentiler kurulmaz.
     */
    fun autoUpdatePlugins() {
        val repos = settings().repositories
        if (repos.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val fetched = runCatching { plugins.fetchRepositories(repos) }
                .onFailure { SeyirLog.w(TAG, "Depolar okunamadı", it) }
                .getOrDefault(emptyMap())
            if (fetched.isNotEmpty()) delisted = plugins.delistedFrom(fetched)
            val updated = if (settings().autoUpdatePlugins) {
                runCatching { plugins.updateFrom(fetched) }
                    .onFailure { SeyirLog.w(TAG, "Eklentiler güncellenemedi", it) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            // Depoya sonradan eklenen eklentiler (kullanıcının kaldırdıkları hariç)
            val added = if (settings().autoInstallNewPlugins) {
                runCatching { plugins.installNewFrom(fetched) }
                    .onFailure { SeyirLog.w(TAG, "Yeni eklentiler kurulamadı", it) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            if (updated.isEmpty() && added.isEmpty()) return@launch
            reloadProviders()
            val t = I18n.current
            registry.announce(
                listOfNotNull(
                    added.takeIf { it.isNotEmpty() }?.let { t.pluginsAdded(it.size) },
                    updated.takeIf { it.isNotEmpty() }?.let { t.pluginsUpdated(it.joinToString()) },
                ).joinToString(" · "),
            )
            if (updated.isNotEmpty()) SeyirLog.i(TAG, "Eklentiler güncellendi: ${updated.joinToString()}")
            if (added.isNotEmpty()) SeyirLog.i(TAG, "Depolara eklenen eklentiler kuruldu: ${added.joinToString()}")
        }
    }

    /** "Güncelle" düğmesi: depoları okur, depodan çıkarılanları işaretler, yeni sürümleri kurar. */
    suspend fun updateNow(repositories: List<String>): List<String> {
        val fetched = plugins.fetchRepositories(repositories)
        if (fetched.isNotEmpty()) delisted = plugins.delistedFrom(fetched)
        return plugins.updateFrom(fetched).also { reloadProviders() }
    }

    /** Depoyu okur (eklemez); sonuç seçim penceresine verilir. */
    fun readRepository(url: String, onResult: (Result<PluginRepository>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { plugins.fetchRepository(url) }
            launch(Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * Seçim penceresinde onaylanan eklentileri kurar ve depoyu (yoksa) ayarlara ekler.
     * [onProgress] kurulum sırasında ilerlemeyi, [onDone] sonuç iletisini alır.
     */
    fun installFromRepository(
        repository: PluginRepository,
        entries: List<RepositoryPlugin>,
        onProgress: (String) -> Unit,
        onDone: (String) -> Unit,
    ) {
        updateSettings { it.copy(repositories = (it.repositories + repository.url).distinct()) }
        // Seçim penceresinde görülen eklentiler (seçilmeyenler dahil) açılışta kendiliğinden kurulmaz
        plugins.offers.markSeen(repository.url, repository.plugins.map { it.internalName })
        scope.launch(Dispatchers.IO) {
            val report = plugins.installSelected(entries, repository.url) { done, total, name ->
                launch(Dispatchers.Main) { onProgress(I18n.current.installingProgress(done, total, name)) }
            }
            reloadProviders()
            val installed = report.installed
            val failedText = if (report.failures.isNotEmpty()) " · " + I18n.current.installFailed(report.failures.entries.joinToString("; ") { "${it.key}: ${it.value}" }) else ""
            val installedText = I18n.current.pluginsInstalled(installed.size)
            launch(Dispatchers.Main) { onDone(installedText + failedText) }

            // Kurulanlar arka planda sınanır; bitince ileti "5 eklenti kuruldu · 4 çalışıyor, 1 sorunlu" olur
            val installedPlugins = installed.mapNotNull { plugins.pluginNamed(it) }
            val apis = installedPlugins.flatMap { it.apis }
            if (apis.isEmpty()) return@launch
            val t = I18n.current
            val results = runCatching { probes.probeApis(apis) { done, total -> launch { registry.announce(t.probingInstalled(done, total)) } } }
                .onFailure { SeyirLog.w(TAG, "Yeni eklentiler sınanamadı", it) }
                .getOrNull() ?: return@launch
            registry.announce(I18n.current.providersReady(providers().size))
            // Eklenti çalışıyor sayılması için hiçbir sağlayıcısı kırmızı olmamalı; yeşil/sarı ayrımı en kötü sağlayıcıya göre
            val byApi = results.associateBy { it.provider }
            val statuses = installedPlugins.map { plugin ->
                val own = plugin.apis.mapNotNull { byApi[it.name]?.status }
                when {
                    ProbeStatus.RED in own -> ProbeStatus.RED
                    ProbeStatus.YELLOW in own -> ProbeStatus.YELLOW
                    else -> ProbeStatus.GREEN
                }
            }
            val counts = t.probeCounts(
                statuses.count { it == ProbeStatus.GREEN } + (installed.size - installedPlugins.size).coerceAtLeast(0),
                statuses.count { it == ProbeStatus.YELLOW },
                statuses.count { it == ProbeStatus.RED },
            )
            launch(Dispatchers.Main) { onDone(installedText + " · " + counts + failedText) }
        }
    }

    /** Depoyu ekler ve içindeki bütün eklentileri kurar (geliştirici senaryosu). */
    fun addRepositoryAndInstall(url: String, onDone: (String) -> Unit) {
        updateSettings { it.copy(repositories = (it.repositories + url).distinct()) }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { plugins.installAll(plugins.fetchRepository(url)) }
            reloadProviders()
            onDone(result.fold({ I18n.current.pluginsInstalled(it.size) }, { I18n.current.installFailed(it.message) }))
        }
    }

    /** [probes]'ta son sınamada kırmızı çıkan eklentileri kaldırır (yalnız kullanıcı onayından sonra çağrılır); [onDone] kaldırılan sayısını bildirir. */
    fun removeFailingPlugins(onDone: (String) -> Unit) {
        val targets = probes.failingPlugins()
        scope.launch(Dispatchers.IO) {
            val removed = targets.count { plugin ->
                val names = plugin.apis.map { it.name }
                runCatching { plugins.uninstall(plugin) }
                    .onFailure { SeyirLog.w(TAG, "${plugin.manifest.name} kaldırılamadı", it) }
                    .isSuccess.also { ok -> if (ok) names.forEach { probes.results.remove(it); health.clear(it) } }
            }
            reloadProviders()
            withContext(Dispatchers.Main) { onDone(I18n.current.pluginsRemovedCount(removed)) }
        }
    }

    /** Eklenti güncellemesinden sonra sağlayıcı listesini yeniler (yüklü eklentilerin güncel durumundan). */
    suspend fun reloadProviders() {
        publishProviders()
    }

    /** Yüklü eklentilerden sağlayıcı listesini kayıt defterine yayımlar; [markReady] ilk yüklemede hazır işareti koyar. */
    suspend fun publishProviders(markReady: Boolean = false): List<MainAPI> =
        registry.publish({ plugins.providerCandidates() }, markReady, onNew = domains::applySaved)

    /** Alan adı taşınmalarını arka planda denetler (aynı anda en fazla 6 site). */
    fun refreshDomains(apis: List<MainAPI> = enabledProviders()) {
        scope.launch(Dispatchers.IO) {
            val gate = Semaphore(6)
            val moved = apis.map { api ->
                async { gate.withPermit { runCatching { domains.refresh(api) }.getOrNull()?.let { api.name } } }
            }.awaitAll().filterNotNull()
            if (moved.isNotEmpty()) registry.domainsChanged(I18n.current.domainsUpdated(moved.joinToString()))
        }
    }

    private companion object {
        const val TAG = "PluginActions"
    }
}
