package seyir.core.player

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** mpv'deki bir ses, altyazı ya da görüntü izi (`track-list` öğesi). */
data class MediaTrack(
    val id: Int,
    /** "audio", "sub" ya da "video". */
    val type: String,
    val title: String?,
    val lang: String?,
    val selected: Boolean,
    val external: Boolean = false,
    val codec: String? = null,
    val channels: Int? = null,
)

/**
 * HLS kalite katmanı. mpv HLS ana listesinin katmanlarını görüntü izi olarak değil "edition" (sürüm) olarak sunar;
 * [editionId] o sürümün kimliğidir. Aynı yükseklikteki katmanlardan yalnız en yüksek bit hızlısı tutulur.
 */
data class QualityLayer(val editionId: Int, val height: Int, val fps: Double?, val kbps: Double) {
    /** "1080p", 50 fps ve üstünde "1080p60". */
    val label: String
        get() = "${height}p" + (fps?.takeIf { it >= 49 }?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "")
}

/**
 * Gömülü oynatıcının o anki durumu (uygulama kendi denetimlerini bununla çizer).
 *
 * @property loaded Dosya açıldı ve oynatılabilir; açılırken ve bölümler arasında false.
 * @property waitingForCache Önbellek doluncaya kadar duraklatıldı.
 * @property seeking İleri/geri sarma sürüyor.
 * @property cachedUntil Önbellekte hazır olan son konum (saniye); bilinmiyorsa null.
 * @property qualities Seçilebilir kalite katmanları (yüksekten alçağa); tek katmanlı yayında boş.
 */
data class PlayerState(
    val loaded: Boolean = false,
    val position: Double? = null,
    val duration: Double? = null,
    val paused: Boolean = false,
    val waitingForCache: Boolean = false,
    val seeking: Boolean = false,
    val cachedUntil: Double? = null,
    val volume: Double = 100.0,
    val muted: Boolean = false,
    val speed: Double = 1.0,
    val tracks: List<MediaTrack> = emptyList(),
    val qualities: List<QualityLayer> = emptyList(),
    val currentEdition: Int? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
) {
    /** Önbellek ya da sarma bekleniyor (dönen gösterge). */
    val buffering: Boolean get() = waitingForCache || seeking

    val audioTracks: List<MediaTrack> get() = tracks.filter { it.type == "audio" }
    val subtitleTracks: List<MediaTrack> get() = tracks.filter { it.type == "sub" }

    /** Şu an oynayan kalite katmanı; sürüm bilgisi yoksa görüntü yüksekliğine en yakın olan. */
    val currentQuality: QualityLayer?
        get() = qualities.firstOrNull { it.editionId == currentEdition }
            ?: videoHeight?.let { h -> qualities.minByOrNull { kotlin.math.abs(it.height - h) } }
}

/** Uygulamanın gömülü oynatıcıya gönderdiği komut; oynatıcının izleme iş parçacığında sırayla uygulanır. */
sealed interface ControlCommand {
    data class SetPaused(val paused: Boolean) : ControlCommand
    data object TogglePause : ControlCommand
    data class SeekTo(val seconds: Double) : ControlCommand
    data class SeekBy(val seconds: Double) : ControlCommand
    data class SetVolume(val volume: Double) : ControlCommand
    data class SetMuted(val muted: Boolean) : ControlCommand
    data class SetSpeed(val speed: Double) : ControlCommand

    /** [id] null ise altyazı kapanır. */
    data class SelectSubtitle(val id: Int?) : ControlCommand
    data class SelectAudio(val id: Int) : ControlCommand

    /** Kalite katmanını değiştirir; video aynı konumdan sürer. */
    data class SelectQuality(val editionId: Int) : ControlCommand

    /** Altyazının alt kenardan uzaklığı (mpv'nin 720 satırlık ölçeğinde); denetimler açıkken altyazı yukarı kayar. */
    data class SetSubtitleMargin(val margin: Int) : ControlCommand

    /** Altyazı boyutu çarpanı (1.0 varsayılan). */
    data class SetSubtitleScale(val scale: Double) : ControlCommand
}

/**
 * Gömülü oynatıcının denetimi: durum akışı ([state]) ve komutlar. Komutlar her iş parçacığından gönderilebilir;
 * açık mpv süreci yoksa atılır. Durum, süreç değiştikçe (yeni bölüm, yeni süreç) aynı nesnede güncellenir.
 */
