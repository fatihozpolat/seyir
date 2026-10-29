package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import seyir.core.SeyirLog
import kotlin.coroutines.CoroutineContext

/** Kayıt defterine girecek sağlayıcı: hangi eklentiden, kaçıncı sürümle ve kaçıncı sırada yüklendiği bilinir. */
class ProviderCandidate(val api: MainAPI, val pluginVersion: Int, val loadOrder: Int)

/**
 * Sağlayıcı listesinin tek sahibi. Liste, hazır olma durumu ve durum iletisi yalnız burada ve yalnız
 * [uiContext] (ana iş parçacığı) üzerinde yazılır; yazımlar bir kilitle sıraya girer, böylece
 * `IO` iş parçacıklarından gelen eşzamanlı yenilemeler birbirinin sonucunu eski liste ile ezemez.
 * Okuyanlar herhangi bir iş parçacığından [providers] değerini alır; her yayın yeni bir anlık görüntüdür.
 */
class ProviderRegistry(
    private val uiContext: CoroutineContext = Dispatchers.Main,
    initialStatus: String = "",
) {
    private val writer = Mutex()

    var providers by mutableStateOf<List<MainAPI>>(emptyList())
        private set
    var ready by mutableStateOf(false)
        private set
    var status by mutableStateOf(initialStatus)
        private set

    /** Bir sağlayıcının alan adı yerinde değişince artar; adres gösteren ekranlar bunu okuyarak yenilenir. */
    var domainsVersion by mutableIntStateOf(0)
        private set

    /**
     * Listeyi [candidates]'in döndürdüğü adaylarla değiştirir ve yeni anlık görüntüyü döndürür. Adaylar
     * kilit alındıktan sonra hesaplanır: kilidi bekleyen bir yayın, bekleme sırasında eskimiş bir
     * eklenti listesini yayımlamaz. Adı aynı olan sağlayıcılardan yalnız biri kalır ([dedupeByName]).
     * Daha önce listede olmayan sağlayıcılar yayından önce [onNew]'e verilir.
     */
    suspend fun publish(
        candidates: () -> List<ProviderCandidate>,
        markReady: Boolean = false,
        onNew: (MainAPI) -> Unit = {},
    ): List<MainAPI> = writer.withLock {
        val next = dedupeByName(candidates()).sortedBy { it.name.lowercase() }
        val known = providers
        next.filter { api -> known.none { it === api } }.forEach(onNew)
        withContext(uiContext) {
            providers = next
            if (markReady) ready = true
        }
        next
    }

    /** Alt şeritte gösterilen durum iletisini yazar. */
    suspend fun announce(message: String) {
        writer.withLock { withContext(uiContext) { status = message } }
    }

    /** Alan adı değişen sağlayıcı olduğunu duyurur ([message] verilirse durum iletisi de yazılır). */
    suspend fun domainsChanged(message: String? = null) {
        writer.withLock {
            withContext(uiContext) {
                domainsVersion++
                if (message != null) status = message
            }
        }
    }

    companion object {
        private const val TAG = "ProviderRegistry"

        /**
         * Aynı görünen adı taşıyan sağlayıcılardan birini seçer: eklenti sürümü en yüksek olan, eşitse önce
         * yüklenen kalır. Ad çakışması arayüzde (anahtarlı listelerde) çökmeye yol açtığı için uyarı yazılır.
         */
        fun dedupeByName(candidates: List<ProviderCandidate>): List<MainAPI> =
            candidates.groupBy { it.api.name }.map { (name, group) ->
                val best = group.sortedWith(compareByDescending<ProviderCandidate> { it.pluginVersion }.thenBy { it.loadOrder }).first()
                if (group.size > 1) SeyirLog.w(TAG, "\"$name\" adı ${group.size} sağlayıcıda var; yalnız biri kullanılıyor")
                best.api
            }
    }
}
