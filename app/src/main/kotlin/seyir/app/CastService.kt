package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import seyir.app.i18n.I18n
import seyir.core.SeyirLog
import seyir.core.cast.CastDevice
import seyir.core.cast.CastDiscovery
import seyir.core.cast.CastListener
import seyir.core.cast.CastMedia
import seyir.core.cast.CastSession
import seyir.core.cast.CastStatus
import seyir.core.cast.CastTrack
import seyir.core.player.SubtitlePlanner
import seyir.core.player.SubtitleTrack
import seyir.core.relay.StreamKind
import java.util.concurrent.TimeUnit

/** Televizyonda çalan içerik; alt şeritte gösterilir. */
data class CastState(
    val device: String,
    val title: String,
    val status: CastStatus? = null,
    /** Televizyona bağlanılıyor ya da video yükleniyor. */
    val connecting: Boolean = true,
)

/**
 * "Televizyonda oynat": bağlantı aktarıcının yerel ağ kapısına kaydedilir, Google TV/Chromecast
 * videoyu bu bilgisayardan çeker (sitenin istediği başlıklar ve engel aşma burada kalır).
 * Konum televizyondan okunur ve geçmişe yazılır; kumandayla yapılan duraklatma ve sarma da yansır.
 */
class CastService(private val controller: AppController) {
    val devices = mutableStateListOf<CastDevice>()
    var scanning by mutableStateOf(false)
        private set
    var state by mutableStateOf<CastState?>(null)
        private set

    private var session: CastSession? = null
    private var lastScan = 0L

