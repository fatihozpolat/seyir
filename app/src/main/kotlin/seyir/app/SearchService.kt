package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import seyir.app.data.AppSettings
import seyir.app.i18n.I18n
import seyir.core.withHardTimeout
import kotlin.coroutines.coroutineContext

/** Bir sağlayıcının arama sonuçları; [key] sağlayıcının sıra numarasıdır (adlar tekrar edebilir). */
data class SearchProviderResult(val key: Int, val provider: String, val items: List<SearchResponse>)

/** Arama ekranının durumu; detaya gidip dönünce kaybolmasın diye burada tutulur. */
class SearchState {
    var query by mutableStateOf("")
    var running by mutableStateOf(false)
    val results = mutableStateListOf<SearchProviderResult>()
    val failed = mutableStateListOf<Pair<String, String>>()
    var pending by mutableIntStateOf(0)

    /** Sonuç listesi her değiştiğinde artar; ekran sıralamayı yalnız buna bakarak yeniden hesaplar. */
    var revision by mutableIntStateOf(0)

    /** Aramanın yapılacağı sağlayıcıların adları; null hepsi demektir. */
    var providerFilter by mutableStateOf<Set<String>?>(null)
}

/** Etkin sağlayıcılarda paralel aramayı yürütür; durumu [state]'e yazar. */
class SearchService(
    private val scope: CoroutineScope,
    private val providers: () -> List<MainAPI>,
    private val settings: () -> AppSettings,
    private val health: HealthService,
) {
    val state = SearchState()

    private var searchJob: Job? = null
    private var searchGeneration = 0

    /**
     * Bütün etkin sağlayıcılarda paralel arama; sonuçlar geldikçe listeye eklenir. Yeni arama
     * öncekini iptal eder; geç kalan eski sonuçlar nesil sayacıyla ayıklanır.
     */
    fun run(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        val generation = ++searchGeneration
        state.query = q
        state.results.clear()
        state.revision++
        state.failed.clear()
        val filter = state.providerFilter
        val disabled = settings().disabledProviders
        // Anahtar sağlayıcının listedeki sırasıdır: aynı adı taşıyan sağlayıcılar da ayrışır
        val apis = providers().withIndex().filter { (_, api) ->
            api.name !in disabled && (filter == null || api.name in filter)
        }
        state.pending = apis.size
        state.running = true
        searchJob = scope.launch {
            val gate = Semaphore(8)
            coroutineScope {
                for ((index, api) in apis) {
                    launch {
                        val result = try {
                            Result.success(gate.withPermit {
                                withHardTimeout(scope, SEARCH_TIMEOUT_MS) { api.search(q).orEmpty() }
                            })
                        } catch (e: TimeoutCancellationException) {
                            Result.failure(IllegalStateException(I18n.current.requestTimedOut((SEARCH_TIMEOUT_MS / 1000).toInt())))
                        } catch (e: CancellationException) {
                            // Gerçek iptal (yeni arama) yukarı taşınır; eklentinin kendi fırlattığı iptal hata sayılır
                            coroutineContext.ensureActive()
                            Result.failure(e)
                        } catch (e: Throwable) {
                            // Eklentiden gelen Error'lar (NoSuchMethodError vb.) da hata sonucu olur; kapsamı çökertmesin
                            Result.failure(e)
                        }
                        if (generation != searchGeneration) return@launch
                        result.onSuccess {
                            if (it.isNotEmpty()) {
                                state.results.add(SearchProviderResult(index, api.name, it))
                                state.revision++
                                health.report(api.name, ok = true, source = HealthSource.SEARCH)
                            }
                        }.onFailure {
                            val message = it.message ?: it::class.simpleName.orEmpty()
                            state.failed.add(api.name to message)
                            health.report(api.name, ok = false, message = userMessage(it), source = HealthSource.SEARCH)
                        }
                        state.pending--
                    }
                }
            }
            if (generation == searchGeneration) state.running = false
        }
    }

    private companion object {
        /** Bir sağlayıcının aramasına tanınan en uzun süre; aşılırsa sağlayıcı yanıt vermedi sayılır. */
        const val SEARCH_TIMEOUT_MS = 25_000L
    }
}
