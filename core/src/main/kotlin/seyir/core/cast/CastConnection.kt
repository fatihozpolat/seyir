package seyir.core.cast

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import seyir.core.SeyirLog
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Cast alıcısıyla CASTV2 bağlantısı: 8009 portunda TLS, her mesaj 4 baytlık uzunluk ve
 * protobuf `CastMessage`. Mesaj tek ve basit olduğu için protobuf kitaplığı yerine elle kodlanır.
 * Alıcılar kendinden imzalı sertifika kullandığı için sertifika doğrulanmaz (yerel ağ, yalnız
 * oynatma komutu gider).
 */
class CastConnection internal constructor(
    private val device: CastDevice,
    private val openSocket: (CastDevice) -> java.net.Socket,
) : AutoCloseable {
    constructor(device: CastDevice) : this(device, ::openTls)

    /** Alıcıdan gelen, bir isteğe yanıt olmayan mesajlar (ad alanı, gönderen, içerik). */
    var onMessage: (namespace: String, source: String, payload: JsonNode) -> Unit = { _, _, _ -> }

    /** Bağlantı koptuğunda bir kez çağrılır. */
    var onClosed: (IOException?) -> Unit = {}

    private val mapper = jacksonObjectMapper()
    private val requestIds = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonNode>>()
    private lateinit var socket: java.net.Socket
    private lateinit var output: DataOutputStream

    @Volatile
    private var closed = false

    /** Bağlantının bu bilgisayardaki adresi; televizyonun aktarıcıya ulaşacağı arayüz budur. */
    val localAddress: String get() = socket.localAddress.hostAddress

    fun connect() {
        socket = openSocket(device)
        output = DataOutputStream(socket.getOutputStream())
        // Alıcı ilk mesaj olarak CONNECT bekler; yaşam sinyali ondan sonra başlar
        send(NS_CONNECTION, mapOf("type" to "CONNECT"), RECEIVER)
        Thread.ofVirtual().name("cast-read").start { readLoop() }
        Thread.ofVirtual().name("cast-ping").start { heartbeat() }
    }

    /** Yanıt beklenmeyen mesaj gönderir. */
    fun send(namespace: String, payload: Map<String, Any?>, destination: String) {
        write(SENDER, destination, namespace, mapper.writeValueAsString(payload))
    }

    /** `requestId` ekleyip gönderir ve aynı kimlikli yanıtı bekler. */
    fun request(namespace: String, payload: Map<String, Any?>, destination: String, timeoutMs: Long = 15_000): JsonNode {
        val id = requestIds.getAndIncrement()
        val future = CompletableFuture<JsonNode>()
        pending[id] = future
        try {
            send(namespace, payload + ("requestId" to id), destination)
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IOException("Televizyon yanıt vermedi (${payload["type"]})")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    // ---- çerçeveleme ----

    @Synchronized
    private fun write(source: String, destination: String, namespace: String, json: String) {
        val message = ByteArrayOutputStream()
        fun varint(value: Int) {
            var v = value
            while (v and 0x7F.inv() != 0) {
                message.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            message.write(v)
        }
        fun field(number: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            varint((number shl 3) or 2)
            varint(bytes.size)
            message.write(bytes)
        }
        varint(1 shl 3); varint(0) // protocol_version = CASTV2_1_0
        field(2, source)
        field(3, destination)
        field(4, namespace)
        varint(5 shl 3); varint(0) // payload_type = STRING
        field(6, json)
        val bytes = message.toByteArray()
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private class Decoded(val source: String, val namespace: String, val payload: String?)

    private fun decode(bytes: ByteArray): Decoded {
        var pos = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        var source = ""
        var namespace = ""
        var payload: String? = null
        while (pos < bytes.size) {
            val key = varint().toInt()
            when (key and 7) {
                0 -> varint()
                2 -> {
                    val len = varint().toInt()
                    val text = String(bytes, pos, len, Charsets.UTF_8)
                    pos += len
                    when (key ushr 3) {
                        2 -> source = text
                        4 -> namespace = text
                        6 -> payload = text
                    }
                }
                else -> throw IOException("Beklenmeyen protobuf alan türü: ${key and 7}")
            }
        }
        return Decoded(source, namespace, payload)
    }

    private fun readLoop() {
        var failure: IOException? = null
        try {
            val input = DataInputStream(socket.getInputStream())
            while (!closed) {
                val length = input.readInt()
                if (length !in 0..MAX_MESSAGE_BYTES) throw IOException("Geçersiz mesaj uzunluğu: $length")
                val message = decode(ByteArray(length).also { input.readFully(it) })
                val json = message.payload?.let { runCatching { mapper.readTree(it) }.getOrNull() } ?: continue
                if (message.namespace == NS_HEARTBEAT && json.path("type").asText() == "PING") {
                    send(NS_HEARTBEAT, mapOf("type" to "PONG"), message.source)
                    continue
                }
                val id = json.path("requestId").asInt(0)
                val future = if (id != 0) pending[id] else null
                if (future != null) future.complete(json) else onMessage(message.namespace, message.source, json)
            }
        } catch (e: IOException) {
            if (!closed) failure = e
        } finally {
            closed = true
            runCatching { socket.close() }
            val error = failure ?: IOException("Bağlantı kapandı")
            pending.values.forEach { it.completeExceptionally(error) }
            if (failure != null) SeyirLog.d(TAG, "${device.name}: bağlantı koptu: ${failure.message}")
            onClosed(failure)
        }
    }

    private fun heartbeat() {
        while (!closed) {
            runCatching { send(NS_HEARTBEAT, mapOf("type" to "PING"), RECEIVER) }
            Thread.sleep(HEARTBEAT_MS)
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private fun openTls(device: CastDevice): java.net.Socket {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(TrustAll), SecureRandom())
            val raw = java.net.Socket()
            raw.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
            val tls = context.socketFactory.createSocket(raw, device.host, device.port, true) as SSLSocket
            tls.startHandshake()
            return tls
        }

        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val TAG = "CastConnection"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HEARTBEAT_MS = 5_000L
        private const val MAX_MESSAGE_BYTES = 1 shl 20
    }
}
