package seyir.app

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.convertValue
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import seyir.app.data.JsonStore
import seyir.app.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import seyir.core.AppDirs
import seyir.core.CompatibilityReport
import seyir.core.Cs3Converter
import seyir.core.LoadedPlugin
import seyir.core.PluginCompatibility
import seyir.core.PluginLoader
import seyir.core.SeyirLog
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bir eklenti deposundaki (plugins.json) tek kayıt. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RepositoryPlugin(
    val url: String,
    val internalName: String,
    val name: String? = null,
    val version: Int = 0,
    val status: Int = 1,
    val description: String? = null,
    val language: String? = null,
    val iconUrl: String? = null,
    val tvTypes: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    /** İsteğe bağlı: indirilen dosyanın sha256 özeti (küçük harfli onaltılık). Varsa kurulumda doğrulanır. */
    val sha256: String? = null,
)

/** Okunmuş bir eklenti deposu. */
data class PluginRepository(
    /** Kullanıcının girdiği adres (ayarlarda bu saklanır). */
    val url: String,
    val name: String,
    val description: String?,
    val plugins: List<RepositoryPlugin>,
)

/** Toplu kurulumun sonucu: kurulanların adları ve kurulamayanların neden kurulamadığı. */
data class InstallReport(val installed: List<String>, val failures: Map<String, String>)

/**
 * Eklentileri kullanıcı klasöründen (`AppDirs.plugins`) yükler; depodan indirir, dosyadan kurar,
 * kaldırır ve günceller. Eklenti jar'ı rastgele kod olduğu için kurulumda şunlar uygulanır: yalnız
 * `https://` kaynaklar, depo `sha256` verdiyse özet doğrulaması, jar'ın yüklenebilir bir eklenti olduğunun
 * denetimi ve her eklentinin yalnız geldiği depodan güncellenmesi.
 */
