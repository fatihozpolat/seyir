package seyir.core.browser

import com.fasterxml.jackson.databind.JsonNode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** CDP komutu başarısız olduğunda ya da bağlantı koptuğunda fırlatılır. */
class CdpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Tek bir Chrome DevTools Protocol WebSocket bağlantısı. Komutlar eşzamanlı çağrılabilir;
 * her biri kendi `id`'siyle eşleşen yanıtı bekler. Olaylar bu bileşende kullanılmaz, yok sayılır.
 */
class CdpConnection(url: String, client: OkHttpClient) : AutoCloseable {
    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonNode?>>()
    private val opened = CompletableFuture<Unit>()

    @Volatile
    private var closeReason: String? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = CdpMessages.parse(text) as? CdpMessage.Response ?: return
            val future = pending.remove(message.id) ?: return
            val error = message.error
            if (error != null) {
                future.completeExceptionally(CdpException("CDP hatası (${error.code}): ${error.message}"))
            } else {
                future.complete(message.result)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail("Bağlantı kapandı ($code $reason)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail("Bağlantı koptu: ${t.message}", t)
        }
    }

    private val socket: WebSocket = client.newWebSocket(Request.Builder().url(url).build(), listener)

    private fun fail(reason: String, cause: Throwable? = null) {
        closeReason = reason
        val error = CdpException(reason, cause)
        opened.completeExceptionally(error)
        pending.keys.toList().forEach { pending.remove(it)?.completeExceptionally(error) }
    }

    /** Bağlantı açılana kadar bekler. */
    fun awaitOpen(timeoutMs: Long) {
        await(opened, timeoutMs, "WebSocket açılışı")
    }

    /**
     * [method] komutunu gönderir ve yanıtın `result` alanını döner (yoksa null).
     * [sessionId] verilirse komut `Target.attachToTarget` ile açılmış sayfa oturumuna gider.
     */
    fun call(
        method: String,
        params: Map<String, Any?>? = null,
        sessionId: String? = null,
        timeoutMs: Long = 10_000,
    ): JsonNode? {
        closeReason?.let { throw CdpException(it) }
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JsonNode?>()
        pending[id] = future
        if (!socket.send(CdpMessages.request(id, method, params, sessionId))) {
            pending.remove(id)
            throw CdpException("$method gönderilemedi: bağlantı kapalı")
        }
        try {
            return await(future, timeoutMs, method)
        } finally {
            pending.remove(id)
        }
    }

    private fun <T> await(future: CompletableFuture<T>, timeoutMs: Long, what: String): T {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw CdpException("$what zaman aşımına uğradı ($timeoutMs ms)", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw if (cause is CdpException) cause else CdpException("$what başarısız: ${cause?.message}", cause)
        }
    }

    override fun close() {
        runCatching { socket.close(1000, null) }
        socket.cancel()
    }
}