class PlayerControls internal constructor() {
    private val mutableState = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    internal val queue = ConcurrentLinkedQueue<ControlCommand>()

    /** Denetimin bağlı olduğu mpv süreci (yalnız o süreç komut okur ve durumu sıfırlar). */
    @Volatile
    internal var owner: Any? = null

    /** Komut gelince izleme döngüsünü uyandırır; süreç yokken null. */
    @Volatile
    internal var wake: (() -> Unit)? = null

    fun send(command: ControlCommand) {
        val wakeUp = wake ?: return
        queue.add(command)
        // Arayüz hemen tepki versin; mpv'nin gerçek değeri bir sonraki ölçümde gelir
        optimistic(command)
        wakeUp()
    }

    fun togglePause() = send(ControlCommand.TogglePause)
    fun seekTo(seconds: Double) = send(ControlCommand.SeekTo(seconds))
    fun seekBy(seconds: Double) = send(ControlCommand.SeekBy(seconds))
    fun setVolume(volume: Double) = send(ControlCommand.SetVolume(volume.coerceIn(0.0, MAX_VOLUME)))
    fun setMuted(muted: Boolean) = send(ControlCommand.SetMuted(muted))
    fun setSpeed(speed: Double) = send(ControlCommand.SetSpeed(speed))
    fun selectSubtitle(id: Int?) = send(ControlCommand.SelectSubtitle(id))
    fun selectAudio(id: Int) = send(ControlCommand.SelectAudio(id))
    fun selectQuality(editionId: Int) = send(ControlCommand.SelectQuality(editionId))
    fun setSubtitleMargin(margin: Int) {
        // Süreç henüz açılmadıysa açılınca uygulanır
        subtitleMargin = margin
        send(ControlCommand.SetSubtitleMargin(margin))
    }

    /** Son istenen altyazı payı; yeni mpv süreci açılınca ona da uygulanır. */
    @Volatile
    internal var subtitleMargin: Int? = null
    fun setSubtitleScale(scale: Double) = send(ControlCommand.SetSubtitleScale(scale))

    private fun optimistic(command: ControlCommand) = mutableState.update { s ->
        when (command) {
            is ControlCommand.SetPaused -> s.copy(paused = command.paused)
            ControlCommand.TogglePause -> s.copy(paused = !s.paused)
            is ControlCommand.SeekTo -> s.copy(position = command.seconds.coerceAtLeast(0.0))
            is ControlCommand.SeekBy -> s.position?.let { s.copy(position = (it + command.seconds).coerceAtLeast(0.0)) } ?: s
            is ControlCommand.SetVolume -> s.copy(volume = command.volume, muted = false)
            is ControlCommand.SetMuted -> s.copy(muted = command.muted)
            is ControlCommand.SetSpeed -> s.copy(speed = command.speed)
            is ControlCommand.SelectSubtitle -> s.copy(tracks = s.tracks.map { if (it.type == "sub") it.copy(selected = it.id == command.id) else it })
            is ControlCommand.SelectAudio -> s.copy(tracks = s.tracks.map { if (it.type == "audio") it.copy(selected = it.id == command.id) else it })
            is ControlCommand.SelectQuality -> s.copy(currentEdition = command.editionId)
            is ControlCommand.SetSubtitleMargin, is ControlCommand.SetSubtitleScale -> s
        }
    }

    internal fun update(transform: (PlayerState) -> PlayerState) = mutableState.update(transform)

    /** Süreç kapandı: kullanıcının ses ayarı korunur, gerisi sıfırlanır. */
    internal fun reset() {
        queue.clear()
        mutableState.update { PlayerState(volume = it.volume, muted = it.muted) }
    }

    companion object {
        const val MAX_VOLUME = 130.0
    }
}

/** mpv özelliklerini [PlayerState]'e çeviren saf yardımcılar (mpv olmadan sınanır). */
internal object PlayerStateMapping {
    /** Değişikliği izlenen özellikler; sürekli değişen konum ve önbellek ölçülerek okunur. */
    val OBSERVED = listOf(
        "pause", "paused-for-cache", "seeking", "volume", "mute", "speed", "duration",
        "track-list", "edition-list", "current-edition", "width", "height",
    )

