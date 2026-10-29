package seyir.core.hls

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * HLS ana listesindeki (`#EXT-X-STREAM-INF`) bir katman.
 *
 * @property url Katmanın oynatma listesi adresi; ana liste adresine göre mutlak çözülmüştür
 *   (çözülemezse listedeki ham değer).
 * @property bandwidth BANDWIDTH (bit/sn); mpv'nin `--hls-bitrate` seçimi bu değerle yapılır.
 * @property height RESOLUTION'daki yükseklik; liste vermediyse null.
 * @property audioOnly Katman yalnız ses içeriyor (görüntü codec'i yok, çözünürlük yok).
 */
data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    val frameRate: Double?,
    val audioGroup: String?,
    val audioOnly: Boolean,
) {
    /** Çözünürlük bilinmiyorsa bant genişliğinden tahmin edilen yükseklik. */
    val effectiveHeight: Int get() = height ?: HlsQuality.estimateHeight(bandwidth)

    /** Yükseklik bilgisi bant genişliğinden tahmin edildi mi. */
    val heightEstimated: Boolean get() = height == null

    /** Kullanıcıya gösterilen kısa ad: "1080p", "4K"; tahmin edilmişse "~720p". */
    val label: String
        get() {
            val h = effectiveHeight
            val base = if (h >= 2160) "4K" else "${h}p"
            return if (heightEstimated) "~$base" else base
        }
}

/** HLS ana liste ayrıştırıcısı. Bellekte çalışır, ağa çıkmaz. */
object HlsMasterParser {
    private const val BOM = "﻿"
    private const val STREAM_INF = "#EXT-X-STREAM-INF:"

    private val RESOLUTION = Regex("""(\d+)\s*[xX]\s*(\d+)""")
    private val AUDIO_CODEC_PREFIXES = listOf("mp4a", "ac-3", "ec-3", "ac-4", "opus", "flac", "vorbis", "mp3", "alac", "ipcm")

    /** Metin bir ana liste mi (en az bir `#EXT-X-STREAM-INF` içeriyor mu). */
    fun isMaster(text: String): Boolean =
        text.removePrefix(BOM).trimStart().startsWith("#EXTM3U") && text.contains("#EXT-X-STREAM-INF")

    /**
     * Ana listedeki bütün katmanları listedeki sırayla döndürür (ses katmanları dahil).
     * `#EXT-X-MEDIA` ses/altyazı grupları ve `#EXT-X-I-FRAME-STREAM-INF` satırları katman sayılmaz.
     * Ana liste değilse boş liste döner.
     */
    fun parse(text: String, base: HttpUrl? = null): List<HlsVariant> {
        val body = text.removePrefix(BOM)
        if (!body.trimStart().startsWith("#EXTM3U")) return emptyList()
        val variants = ArrayList<HlsVariant>()
        var pending: Map<String, String>? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(STREAM_INF)) {
                pending = parseAttributes(line.substring(STREAM_INF.length))
                continue
            }
            // Katman adresi, STREAM-INF'ten sonraki ilk etiket olmayan satırdır (araya yorum girebilir)
            if (line.startsWith("#")) continue
            val attrs = pending ?: continue
            pending = null
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
            val resolution = attrs["RESOLUTION"]?.let { RESOLUTION.find(it) }
            val width = resolution?.groupValues?.get(1)?.toIntOrNull()
            val height = resolution?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }
            val codecs = attrs["CODECS"]?.takeIf { it.isNotBlank() }
            variants += HlsVariant(
                url = base?.resolve(line)?.toString() ?: line,
                bandwidth = bandwidth,
                width = width,
                height = height,
                codecs = codecs,
                frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                audioGroup = attrs["AUDIO"]?.takeIf { it.isNotBlank() },
                audioOnly = height == null && isAudioOnly(codecs),
            )
        }
        return variants
    }

    /** [parse] ile aynı; ana liste adresi metin olarak verilir (geçersizse göreli adresler olduğu gibi kalır). */
    fun parse(text: String, baseUrl: String): List<HlsVariant> = parse(text, baseUrl.toHttpUrlOrNull())

    /** Bütün codec'ler ses codec'i ise (örn. `mp4a.40.2`) true; codec bilgisi yoksa false. */
    private fun isAudioOnly(codecs: String?): Boolean {
        val list = codecs?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
        return list.isNotEmpty() && list.all { codec -> AUDIO_CODEC_PREFIXES.any { codec.startsWith(it) } }
    }

    /**
     * `KEY=değer,KEY2="tırnaklı, virgüllü değer"` öznitelik listesini ayrıştırır. Anahtarlar büyük harfe
     * çevrilir; tırnaklar kaldırılır; tırnak dışındaki virgül ayraçtır.
     */
    internal fun parseAttributes(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && (text[i] == ',' || text[i].isWhitespace())) i++
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val key = text.substring(i, eq).trim().uppercase()
            i = eq + 1
            val value: String
            if (i < n && text[i] == '"') {
                val end = text.indexOf('"', i + 1).let { if (it < 0) n else it }
                value = text.substring(i + 1, end)
                i = minOf(end + 1, n)
                // Kapanış tırnağından sonraki artık karakterleri bir sonraki virgüle kadar at
                while (i < n && text[i] != ',') i++
            } else {
                val end = text.indexOf(',', i).let { if (it < 0) n else it }
                value = text.substring(i, end).trim()
                i = end
            }
            if (key.isNotEmpty()) result.putIfAbsent(key, value)
        }
        return result
    }
}
