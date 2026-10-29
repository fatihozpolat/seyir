package seyir.core.cast

import com.fasterxml.jackson.databind.JsonNode
import seyir.core.SeyirLog
import java.io.IOException

/** Televizyona gönderilen altyazı (WebVTT, aktarıcıdan). */
data class CastTrack(val url: String, val name: String, val lang: String?)

/** Televizyonda açılacak içerik. */
data class CastMedia(
    val url: String,
    /** `application/x-mpegurl`, `application/dash+xml`, `video/mp4` … */
    val contentType: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val startSeconds: Double = 0.0,
    val tracks: List<CastTrack> = emptyList(),
    /** Açılışta gösterilecek altyazının [tracks] içindeki sırası. */
    val activeTrack: Int? = null,
    /** HLS parçaları fMP4 ise true; TS için alıcının varsayılanı kullanılır. */
    val fmp4: Boolean = false,
)

/** Oynatma durumu; alıcının MEDIA_STATUS mesajlarından güncellenir. */
data class CastStatus(
    val position: Double = 0.0,
    val duration: Double? = null,
    /** PLAYING, PAUSED, BUFFERING, IDLE */
    val state: String = "BUFFERING",
    val volume: Double? = null,
)

interface CastListener {
    fun onStatus(status: CastStatus)

    /** Oturum bitti: video sona erdi, televizyonda kapatıldı ya da bağlantı koptu. */
    fun onEnd(status: CastStatus, finished: Boolean, error: String?)
}

/**
 * Google TV/Chromecast'te Google'ın Varsayılan Medya Alıcısı'nı (CC1AD845) açıp video oynatır.
 * Konum alıcıdan iki saniyede bir sorulur; televizyon kumandasıyla yapılan duraklatma ve
 * sarma da bu yoldan buraya yansır.
 */