    fun apply(state: PlayerState, name: String, data: JsonNode?): PlayerState = when (name) {
        "pause" -> data?.takeIf { it.isBoolean }?.let { state.copy(paused = it.asBoolean()) } ?: state
        "paused-for-cache" -> state.copy(waitingForCache = data?.asBoolean() == true)
        "seeking" -> state.copy(seeking = data?.asBoolean() == true)
        "volume" -> data?.takeIf { it.isNumber }?.let { state.copy(volume = it.asDouble()) } ?: state
        "mute" -> data?.takeIf { it.isBoolean }?.let { state.copy(muted = it.asBoolean()) } ?: state
        "speed" -> data?.takeIf { it.isNumber }?.let { state.copy(speed = it.asDouble()) } ?: state
        "duration" -> state.copy(duration = data?.takeIf { it.isNumber }?.asDouble()?.takeIf { it > 0 })
        "track-list" -> state.copy(tracks = tracks(data))
        "edition-list" -> state.copy(qualities = qualities(data))
        "current-edition" -> state.copy(currentEdition = data?.takeIf { it.isIntegralNumber }?.asInt())
        "width" -> state.copy(videoWidth = data?.takeIf { it.isIntegralNumber }?.asInt())
        "height" -> state.copy(videoHeight = data?.takeIf { it.isIntegralNumber }?.asInt())
        else -> state
    }

    fun tracks(data: JsonNode?): List<MediaTrack> {
        if (data == null || !data.isArray) return emptyList()
        return data.mapNotNull { node ->
            val id = node.get("id")?.takeIf { it.isIntegralNumber }?.asInt() ?: return@mapNotNull null
            val type = node.get("type")?.asText() ?: return@mapNotNull null
            // Kapak görselleri iz sayılmaz
            if (type == "video" && (node.get("albumart")?.asBoolean() == true || node.get("image")?.asBoolean() == true)) return@mapNotNull null
            MediaTrack(
                id = id,
                type = type,
                title = node.get("title")?.asText()?.takeIf { it.isNotBlank() },
                lang = node.get("lang")?.asText()?.takeIf { it.isNotBlank() },
                selected = node.get("selected")?.asBoolean() == true,
                external = node.get("external")?.asBoolean() == true,
                codec = node.get("codec")?.asText()?.takeIf { it.isNotBlank() },
                channels = node.get("demux-channel-count")?.takeIf { it.isIntegralNumber }?.asInt(),
            )
        }
    }

    private val resolution = Regex("""(?<!\d)(\d+)x(\d+)(?!\d)""")
    private val bitrate = Regex("""Bitrate:\s*([\d.]+)\s*([MKk])bps""")
    private val framerate = Regex("""([\d.]+)\s*fps""")

    /**
     * `edition-list` öğelerinin başlığından ("Bitrate: 2.149 Mbps (h264 [High] 1280x720 60 fps 2149 kbps) ...")
     * katmanları çıkarır. Çözünürlüğü olmayan sürümler (yalnız ses, Matroska sürümleri) katman sayılmaz; aynı
     * yükseklikte en yüksek bit hızlısı kalır. Birden az katman varsa boş liste döner.
     */
    fun qualities(data: JsonNode?): List<QualityLayer> {
        if (data == null || !data.isArray || data.size() < 2) return emptyList()
        val layers = data.mapNotNull { node ->
            val id = node.get("id")?.takeIf { it.isIntegralNumber }?.asInt() ?: return@mapNotNull null
            val title = node.get("title")?.asText().orEmpty()
            val height = resolution.find(title)?.groupValues?.get(2)?.toIntOrNull() ?: return@mapNotNull null
            val kbps = bitrate.find(title)?.let { m ->
                val number = m.groupValues[1].toDoubleOrNull() ?: 0.0
                if (m.groupValues[2] == "M") number * 1000 else number
            } ?: 0.0
            QualityLayer(id, height, framerate.find(title)?.groupValues?.get(1)?.toDoubleOrNull(), kbps)
        }
        val best = layers.groupBy { it.height }.map { (_, group) -> group.maxBy { it.kbps } }.sortedByDescending { it.height }
        return if (best.size > 1) best else emptyList()
    }
}
