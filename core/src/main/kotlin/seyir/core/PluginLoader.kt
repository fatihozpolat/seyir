package seyir.core

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Yüklenmiş bir eklenti ve kaydettiği sağlayıcılar. [file] eklentinin kalıcı dosyasıdır (kaldırma ve
 * güncellemede kullanılır); sınıflar [codeSource]'tan okunur (kopyadan yüklendiyse kopya).
 */
class LoadedPlugin(
    val manifest: BasePlugin.Manifest,
    val file: File,
    val instance: Plugin,
    internal val classLoader: URLClassLoader,
    val apis: List<MainAPI>,
    val codeSource: File = file,
)

/** Diskte bulunmuş, manifesti okunabilen bir eklenti dosyası. */
data class PluginCandidate(val file: File, val manifest: BasePlugin.Manifest)

/**
 * Eklenti jar'larını çalışma anında yükler. Her eklenti kendi sınıf yükleyicisini alır; böylece
 * aynı paket ve adla sınıf taşıyan iki eklenti (ör. ortak extractor kopyaları) çakışmaz.
 */
class PluginLoader(private val context: Context = Context()) {
    init {
        Compat.install()
    }

    private val mapper = jacksonObjectMapper()
    private val loaded = mutableListOf<LoadedPlugin>()

    val plugins: List<LoadedPlugin> get() = synchronized(loaded) { loaded.toList() }

    /**
     * Klasördeki yüklenecek eklentileri bulur: manifesti okunamayan jar'lar atlanır, aynı adlı birden
     * fazla dosya varsa yalnız sürümü en yüksek olan kalır. Sonuç dosya adına göre sıralıdır.
     */
    fun discover(dir: File): List<PluginCandidate> =
        selectLatest(
            dir.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty().sortedBy { it.name }
                .mapNotNull { jar -> runCatching { PluginCandidate(jar, readManifest(jar)) }.getOrNull() }
        )

    /** Klasördeki bütün .jar eklentilerini yükler; bozuk olanları atlayıp günlüğe yazar. */
    fun loadAll(dir: File): List<LoadedPlugin> {
        val result = discover(dir).mapNotNull { candidate ->
            runCatching { load(candidate.file) }
                .onFailure { SeyirLog.e(TAG, "Eklenti yüklenemedi: ${candidate.file.name}", it) }
                .getOrNull()
        }
        APIHolder.initAll()
        return result
    }

    /**
     * @param codeSource sınıfların okunacağı dosya; verilmezse [file]. Windows'ta yüklenen jar kapatılsa da
     * kilitli kalabildiği için uygulama kullanıcı eklentilerini bir kopyadan yükler, asıl dosya
     * ([file], kaldırma ve güncellemede kullanılan) serbest kalır.
     */
    fun load(file: File, codeSource: File = file): LoadedPlugin {
        val manifest = readManifest(codeSource)
        val className = manifest.pluginClassName
            ?: throw IllegalStateException("manifest.json içinde pluginClassName yok")

        val classLoader = URLClassLoader(arrayOf(codeSource.toURI().toURL()), parentClassLoader)
        try {
            val instance = classLoader.loadClass(className).getDeclaredConstructor().newInstance() as Plugin
            // Kimlik kod kaynağının yoludur: aynı eklentinin yeni sürümü eskisi bellekteyken yüklenebilsin,
            // kaldırma da yalnız kendi kayıtlarını silsin
            val identity = codeSource.absolutePath
            instance.filename = identity
            instance.load(context)

            val apis = APIHolder.apis.filter { it.sourcePlugin == identity }
            val plugin = LoadedPlugin(manifest, file, instance, classLoader, apis, codeSource)
            synchronized(loaded) { loaded += plugin }
            SeyirLog.i(TAG, "Yüklendi: ${manifest.name} v${manifest.version} (${apis.joinToString { it.name }})")
            return plugin
        } catch (t: Throwable) {
            classLoader.close()
            throw t
        }
    }

    /**
     * Eklentiyi kayıtlardan çıkarır. [closeClassLoader] false verilirse sınıf yükleyici açık kalır: arayüz,
     * ana sayfa ya da süren bir arama eski [MainAPI] örneklerini hâlâ kullanıyor olabilir; yükleyici
     * onlar yeni kümeye geçtikten sonra [closeClassLoader] ile kapatılır.
     */
    fun unload(plugin: LoadedPlugin, closeClassLoader: Boolean = true) {
        runCatching { plugin.instance.beforeUnload() }
            .onFailure { SeyirLog.w(TAG, "beforeUnload hata verdi: ${plugin.manifest.name}", it) }
        val path = plugin.codeSource.absolutePath
        plugin.apis.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.removeAll { it.sourcePlugin == path }
        extractorApis.removeAll { it.sourcePlugin == path }
        synchronized(loaded) { loaded.remove(plugin) }
        if (closeClassLoader) closeClassLoader(plugin)
    }

    /** Kayıtlardan çıkarılmış eklentinin sınıf yükleyicisini kapatır (jar kilidi de açılır). */
    fun closeClassLoader(plugin: LoadedPlugin) {
        runCatching { plugin.classLoader.close() }
            .onFailure { SeyirLog.w(TAG, "Sınıf yükleyici kapatılamadı: ${plugin.manifest.name}", it) }
    }

    /** Jar içindeki manifest.json dosyasını okur (eklentiyi yüklemeden). */
    fun readManifest(file: File): BasePlugin.Manifest =
        JarFile(file).use { jar ->
            val entry = jar.getJarEntry("manifest.json")
                ?: throw IllegalStateException("${file.name} içinde manifest.json yok")
            jar.getInputStream(entry).use { mapper.readValue(it) }
        }

    /**
     * Dosyanın yüklenebilir bir eklenti olduğunu doğrular: zip olarak açılmalı, manifest.json okunmalı ve
     * manifestin gösterdiği giriş sınıfı jar içinde bulunmalı. Eklentiyi yüklemez; hata olursa
     * [IllegalStateException] fırlatır.
     */
    fun validate(file: File): BasePlugin.Manifest {
        val manifest = readManifest(file)
        val className = manifest.pluginClassName
            ?: throw IllegalStateException("manifest.json içinde pluginClassName yok")
        JarFile(file).use { jar ->
            if (jar.getJarEntry(className.replace('.', '/') + ".class") == null)
                throw IllegalStateException("${file.name} içinde $className sınıfı yok")
        }
        return manifest
    }

    companion object {
        private const val TAG = "PluginLoader"

        /** Aynı adı taşıyan adaylardan sürümü en yüksek olanı bırakır (eşitse ilk gelen); dosya adına göre sıralar. */
        fun selectLatest(candidates: List<PluginCandidate>): List<PluginCandidate> =
            candidates
                .groupBy { it.manifest.name }
                .mapNotNull { (_, versions) -> versions.maxByOrNull { it.manifest.version ?: 0 } }
                .sortedBy { it.file.name }

        /**
         * Eklenti sınıf yükleyicilerinin üst yükleyicisi: Seyir'in kendi sınıf yolu. Eklentiler
         * Android/CloudStream taklit sınıflarını ve [seyir.core] altındakileri buradan görür; statik
         * uyumluluk denetimi ([PluginCompatibility]) de aynı yükleyiciye bakar.
         */
        val parentClassLoader: ClassLoader get() = PluginLoader::class.java.classLoader
    }
}
