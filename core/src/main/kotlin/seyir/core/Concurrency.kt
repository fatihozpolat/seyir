package seyir.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * [block]'u IO'da çalıştırır ve en çok [timeoutMillis] bekler; süre dolarsa `TimeoutCancellationException` fırlar.
 * İş [scope] içinde ayrı başlatıldığı için iptale/zaman aşımına bakmayan (engelleyici) eklenti kodu
 * beklemeyi takılı bırakmaz; iş kendi başına bitene kadar arka planda sürer. İşin hatası çağırana taşınır.
 * [extraContext] işin bağlamına eklenir (ör. [NoSolve.context]).
 */
suspend fun <T> withHardTimeout(
    scope: CoroutineScope,
    timeoutMillis: Long,
    extraContext: CoroutineContext = EmptyCoroutineContext,
    block: suspend () -> T,
): T {
    // Hata Result içinde taşınır: başarısız bir async, üst kapsamı (uygulama kapsamını) iptal etmesin
    val work = scope.async(Dispatchers.IO + extraContext) { runCatching { block() } }
    try {
        return withTimeout(timeoutMillis) { work.await() }.getOrThrow()
    } finally {
        work.cancel()
    }
}
