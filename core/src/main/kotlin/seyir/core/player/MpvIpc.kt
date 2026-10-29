package seyir.core.player

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.UUID

/** mpv IPC'sinden gelen bir satır. */
sealed interface MpvMessage

/** Bir komutun yanıtı. [error] başarıda "success"tir. */
data class MpvResponse(val requestId: Long, val error: String, val data: JsonNode?) : MpvMessage {
    val isSuccess: Boolean get() = error == "success"
}

/** mpv'nin kendiliğinden gönderdiği olay (`file-loaded`, `end-file`...). */
data class MpvEvent(val name: String, val fields: JsonNode) : MpvMessage {
    /** `end-file` olayının nedeni ("eof", "stop", "quit", "error"...). */
    val reason: String? get() = fields.get("reason")?.asText()

    /** `start-file`/`end-file` olaylarındaki çalma listesi girdisi kimliği (hangi dosyaya ait olduğu). */
    val entryId: Long? get() = fields.get("playlist_entry_id")?.takeIf { it.isIntegralNumber }?.asLong()
}

/** mpv'nin JSON satırlarını ayrıştırır. */
object MpvMessageParser {
    private val mapper = jacksonObjectMapper()

    /** Çözülemeyen ya da tanınmayan satır için null döner (hata fırlatmaz). */
    fun parse(line: String): MpvMessage? {
        val text = line.trim()
        if (text.isEmpty() || text[0] != '{') return null
        val node = try {
            mapper.readTree(text)
        } catch (_: Exception) {
            return null
        }
        if (node == null || !node.isObject) return null
        node.get("event")?.takeIf { it.isTextual }?.let { return MpvEvent(it.asText(), node) }
        val id = node.get("request_id")
        if (id != null && id.isIntegralNumber) {
            return MpvResponse(id.asLong(), node.get("error")?.asText() ?: "unknown", node.get("data"))
        }
        return null
    }

    internal fun encodeCommand(requestId: Long, command: List<Any?>): String =
        mapper.writeValueAsString(mapOf("command" to command, "request_id" to requestId))
}

/** IPC kanalı: bayt okuma/yazma. Windows'ta adlandırılmış boru, Linux'ta Unix soketi. */
interface IpcTransport : Closeable {
    /** En az bir bayt gelene kadar bekler; akış kapanmışsa -1 döner. */
    fun read(buffer: ByteArray): Int

    fun write(bytes: ByteArray)
}

/**
 * Satır tabanlı, SENKRON mpv IPC istemcisi.
 *
 * Windows'ta eşzamanlı boru G/Ç'si aynı tutamaçta okuma ve yazmayı birbirine kilitlediği için
 * bu sınıf hiçbir zaman eşzamanlı okuma+yazma yapmaz: komutu yazar, sonra yanıt satırını
 * (request_id eşleşene kadar) okur. Arada gelen olay satırları [onEvent]'e verilir.
 * Tek bir iş parçacığından kullanılmalıdır.
 */
class MpvIpcClient(
    private val transport: IpcTransport,
    private val onEvent: (MpvEvent) -> Unit = {},
) : Closeable {
    private var nextId = 1L
    private val pending = ByteArrayOutputStream()
    private val chunk = ByteArray(4096)
    private var closed = false

    /** Komutu gönderir ve yanıtını döndürür. Bağlantı koparsa [IOException] fırlatır. */
    fun request(command: List<Any?>): MpvResponse {
        val id = nextId++
        transport.write((MpvMessageParser.encodeCommand(id, command) + "\n").toByteArray(Charsets.UTF_8))
        while (true) {
            val line = readLine() ?: throw IOException("mpv IPC bağlantısı kapandı")
            when (val message = MpvMessageParser.parse(line)) {
                is MpvResponse -> if (message.requestId == id) return message
                is MpvEvent -> onEvent(message)
                null -> Unit
            }
        }
    }

    /** `get_property` kısayolu; özellik yoksa/kullanılamıyorsa null. */
    fun getDouble(name: String): Double? {
        val response = request(listOf("get_property", name))
        val data = response.data
        return if (response.isSuccess && data != null && data.isNumber) data.asDouble() else null
    }

    /** `get_property` kısayolu; özellik yoksa/kullanılamıyorsa null. */
    fun getProperty(name: String): JsonNode? {
        val response = request(listOf("get_property", name))
        return if (response.isSuccess) response.data else null
    }

    fun getBoolean(name: String): Boolean? = getProperty(name)?.takeIf { it.isBoolean }?.asBoolean()

    fun getLong(name: String): Long? = getProperty(name)?.takeIf { it.isIntegralNumber }?.asLong()

    private fun readLine(): String? {
        while (true) {
            val bytes = pending.toByteArray()
            val newline = bytes.indexOf('\n'.code.toByte())
            if (newline >= 0) {
                pending.reset()
                pending.write(bytes, newline + 1, bytes.size - newline - 1)
                return String(bytes, 0, newline, Charsets.UTF_8)
            }
            val count = transport.read(chunk)
            if (count < 0) return null
            pending.write(chunk, 0, count)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            transport.close()
        } catch (_: IOException) {
        }
    }
}

/** IPC kanalı oluşturma yardımcıları. */
internal object MpvIpcTransports {
    /** mpv'ye `--input-ipc-server` olarak verilecek benzersiz yol. */
    fun newEndpoint(windows: Boolean, socketDir: File? = null): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        if (windows) return "\\\\.\\pipe\\seyir-mpv-$suffix"
        val dir = socketDir
            ?: File(System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: "/tmp")
        dir.mkdirs()
        return File(dir, "seyir-mpv-$suffix.sock").absolutePath
    }

    /** mpv kanalı henüz oluşturmadıysa [IOException] fırlatır; çağıran yeniden dener. */
    fun open(endpoint: String, windows: Boolean): IpcTransport =
        if (windows) WindowsPipeTransport(endpoint) else UnixSocketTransport(endpoint)
}

/** Windows adlandırılmış borusu; `RandomAccessFile` ile açılır. */
internal class WindowsPipeTransport(path: String) : IpcTransport {
    private val file = RandomAccessFile(path, "rw")

    override fun read(buffer: ByteArray): Int = file.read(buffer)

    override fun write(bytes: ByteArray) = file.write(bytes)

    override fun close() = file.close()
}

/** Linux Unix alan soketi. */
internal class UnixSocketTransport(path: String) : IpcTransport {
    private val channel: SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).also {
        try {
            it.connect(UnixDomainSocketAddress.of(path))
        } catch (e: IOException) {
            it.close()
            throw e
        }
    }

    override fun read(buffer: ByteArray): Int = channel.read(ByteBuffer.wrap(buffer))

    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun close() = channel.close()
}