class PluginManager(
    private val loader: PluginLoader = PluginLoader(),
    private val sources: PluginSources = PluginSources(),
    private val userDirProvider: () -> File = { AppDirs.plugins },
    /** Kullanıcı eklentilerinin yüklendiği kopyaların klasörü; ilk kullanımda temizlenir (o anda hiçbiri kullanımda değildir). */
    private val shadowDirectory: File = File(AppDirs.data, "cache/plugins-loaded"),
    /** Değiştirilen eklentinin eski sınıf yükleyicisi, kullanan olabileceği için bu kadar sonra kapatılır. */
    private val retireDelayMs: Long = RETIRE_DELAY_MS,
    /** Depoya sonradan eklenen eklentilerin kendiliğinden kurulması için sunulanlar ve kaldırılanlar. */
    val offers: PluginOffers = PluginOffers(),
) {
    val plugins: List<LoadedPlugin> get() = loader.plugins

    private val userDir: File get() = userDirProvider()

    /** Depo kayıtlarının uyumluluk denetimi sonuçları (adres#sürüm → rapor); yalnız bu oturumda geçerli. */
    private val compatibilityCache = java.util.concurrent.ConcurrentHashMap<String, CompatibilityReport>()

    private val shadowDir: File by lazy {
        shadowDirectory.apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /** Kurulum, kaldırma ve ilk yükleme bu kilitle sıraya girer; indirmeler kilidin dışında paralel yürür. */
    private val commitLock = Any()

    private val retireScheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "plugin-retire").apply { isDaemon = true } }
    }

    /** Dosyayı kopyalayıp kopyadan yükler: Windows'ta yüklü jar kilitli kalır, asıl dosya (güncelleme, kaldırma) serbest kalmalı. */
    private fun loadPlugin(file: File, from: File = file): LoadedPlugin {
        val shadow = File(shadowDir, "${file.nameWithoutExtension}-${System.nanoTime()}.jar")
        from.copyTo(shadow)
        return try {
            loader.load(file, codeSource = shadow)
        } catch (t: Throwable) {
            shadow.delete()
            throw t
        }
    }

    fun loadAll(): List<LoadedPlugin> {
        synchronized(commitLock) {
            userDir.mkdirs()
            loader.discover(userDir).forEach { candidate ->
                runCatching { loadPlugin(candidate.file) }
                    .onFailure { SeyirLog.e(TAG, "Eklenti yüklenemedi: ${candidate.file.name}", it) }
            }
            APIHolder.initAll()
        }
        SeyirLog.i(TAG, "${loader.plugins.size} eklenti yüklendi ($userDir)")
        return loader.plugins
    }

    /** Yüklü eklentilerin sağlayıcıları; kayıt defterine (sürüm ve yüklenme sırasıyla) verilir. */
    fun providerCandidates(): List<ProviderCandidate> =
        loader.plugins.flatMapIndexed { index, plugin ->
            plugin.apis.map { ProviderCandidate(it, plugin.manifest.version ?: 0, index) }
        }

    /**
     * Özel GitHub depoları için erişim anahtarı (Ayarlar). Yalnız GitHub adreslerine gönderilir;
     * başka bir siteye giden isteğe asla eklenmez.
     */
    var githubToken: () -> String? = { null }

    private fun authHeaders(url: String): Map<String, String> = authHeadersFor(url, githubToken())

    /**
     * Adresi alır. Yönlendirmeler elle izlenir ve her adım ilkeye ([UrlPolicy]) karşı denetlenir; böylece
     * `https://` bir adres `http://` bir adrese yönlendiremez. Erişim anahtarı her adımda yeniden değerlendirilir.
     */
    private suspend fun httpGet(url: String): NiceResponse {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            UrlPolicy.require(current)
            val response = app.get(current, headers = authHeaders(current), allowRedirects = false)
            val location = response.headers["Location"]
            if (response.code !in REDIRECT_CODES || location == null) return response
            runCatching { response.body.close() }
            current = UrlPolicy.resolve(current, location)
        }
        throw IllegalStateException(I18n.current.tooManyRedirects)
    }

    /** Metni okur (yerel dosya ya da uzak adres); uzakta başarısız yanıt için null döner. */
    private suspend fun getText(url: String): String? {
        if (UrlPolicy.isLocalPath(url)) return UrlPolicy.toFile(url).readText()
        val response = httpGet(url)
        return response.takeIf { it.isSuccessful }?.text
    }

    /** Eklentinin kullanıcı klasöründen kurulup kurulmadığı; yalnız bunlar kaldırılabilir. */
    fun isUserInstalled(plugin: LoadedPlugin): Boolean =
        plugin.file.absoluteFile.parentFile == userDir.absoluteFile

    /**
     * Depoyu okur. Kabul edilen adresler:
     * - `https://github.com/kullanıcı/depo` → `builds` dalındaki repo.json (şablonun yayımladığı yer),
     * - `…/repo.json` (CloudStream biçimi: ad, açıklama, `pluginLists`),
     * - `…/plugins.json` (doğrudan eklenti listesi).
     * Göreli adresler bulundukları dosyaya göre çözülür. Yalnız `https://` (ve yerel geliştirme için
     * `http://localhost`) ile yerel dosya yolları kabul edilir.
     */
    suspend fun fetchRepository(input: String): PluginRepository {
        // GitHub adresinde önce Seyir biçimi (builds/repo.json), sonra CloudStream depolarının yerleri denenir
        val candidates = repositoryUrls(input)
        UrlPolicy.require(candidates.first())
        val (url, text) = candidates.firstNotNullOfOrNull { candidate ->
            runCatching { getText(candidate) }
                .onFailure { if (it is InsecureUrlException || it is CancellationException) throw it }
                .getOrNull()?.let { candidate to it }
        } ?: throw IllegalArgumentException(
            when {
                runCatching { URI(candidates.first()).host }.getOrNull() != "raw.githubusercontent.com" -> I18n.current.repoUnreadable(candidates.first())
                githubToken().isNullOrBlank() -> I18n.current.repoNotFoundNoToken
                else -> I18n.current.repoNotFoundWithToken
            }
        )
        val root = JsonStore.mapper.readTree(text)
        val lists = when {
            root.isArray -> listOf(url to root)
            root.has("pluginLists") -> root.path("pluginLists").map { list ->
                val listUrl = UrlPolicy.resolve(url, list.asText())
                val listText = getText(listUrl) ?: throw IllegalArgumentException(I18n.current.repoUnreadable(listUrl))
                listUrl to JsonStore.mapper.readTree(listText)
            }
            else -> throw IllegalArgumentException(I18n.current.notARepository)
        }
        val plugins = lists.flatMap { (listUrl, node) ->
            JsonStore.mapper.convertValue<List<RepositoryPlugin>>(node).map { it.copy(url = UrlPolicy.resolve(listUrl, it.url)) }
        }
        return PluginRepository(
            url = input,
            name = root.path("name").asText("").ifBlank { runCatching { URI(url).host }.getOrNull() ?: input },
            description = root.path("description").asText("").ifBlank { null },
            plugins = plugins.distinctBy { it.internalName },
        )
    }

    /** Aşamalanmış (indirilmiş, doğrulanmış, gerekirse çevrilmiş) ama henüz kurulmamış eklenti. */
    private class Staged(val jar: File, val manifestName: String, val source: String, val temps: List<File>) {
        fun cleanup() = temps.forEach { it.delete() }
    }

    /** Dosyayı doğrular (gerekirse CloudStream .cs3'ten çevirir); hiçbir şeyi kurmaz. */
    private fun stage(input: File, source: String, temps: MutableList<File>): Staged {
        // CloudStream'in Android eklentisi (.cs3: classes.dex içerir) önce JVM jar'ına çevrilir
        val jar = if (isAndroidPlugin(input)) File.createTempFile("seyir-cs3-", ".jar").also { out ->
            temps += out
            runCatching { Cs3Converter.convert(input, out) }.getOrElse {
                throw IllegalArgumentException(I18n.current.cs3ConvertFailed(it.message))
            }
        } else input
        val manifest = runCatching { loader.readManifest(jar) }
            .getOrElse { throw IllegalArgumentException(I18n.current.invalidPlugin(it.message)) }
        val name = manifest.name ?: throw IllegalArgumentException(I18n.current.manifestNoName)
        requireNotNull(manifest.pluginClassName) { I18n.current.manifestNoClass }
        require(manifest.requiresResources != true) { I18n.current.needsAndroidResources(name) }
        // Eski sürümün yerine geçmeden önce yenisinin gerçekten yüklenebilir bir eklenti olduğu görülür
        runCatching { loader.validate(jar) }
            .getOrElse { throw IllegalArgumentException(I18n.current.invalidPlugin(it.message)) }
        // Statik uyumluluk denetimi: eklentinin başvurduğu sınıflar Seyir'de yoksa hiçbir şeye dokunulmadan reddedilir
        val compatibility = PluginCompatibility.check(jar)
        require(compatibility.compatible) { I18n.current.pluginIncompatible(compatibility.describeMissing()) }
        return Staged(jar, name, source, temps.toList())
    }

    /** Depo kaydındaki dosyayı indirip (sha256 varsa doğrulayıp) aşamalar. */
    private suspend fun downloadAndStage(entry: RepositoryPlugin, source: String): Staged {
        val temps = mutableListOf<File>()
        try {
            val tmp = File.createTempFile("seyir-eklenti-", ".tmp").also { temps += it }
            val digest = MessageDigest.getInstance("SHA-256")
            if (UrlPolicy.isLocalPath(entry.url)) {
                UrlPolicy.toFile(entry.url).inputStream().use { copyDigesting(it, tmp, digest) }
            } else {
                val response = httpGet(entry.url)
                if (!response.isSuccessful) throw IllegalStateException(I18n.current.pluginDownloadFailed(response.code))
                response.body.byteStream().use { copyDigesting(it, tmp, digest) }
            }
            if (!checksumMatches(entry.sha256, digest.digest().toHex()))
                throw IllegalStateException(I18n.current.integrityMismatch(entry.name ?: entry.internalName))
            return stage(tmp, source, temps)
        } catch (t: Throwable) {
            temps.forEach { it.delete() }
            throw t
        }
    }

    private fun copyDigesting(input: java.io.InputStream, target: File, digest: MessageDigest) {
        DigestInputStream(input, digest).use { stream -> target.outputStream().use { stream.copyTo(it) } }
    }

    /**
     * Depodaki eklentiyi indirip kurar (aynı adlı eklenti varsa yerini alır; CloudStream .cs3 ise çevrilir).
     * [source] eklentinin geldiği depo adresidir; verilmezse (doğrudan adres) eklenti bir depoya bağlanmaz.
     */
    suspend fun install(entry: RepositoryPlugin, source: String? = null): LoadedPlugin {
        val staged = downloadAndStage(entry, source ?: PluginSources.LOCAL)
        try {
            return commit(listOf(staged)).single().getOrThrow()
        } finally {
            staged.cleanup()
        }
    }

    /**
     * Seçilen eklentileri kurar: [DOWNLOAD_PARALLELISM] kadarı aynı anda indirilir, hepsi bitince tek
     * seferde yüklenir. [onProgress] her indirmeden önce (kaçıncı, toplam, ad) ile çağrılır (başka
     * iş parçacığından olabilir). Kurulamayan atlanıp günlüğe yazılır ve sonuçta nedeniyle bildirilir.
     */
    suspend fun installSelected(
        entries: List<RepositoryPlugin>,
        source: String? = null,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): InstallReport {
        val gate = Semaphore(DOWNLOAD_PARALLELISM)
        val started = AtomicInteger()
        val failures = linkedMapOf<String, String>()
        fun fail(entry: RepositoryPlugin, error: Throwable) {
            SeyirLog.w(TAG, "${entry.internalName} kurulamadı", error)
            synchronized(failures) { failures[entry.internalName] = error.message ?: error::class.simpleName.orEmpty() }
        }
        val staged = coroutineScope {
            entries.map { entry ->
                async {
                    gate.withPermit {
                        onProgress(started.incrementAndGet(), entries.size, entry.name ?: entry.internalName)
                        runCatching { downloadAndStage(entry, source ?: PluginSources.LOCAL) }
                            .onFailure { if (it is CancellationException) throw it else fail(entry, it) }
                            .getOrNull()?.let { entry to it }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val installed = try {
            val results = commit(staged.map { it.second })
            staged.zip(results).mapNotNull { (item, result) ->
                result.onFailure { fail(item.first, it) }.getOrNull()?.let { item.first.internalName }
            }
        } finally {
            staged.forEach { it.second.cleanup() }
        }
        return InstallReport(installed, failures.toMap())
    }

    /**
     * Depo kaydındaki eklentiyi indirir (gerekirse .cs3'ten çevirir) ve kurmadan statik uyumluluk denetiminden
     * geçirir. Sonuç bellekte adres+sürüm başına saklanır; aynı kayıt yeniden sorulunca indirilmez.
     * İndirme ya da çevirme başarısız olursa istisna fırlar (sonuç saklanmaz).
     */
    suspend fun checkCompatibility(entry: RepositoryPlugin): CompatibilityReport {
        cachedCompatibility(entry)?.let { return it }
        val report = withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("seyir-denetim-", ".tmp")
            var converted: File? = null
            try {
                if (UrlPolicy.isLocalPath(entry.url)) {
                    UrlPolicy.toFile(entry.url).copyTo(tmp, overwrite = true)
                } else {
                    val response = httpGet(entry.url)
                    if (!response.isSuccessful) throw IllegalStateException(I18n.current.pluginDownloadFailed(response.code))
                    response.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                }
                if (isAndroidPlugin(tmp)) {
                    converted = File.createTempFile("seyir-denetim-", ".jar").also { Cs3Converter.convert(tmp, it) }
                }
                PluginCompatibility.check(converted ?: tmp)
            } finally {
                tmp.delete()
                converted?.delete()
            }
        }
        compatibilityCache["${entry.url}#${entry.version}"] = report
        return report
    }

    /** Bu kayıt için daha önce yapılmış uyumluluk denetiminin sonucu (yoksa null). */
    fun cachedCompatibility(entry: RepositoryPlugin): CompatibilityReport? = compatibilityCache["${entry.url}#${entry.version}"]

    /** Yüklü eklentiyi manifest adıyla bulur. */
    fun pluginNamed(name: String): LoadedPlugin? = loader.plugins.firstOrNull { it.manifest.name == name }

    /** Kurulu sürümü (yoksa null); seçim penceresi kurulu/güncellenebilir durumunu buradan okur. */
    fun installedVersion(internalName: String): Int? =
        loader.plugins.firstOrNull { it.manifest.name == internalName }?.let { it.manifest.version ?: 0 }

    /** Depodaki kurulu olmayan ve kurulu sürümü eski olan bütün eklentileri kurar; dönen değer: kurulanlar. */
    suspend fun installAll(repository: PluginRepository): List<String> =
        installSelected(
            repository.plugins.filter { entry ->
                val current = loader.plugins.firstOrNull { it.manifest.name == entry.internalName }
                current == null || (current.manifest.version ?: 0) < entry.version
            },
            repository.url,
        ).installed

    /**
     * Bir eklenti dosyasını (.jar ya da CloudStream .cs3) kullanıcı klasörüne kopyalayıp yükler. Önce
     * manifest.json okunur ve jar doğrulanır; geçerli bir Seyir eklentisi değilse hiçbir şeye dokunulmaz.
     * Dosyadan kurulan eklenti bir depoya bağlanmaz, kendiliğinden güncellenmez.
     */
    fun installFile(input: File): LoadedPlugin {
        val temps = mutableListOf<File>()
        try {
            val staged = stage(input, PluginSources.LOCAL, temps)
            return commit(listOf(staged)).single().getOrThrow()
        } finally {
            temps.forEach { it.delete() }
        }
    }

    /**
     * Aşamalanmış eklentileri kurar. Yeni sürüm önce kopyadan yüklenir; ancak yüklenebildiyse dosyası eskinin
     * yerine geçer ve eski sürüm kayıttan çıkarılır (sınıf yükleyicisi gecikmeyle kapanır). `initAll`
     * toplu işte bir kez çalışır.
     */
    private fun commit(items: List<Staged>): List<Result<LoadedPlugin>> = synchronized(commitLock) {
        val replaced = mutableListOf<LoadedPlugin>()
        val results = items.map { staged ->
            runCatching { swapIn(staged, replaced) }
                .onFailure { SeyirLog.w(TAG, "${staged.manifestName} kurulamadı", it) }
        }
        replaced.forEach(::retire)
        APIHolder.initAll()
        results
    }

    private fun swapIn(staged: Staged, replaced: MutableList<LoadedPlugin>): LoadedPlugin {
        userDir.mkdirs()
        val name = staged.manifestName
        val target = File(userDir, "${safeFileName(name)}.jar")
        val plugin = loadPlugin(target, from = staged.jar)
        try {
            // Önce yeni dosya yerine yazılır; eski sürüm ancak bu başarılıysa kaldırılır
            if (staged.jar.absoluteFile != target.absoluteFile) {
                val part = File(userDir, "${target.name}.part")
                Files.copy(staged.jar.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            retire(plugin)
            throw t
        }
        replaced += loader.plugins.filter { it.manifest.name == name && it !== plugin }
        sources.set(name, staged.source)
        // Elle yeniden kurulan eklenti artık "kullanıcı kaldırdı" sayılmaz
        offers.clearRemoved(name)
        SeyirLog.i(TAG, "Kuruldu: $name v${plugin.manifest.version} (${target.name})")
        return plugin
    }

    /**
     * Eklentiyi kayıtlardan çıkarır; sınıf yükleyicisi ve kopya jar'ı [retireDelayMs] sonra kapatılıp silinir.
     * Arayüz, ana sayfa ya da süren bir arama eski örnekleri o ana dek kullanabilir.
     */
    private fun retire(plugin: LoadedPlugin) {
        loader.unload(plugin, closeClassLoader = false)
        val release = Runnable {
            loader.closeClassLoader(plugin)
            if (plugin.codeSource != plugin.file && !plugin.codeSource.delete() && plugin.codeSource.exists())
                SeyirLog.w(TAG, "Kopya silinemedi: ${plugin.codeSource.name}")
        }
        if (retireDelayMs <= 0) release.run() else retireScheduler.schedule(release, retireDelayMs, TimeUnit.MILLISECONDS)
    }

    /** Kullanıcının kurduğu eklentiyi kaldırır. */
    fun uninstall(plugin: LoadedPlugin) {
        val name = plugin.manifest.name
        synchronized(commitLock) {
            retire(plugin)
            if (!plugin.file.delete()) SeyirLog.w(TAG, "Dosya silinemedi: ${plugin.file}")
            APIHolder.initAll()
        }
        name?.let(sources::remove)
        // Kullanıcının kaldırdığı eklenti açılışta depodan geri kurulmasın
        name?.let(offers::markRemoved)
        SeyirLog.i(TAG, "Kaldırıldı: $name")
    }

    /**
     * Kurulu eklentilerin yeni sürümlerini kurar (kurulu olmayanlara dokunmaz). Her eklenti yalnız kendi
     * kaynağı olan depodan güncellenir; aynı adı listeleyen başka bir depo onun yerine geçemez. Kaynağı
     * kaydedilmemiş (eski kurulum) eklenti, yalnız tek bir depo listeliyorsa o depoya bağlanır.
     * Dönen değer: güncellenen eklenti adları.
     */
    suspend fun updateFrom(repositories: List<String>): List<String> = updateFrom(fetchRepositories(repositories))

    /** Depoların eklenti listeleri (okunamayan depo atlanır, günlüğe yazılır). */
    suspend fun fetchRepositories(repositories: List<String>): Map<String, List<RepositoryPlugin>> {
        val fetched = linkedMapOf<String, List<RepositoryPlugin>>()
        for (repo in repositories) {
            runCatching { fetchRepository(repo).plugins }
                .onSuccess { fetched[repo] = it }
                .onFailure { if (it is CancellationException) throw it else SeyirLog.w(TAG, "Depo okunamadı: $repo", it) }
        }
        return fetched
    }

    /** [updateFrom]'un önceden okunmuş depo listeleriyle çalışan hâli. */
    suspend fun updateFrom(fetched: Map<String, List<RepositoryPlugin>>): List<String> {
        val plan = linkedMapOf<String, MutableList<RepositoryPlugin>>()
        for (current in loader.plugins) {
            val name = current.manifest.name ?: continue
            val owner = ownerRepository(name, fetched) ?: continue
            val entry = fetched.getValue(owner).firstOrNull { it.internalName == name } ?: continue
            if ((current.manifest.version ?: 0) >= entry.version) continue
            plan.getOrPut(owner) { mutableListOf() } += entry
        }
        return plan.flatMap { (repo, entries) -> installSelected(entries, repo).installed }
    }

    /**
     * Depolara sonradan eklenen eklentileri kurar ([PluginOffers.newOnes]: kurulu değil, kullanıcı kaldırmamış,
     * daha önce sunulmamış). Kurulanlar ve kurulumu bilerek atlananlar o deponun "sunulmuş" listesine girer;
     * indirme hatası verenler bir sonraki açılışta yeniden denenir. Dönen değer: kurulan eklenti adları.
     */
    suspend fun installNewFrom(fetched: Map<String, List<RepositoryPlugin>>): List<String> {
        val installed = loader.plugins.mapNotNull { it.manifest.name }.toSet()
        val claimed = mutableSetOf<String>()
        val added = mutableListOf<String>()
        for ((repo, entries) in fetched) {
            // Aynı eklentiyi listeleyen iki depodan yalnız ilki kurar
            val names = offers.newOnes(repo, entries.map { it.internalName }, installed + claimed)
            val chosen = entries.filter { it.internalName in names }
            claimed += names
            val report = if (chosen.isEmpty()) InstallReport(emptyList(), emptyMap()) else installSelected(chosen, repo)
            added += report.installed
            report.failures.forEach { (name, reason) -> SeyirLog.w(TAG, "Yeni eklenti kurulamadı: $name ($reason)") }
            offers.markSeen(repo, entries.map { it.internalName }.filter { it !in report.failures })
        }
        return added
    }

    /**
     * Kaynağı okunan depolardan biri olduğu hâlde o depoda artık listelenmeyen kurulu eklentiler (depodan
     * çıkarılmış; güncelleme almaz). Dosyadan, doğrudan adresten ya da kaynağı bilinmeden kurulanlar sayılmaz.
     */
    fun delistedFrom(fetched: Map<String, List<RepositoryPlugin>>): Set<String> =
        loader.plugins.mapNotNull { it.manifest.name }.filterTo(mutableSetOf()) { name ->
            val source = sources.get(name)?.takeIf { it != PluginSources.LOCAL } ?: return@filterTo false
            val repo = fetched.keys.firstOrNull { PluginSources.sameSource(it, source) } ?: return@filterTo false
            fetched.getValue(repo).none { it.internalName == name }
        }

    /** Eklentinin güncelleneceği depo ([fetched] anahtarlarından biri); yoksa null. */
    private fun ownerRepository(name: String, fetched: Map<String, List<RepositoryPlugin>>): String? {
        val pinned = sources.get(name)
        if (pinned == PluginSources.LOCAL) return null
        if (pinned != null) return fetched.keys.firstOrNull { PluginSources.sameSource(it, pinned) }
        val listing = fetched.filterValues { entries -> entries.any { it.internalName == name } }.keys
        val only = listing.singleOrNull()
        if (only == null) {
            if (listing.size > 1) SeyirLog.w(TAG, "$name birden fazla depoda var ve kaynağı bilinmiyor; kendiliğinden güncellenmedi")
            return null
        }
        sources.set(name, only)
        return only
    }

    companion object {
        private const val TAG = "PluginManager"
        private const val RETIRE_DELAY_MS = 30_000L
        private const val DOWNLOAD_PARALLELISM = 3
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val GITHUB_HOSTS = setOf("github.com", "raw.githubusercontent.com", "api.github.com", "objects.githubusercontent.com")
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

        /**
         * İndirilen dosyanın özeti ([actual], küçük harfli onaltılık) depodaki değerle ([expected]) uyuşuyor mu.
         * Depo özet vermediyse (null) denetim yoktur; verdiği değer geçerli bir sha256 değilse reddedilir.
         */
        fun checksumMatches(expected: String?, actual: String): Boolean {
            val wanted = expected?.trim() ?: return true
            return SHA256_HEX.matches(wanted) && wanted.equals(actual, ignoreCase = true)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /**
         * Erişim anahtarını yalnız `https://` ile GitHub sunucularına giden isteğe ekler; başka bir
         * adrese giden isteğe hiçbir zaman eklenmez.
         */
        fun authHeadersFor(url: String, token: String?): Map<String, String> {
            val value = token?.trim()?.ifBlank { null } ?: return emptyMap()
            val uri = runCatching { URI(url) }.getOrNull() ?: return emptyMap()
            if (uri.scheme?.lowercase() != "https") return emptyMap()
            return if (uri.host?.lowercase() in GITHUB_HOSTS) mapOf("Authorization" to "token $value") else emptyMap()
        }

        /**
         * Depo adresinin okunacağı yerler. GitHub depo adresinde sırayla: Seyir deposu (builds/repo.json),
         * CloudStream depoları (master/main dalındaki repo.json), yalnız eklenti listesi (builds/plugins.json).
         * Öteki adresler olduğu gibi kullanılır.
         */
        fun repositoryUrls(input: String): List<String> {
            val trimmed = input.trim().trimEnd('/')
            val github = Regex("""^https?://(?:www\.)?github\.com/([^/]+)/([^/]+?)(?:\.git)?(?:/.*)?$""").find(trimmed)
                ?: return listOf(trimmed)
            val (owner, repo) = github.destructured
            val raw = "https://raw.githubusercontent.com/$owner/$repo"
            return listOf("$raw/builds/repo.json", "$raw/master/repo.json", "$raw/main/repo.json", "$raw/builds/plugins.json")
        }

        /** Dosya CloudStream'in Android eklentisi mi (zip içinde classes.dex). */
        fun isAndroidPlugin(file: File): Boolean =
            runCatching { java.util.zip.ZipFile(file).use { it.getEntry("classes.dex") != null } }.getOrDefault(false)

        private fun safeFileName(name: String): String =
            java.text.Normalizer.normalize(name.replace('ı', 'i'), java.text.Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    }
}