class CastSession private constructor(
    val device: CastDevice,
    private val connection: CastConnection,
    private val listener: CastListener,
) : AutoCloseable {
    @Volatile
    private var transportId: String? = null

    @Volatile
    private var mediaSessionId: Int? = null

    @Volatile
    var status = CastStatus()
        private set

    @Volatile
    private var ended = false

    /** Bu bilgisayarın televizyona dönük adresi; aktarıcı adresleri bununla kurulur. */
    val localAddress: String get() = connection.localAddress

    /** Alıcıyı açar; [load] çağrılmadan video oynamaz. */
    private fun launch() {
        connection.onMessage = ::handleMessage
        connection.onClosed = { finish(false, if (ended) null else "Televizyonla bağlantı koptu") }
        val response = connection.request(
            CastConnection.NS_RECEIVER,
            mapOf("type" to "LAUNCH", "appId" to DEFAULT_RECEIVER),
            CastConnection.RECEIVER,
            timeoutMs = 30_000,
        )
        val app = response.path("status").path("applications").firstOrNull { it.path("appId").asText() == DEFAULT_RECEIVER }
            ?: throw IOException("Televizyon oynatıcıyı açmadı (${response.path("type").asText()} ${response.path("reason").asText()})")
        val transport = app.path("transportId").asText()
        transportId = transport
        connection.send(CastConnection.NS_CONNECTION, mapOf("type" to "CONNECT"), transport)
    }

    fun load(media: CastMedia) {
        val transport = transportId ?: throw IOException("Oynatıcı açık değil")
        val metadata = buildMap<String, Any?> {
            put("metadataType", 0)
            put("title", media.title)
            media.subtitle?.let { put("subtitle", it) }
            media.imageUrl?.let { put("images", listOf(mapOf("url" to it))) }
        }
        val mediaInfo = buildMap<String, Any?> {
            put("contentId", media.url)
            put("contentUrl", media.url)
            put("contentType", media.contentType)
            put("streamType", "BUFFERED")
            put("metadata", metadata)
            if (media.fmp4) {
                put("hlsSegmentFormat", "fmp4")
                put("hlsVideoSegmentFormat", "fmp4")
            }
            if (media.tracks.isNotEmpty()) {
                put("tracks", media.tracks.mapIndexed { index, track ->
                    buildMap {
                        put("trackId", index + 1)
                        put("type", "TEXT")
                        put("subtype", "SUBTITLES")
                        put("trackContentId", track.url)
                        put("trackContentType", "text/vtt")
                        put("name", track.name)
                        track.lang?.let { put("language", it) }
                    }
                })
                put("textTrackStyle", mapOf("backgroundColor" to "#00000000", "edgeType" to "OUTLINE", "edgeColor" to "#000000FF"))
            }
        }
        val response = connection.request(
            CastConnection.NS_MEDIA,
            buildMap {
                put("type", "LOAD")
                put("media", mediaInfo)
                put("autoplay", true)
                put("currentTime", media.startSeconds)
                media.activeTrack?.let { put("activeTrackIds", listOf(it + 1)) }
            },
            transport,
            timeoutMs = 60_000,
        )
        when (response.path("type").asText()) {
            "MEDIA_STATUS" -> handleMediaStatus(response)
            else -> throw IOException("Televizyon videoyu açamadı (${response.path("type").asText()} ${response.path("detailedErrorCode").asText("")})".trim())
        }
        Thread.ofVirtual().name("cast-poll").start { poll() }
    }

    fun play() = mediaCommand("PLAY")

    fun pause() = mediaCommand("PAUSE")

    fun seek(seconds: Double) = mediaCommand("SEEK", mapOf("currentTime" to seconds.coerceAtLeast(0.0)))

    /** Videoyu durdurur ve televizyondaki oynatıcıyı kapatır. */
    fun stop() {
        runCatching { mediaCommand("STOP") }
        runCatching {
            connection.send(CastConnection.NS_RECEIVER, mapOf("type" to "STOP", "requestId" to 0), CastConnection.RECEIVER)
        }
        finish(false, null)
    }

    override fun close() = stop()

    private fun mediaCommand(type: String, extra: Map<String, Any?> = emptyMap()) {
        val transport = transportId ?: return
        val session = mediaSessionId ?: return
        Thread.ofVirtual().name("cast-cmd").start {
            runCatching {
                val response = connection.request(
                    CastConnection.NS_MEDIA,
                    mapOf("type" to type, "mediaSessionId" to session) + extra,
                    transport,
                )
                if (response.path("type").asText() == "MEDIA_STATUS") handleMediaStatus(response)
            }.onFailure { SeyirLog.d(TAG, "$type gönderilemedi: ${it.message}") }
        }
    }

    private fun poll() {
        while (!ended) {
            Thread.sleep(POLL_MS)
            val transport = transportId ?: continue
            runCatching {
                val response = connection.request(
                    CastConnection.NS_MEDIA,
                    buildMap {
                        put("type", "GET_STATUS")
                        mediaSessionId?.let { put("mediaSessionId", it) }
                    },
                    transport,
                )
                handleMessage(CastConnection.NS_MEDIA, transport, response)
            }.onFailure { if (!ended) SeyirLog.d(TAG, "Durum alınamadı: ${it.message}") }
        }
    }

    private fun handleMessage(namespace: String, source: String, payload: JsonNode) {
        when (payload.path("type").asText()) {
            "MEDIA_STATUS" -> handleMediaStatus(payload)
            "RECEIVER_STATUS" -> {
                // Oynatıcı kapatıldıysa (televizyonda Geri ya da başka uygulama) oturum biter
                val apps = payload.path("status").path("applications")
                val volume = payload.path("status").path("volume").path("level").takeIf { it.isNumber }?.asDouble()
                if (volume != null) update(status.copy(volume = volume))
                if (transportId != null && apps.none { it.path("transportId").asText() == transportId }) finish(false, null)
            }
            "CLOSE" -> if (namespace == CastConnection.NS_CONNECTION && source == transportId) finish(false, null)
            "LOAD_FAILED" -> finish(false, "Televizyon videoyu oynatamadı")
        }
    }

    private fun handleMediaStatus(payload: JsonNode) {
        val entry = payload.path("status").firstOrNull() ?: run {
            // Boş durum: oynatıcı medyayı bıraktı
            if (mediaSessionId != null) finish(false, null)
            return
        }
        entry.path("mediaSessionId").takeIf { it.isInt }?.let { mediaSessionId = it.asInt() }
        val duration = entry.path("media").path("duration").takeIf { it.isNumber }?.asDouble() ?: status.duration
        val state = entry.path("playerState").asText(status.state)
        val position = entry.path("currentTime").takeIf { it.isNumber }?.asDouble() ?: status.position
        update(status.copy(position = position, duration = duration, state = state))
        if (state == "IDLE") {
            when (entry.path("idleReason").asText()) {
                "FINISHED" -> finish(true, null)
                "ERROR" -> finish(false, "Televizyon videoyu oynatamadı")
                "CANCELLED", "INTERRUPTED" -> finish(false, null)
            }
        }
    }

    private fun update(next: CastStatus) {
        if (next == status) return
        status = next
        if (!ended) listener.onStatus(next)
    }

    @Synchronized
    private fun finish(finished: Boolean, error: String?) {
        if (ended) return
        ended = true
        SeyirLog.i(TAG, "${device.name}: oturum bitti (konum ${status.position.toInt()} sn, sona erdi=$finished, hata=$error)")
        runCatching { connection.close() }
        listener.onEnd(status, finished, error)
    }

    companion object {
        private const val TAG = "CastSession"
        private const val DEFAULT_RECEIVER = "CC1AD845"
        private const val POLL_MS = 2_000L

        /** Alıcıya bağlanır ve oynatıcıyı açar; videoyu [load] başlatır. */
        fun open(device: CastDevice, listener: CastListener): CastSession = open(CastConnection(device), device, listener)

        internal fun open(connection: CastConnection, device: CastDevice, listener: CastListener): CastSession {
            connection.connect()
            val session = CastSession(device, connection, listener)
            try {
                session.launch()
            } catch (e: Exception) {
                connection.close()
                throw e
            }
            return session
        }
    }
}