    /** Bu bilgisayardaki ağ adreslerine giden, vekilsiz istemci (yalnız oynatma listesini okumak için). */
    private val local = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).callTimeout(15, TimeUnit.SECONDS).build()

    /** Ağdaki alıcıları arar; son arama yeniyse ve bir şey bulduysa tekrarlamaz. */
    fun scan(force: Boolean = false) {
        if (scanning) return
        if (!force && devices.isNotEmpty() && System.currentTimeMillis() - lastScan < RESCAN_MS) return
        scanning = true
        controller.scope.launch(Dispatchers.IO) {
            val found = runCatching { CastDiscovery.scan() }
                .onFailure { SeyirLog.w(TAG, "Televizyon aranamadı", it) }
                .getOrDefault(emptyList())
                .toMutableList()
            val manual = controller.settings.castAddress.trim()
            if (manual.isNotEmpty() && found.none { it.host == manual }) {
                found += CastDevice(I18n.current.tvDeviceName(manual), manual)
            }
            SeyirLog.i(TAG, "Bulunan alıcılar: ${found.joinToString { "${it.name} ${it.host}" }.ifEmpty { "yok" }}")
            lastScan = System.currentTimeMillis()
            devices.clear()
            devices.addAll(found)
            scanning = false
        }
    }

    fun start(device: CastDevice, context: PlayContext, link: ExtractorLink, subtitles: List<SubtitleFile>) {
        val playback = controller.playback
        playback.lastError = null
        playback.stop()
        stop()
        val title = playback.titleOf(context)
        val start = playback.resumePosition(context)
        state = CastState(device.name, title)
        SeyirLog.i(TAG, "Televizyona gönderiliyor: $title -> ${device.name} ${device.host} (başlangıç ${start.toInt()} sn)")

        controller.scope.launch(Dispatchers.IO) {
            var opened: CastSession? = null
            runCatching {
                val listener = object : CastListener {
                    private var lastSaved = 0L

                    override fun onStatus(status: CastStatus) {
                        if (session !== opened) return
                        state = state?.copy(status = status, connecting = status.state == "BUFFERING" && status.position <= start + 1)
                        val now = System.currentTimeMillis()
                        if (status.state == "PLAYING" && now - lastSaved >= SAVE_INTERVAL_MS) {
                            lastSaved = now
                            playback.record(context, status.position, status.duration, finished = false)
                        }
                    }

                    override fun onEnd(status: CastStatus, finished: Boolean, error: String?) {
                        if (status.position > 0) playback.record(context, status.position, status.duration, finished)
                        if (session === opened) {
                            session = null
                            state = null
                            // Bitti ya da koptu: ağdaki kapı açık kalmasın
                            playback.relay.stopLan()
                            if (error != null) playback.lastError = error
                        }
                    }
                }
                val cast = CastSession.open(device, listener)
                opened = cast
                session = cast
                // Televizyon aktarıcıya, bu bağlantının çıktığı ağ arayüzünden ulaşır
                val lanHost = cast.localAddress
                val url = playback.localUrl(link, context.provider, lanHost)
                val referer = link.referer.ifBlank { null }
                val planned = SubtitlePlanner.plan(
                    subtitles.map { SubtitleTrack(it.url, it.lang, it.langTag) },
                    controller.settings.subtitleLang,
                )
                val tracks = planned.map { item ->
                    val sub = subtitles.first { it.url == item.track.url }
                    CastTrack(
                        url = playback.relay.registerSubtitle(sub.url, sub.headers ?: emptyMap(), referer, lanHost),
                        name = sub.lang,
                        lang = SubtitlePlanner.normalizeLang(sub.langTag),
                    )
                }
                val kind = playback.kindOf(link)
                cast.load(
                    CastMedia(
                        url = url,
                        contentType = contentType(kind, link.url),
                        title = context.showName,
                        subtitle = context.item.title.takeIf { it != context.showName },
                        imageUrl = context.posterUrl,
                        startSeconds = start,
                        tracks = tracks,
                        activeTrack = if (tracks.isNotEmpty()) 0 else null,
                        fmp4 = kind == StreamKind.HLS && isFmp4(url),
                    )
                )
                playback.record(context, start, null, finished = false)
                state = state?.copy(connecting = false)
            }.onFailure {
                SeyirLog.w(TAG, "Televizyonda oynatılamadı", it)
                runCatching { opened?.close() }
                if (session === opened) {
                    session = null
                    state = null
                    playback.relay.stopLan()
                    playback.lastError = I18n.current.castFailed(it.message ?: it::class.simpleName.orEmpty())
                } else if (session == null) {
                    // Bağlanırken kullanıcı durdurdu; kayıt sırasında yeniden açılmış olabilecek kapıyı kapat
                    playback.relay.stopLan()
                }
            }
        }
    }

    fun togglePause() {
        val cast = session ?: return
        if (state?.status?.state == "PAUSED") cast.play() else cast.pause()
    }

    fun seekBy(seconds: Double) {
        val cast = session ?: return
        val status = state?.status ?: return
        val target = (status.position + seconds).let { t -> status.duration?.let { t.coerceAtMost(it - 1) } ?: t }
        cast.seek(target)
    }

    /** Televizyondaki oynatmayı durdurur (çalan bir şey yoksa bir şey yapmaz). */
    fun stop() {
        // Hiçbir şey çalmıyorsa da ağ kapısı kapanır (kapalıyken zararsızdır)
        controller.playback.relay.stopLan()
        val cast = session ?: return
        session = null
        state = null
        Thread.ofVirtual().name("cast-stop").start { runCatching { cast.stop() } }
    }

    private fun contentType(kind: StreamKind, url: String): String = when (kind) {
        StreamKind.HLS -> "application/x-mpegurl"
        StreamKind.DASH -> "application/dash+xml"
        StreamKind.FILE -> when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    /**
     * Alıcının oynatıcısı TS ve fMP4 parçalarını farklı ayarla açar; ilk oynatma listesine bakılır.
     * Ana listeyse ilk kalitenin listesi okunur. Okunamazsa TS varsayılır.
     */
    private fun isFmp4(url: String): Boolean = runCatching {
        fun read(u: String) = local.newCall(Request.Builder().url(u).build()).execute().use { it.body.string() }
        val first = read(url)
        val media = if ("#EXT-X-STREAM-INF" in first) {
            first.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()?.let(::read) ?: first
        } else {
            first
        }
        "#EXT-X-MAP" in media
    }.getOrDefault(false)

    private companion object {
        const val TAG = "CastService"
        const val RESCAN_MS = 30_000L
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
