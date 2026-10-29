package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import seyir.app.i18n.I18n
import seyir.core.DomainTracker
import seyir.core.FailureKind
import seyir.core.LoadedPlugin
import seyir.core.ProbeResult
import seyir.core.ProbeStatus
import seyir.core.ProviderProbe
import seyir.core.SeyirLog

/**
 * Sına: kurulu eklentiyi uçtan uca deneme. Sonuçları ve süren işlerin durumunu Compose durumu olarak
 * tutar, sonucu sağlık kaydına yansıtır.
 */
class ProbeService(
    private val scope: CoroutineScope,
    domains: DomainTracker,
    private val plugins: PluginManager,
    private val health: HealthService,
    private val providers: () -> List<MainAPI>,
) {
    /** Sağlayıcı adına göre son sınama sonucu (yalnız bu oturumda tutulur). */
    val results = mutableStateMapOf<String, ProbeResult>()

    /** Şu an sınanmakta olan sağlayıcılar. */
    val probing = mutableStateMapOf<String, Boolean>()

    /** "Tümünü sına" sürerken (biten, toplam); değilse null. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    private val prober = ProviderProbe(domains = domains)

    /** Tek sağlayıcıyı sınar; sonucu saklar ve sağlık haritasına yansıtır (kırmızı: sorunlu, öteki: çalışıyor). */
    suspend fun probeProvider(api: MainAPI): ProbeResult {
        probing[api.name] = true
        try {
            val result = prober.run(api)
            results[api.name] = result
            val red = result.status == ProbeStatus.RED
            // Doğrulama bekleyen sonuç ne çalışıyor ne bozuk demektir; gerçek kullanımdan gelen kayıt silinmesin
            if (!result.needsChallenge) {
                health.report(api.name, ok = !red, message = if (red) result.healthMessage(I18n.current) else null, source = HealthSource.PROBE)
            }
            return result
        } finally {
            probing.remove(api.name)
        }
    }

    /** Sağlayıcıları aynı anda en çok [parallelism] tanesi çalışacak biçimde sınar. */
    suspend fun probeApis(apis: List<MainAPI>, parallelism: Int = 3, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<ProbeResult> =
        coroutineScope {
            val gate = Semaphore(parallelism)
            val done = java.util.concurrent.atomic.AtomicInteger()
            onProgress(0, apis.size)
            val first = apis.map { api ->
                async(Dispatchers.IO) {
                    gate.withPermit { probeProvider(api) }.also { onProgress(done.incrementAndGet(), apis.size) }
                }
            }.awaitAll()
            // Aynı anda çalışan sınamaların yükü sahte zaman aşımı üretebilir; zaman aşımına uğrayanlar
            // hepsi bittikten sonra tek tek bir kez daha denenir
            first.mapIndexed { index, result ->
                if (result.firstFailure?.second?.kind == FailureKind.TIMEOUT) probeProvider(apis[index]) else result
            }
        }

    /** Tek sağlayıcıyı arka planda sınar. */
    fun probe(api: MainAPI) {
        if (probing[api.name] == true) return
        scope.launch(Dispatchers.IO) { runCatching { probeProvider(api) }.onFailure { SeyirLog.w(TAG, "${api.name} sınanamadı", it) } }
    }

    /** Bütün sağlayıcıları 3'erli sınar; bitince [onDone] özet iletiyle çağrılır. */
    fun probeAll(onDone: (String) -> Unit) {
        if (progress != null) return
        val apis = providers()
        scope.launch(Dispatchers.IO) {
            progress = 0 to apis.size
            val outcomes = try {
                probeApis(apis) { done, total -> progress = done to total }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                SeyirLog.w(TAG, "Toplu sınama başarısız", t)
                emptyList()
            } finally {
                progress = null
            }
            val t = I18n.current
            val counts = t.probeCounts(
                outcomes.count { it.status == ProbeStatus.GREEN },
                outcomes.count { it.status == ProbeStatus.YELLOW },
                outcomes.count { it.status == ProbeStatus.RED },
            )
            withContext(Dispatchers.Main) { onDone(t.pluginsProbed(outcomes.size) + if (counts.isNotEmpty()) " · $counts" else "") }
        }
    }

    /** Kullanıcının kurduğu ve bütün sağlayıcıları son sınamada kırmızı çıkan eklentiler. */
    fun failingPlugins(): List<LoadedPlugin> = plugins.plugins.filter { plugin ->
        plugins.isUserInstalled(plugin) && plugin.apis.isNotEmpty() &&
            plugin.apis.all { results[it.name]?.status == ProbeStatus.RED }
    }

    private companion object {
        const val TAG = "ProbeService"
    }
}
