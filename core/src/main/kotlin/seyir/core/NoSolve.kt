package seyir.core

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import okhttp3.Call
import okhttp3.EventListener
import java.util.Collections
import java.util.WeakHashMap
import kotlin.coroutines.CoroutineContext

/**
 * "Tarayıcı sınaması çözme" bayrağı: bayrak açıkken yapılan isteklerde Cloudflare yakalayıcısı sınama
 * çözücüyü (tarayıcı penceresini) hiç başlatmaz. Sına gibi arka plan denetimleri, eklenti koduyla
 * yaptıkları isteklerle kullanıcıya pencere açmasın diye kullanır. Eklenti isteklerinin başlığına
 * dokunulamadığı için bayrak başlıkla değil bağlamla taşınır:
 *
 * - [context] coroutine bağlamına eklenir; bağlam hangi iş parçacığında çalışırsa bayrak orada açılır.
 * - NiceHttp istekleri `Call.enqueue` ile OkHttp'nin kendi iş parçacıklarında yürütür; yakalayıcı o
 *   iş parçacığında bayrağı göremez. [listenerFactory] ise `newCall` sırasında, yani çağıran
 *   iş parçacığında çalışır ve çağrıyı işaretler; yakalayıcı [isMarked] ile işareti okur.
 *
 * Eklentinin kendi sıfırdan kurduğu bir istemci [listenerFactory]'yi taşımaz; yine de eşzamanlı
 * `execute()` çağrılarında bayrak doğrudan iş parçacığından okunur.
 */
object NoSolve {
    /** Bayrağın taşıdığı durum; [sighting] verilmişse çözülmeyen sınamalar oraya bildirilir. */
    class State internal constructor(internal val sighting: ChallengeSighting?)

    private val plain = State(null)
    private val flag = ThreadLocal<State?>()
    private val marked: MutableMap<Call, State> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Bu bağlamda (ve onu devralan alt işlerde) yapılan istekler sınama çözücüyü tetiklemez.
     * [sighting] verilirse, çözülmediği için geçilen Cloudflare sınamaları ona kaydedilir.
     */
    fun context(sighting: ChallengeSighting? = null): ThreadContextElement<State?> =
        flag.asContextElement(if (sighting == null) plain else State(sighting))

    /** Çağıran iş parçacığında bayrak açık mı. */
    fun isActive(): Boolean = flag.get() != null

    /** Ortak HTTP istemcisine takılır: bayrak açıkken oluşturulan çağrıları işaretler. */
    val listenerFactory: EventListener.Factory = EventListener.Factory { call ->
        flag.get()?.let { marked[call] = it }
        EventListener.NONE
    }

    /** [call] için sınama çözümü kapalı mı (iş parçacığı bayrağı ya da `newCall` anındaki işaret). */
    fun isMarked(call: Call): Boolean = isActive() || marked.containsKey(call)

    /**
     * [call] bir Cloudflare sınamasına takıldı ve bayrak yüzünden çözülmedi; bayrağı açan denetim
     * dinliyorsa ([context]'e verilen [ChallengeSighting]) ona bildirilir.
     */
    fun reportChallenge(call: Call) {
        val state = flag.get() ?: marked[call]
        state?.sighting?.record(call.request().url.host)
    }
}

/** Bayrak açıkken görülen (ve çözülmeyip geçilen) Cloudflare sınamasının kaydı; iş parçacıkları arası okunur. */
class ChallengeSighting {
    @Volatile
    var host: String? = null
        private set

    /** Sınama görüldü mü. */
    val seen: Boolean get() = host != null

    internal fun record(host: String) {
        this.host = host
    }
}

/** [NoSolve.context]'i bağlama ekler. */
fun CoroutineContext.withNoSolve(sighting: ChallengeSighting? = null): CoroutineContext = this + NoSolve.context(sighting)
