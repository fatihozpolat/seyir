package seyir.core.player

import java.util.Locale

/** Konum ve sona erme takibi. Tek iş parçacığından kullanılır. */
class PlaybackProgress {
    var position: Double? = null
        private set
    var duration: Double? = null
        private set
    var sawEof: Boolean = false
        private set

    /** Son `end-file` olayının nedeni ("eof", "error", "quit", "stop"); olay görülmediyse null. */
    var endReason: String? = null
        private set

    /** `end-file` nedeni "error" ise mpv'nin verdiği hata adı (ör. "loading failed"). */
    var fileError: String? = null
        private set

    /** Yeni bir ölçüm; null değerler eski değeri silmez (özellik geçici olarak kullanılamayabilir). */
    fun update(position: Double?, duration: Double?) {
        if (position != null && position >= 0.0) this.position = position
        if (duration != null && duration > 0.0) this.duration = duration
    }

    fun onEvent(event: MpvEvent) {
        if (event.name != "end-file") return
        // "redirect" yeni bir dosyaya geçiştir, oynatmanın sonu değil
        if (event.reason == "redirect") return
        if (event.reason == "eof") sawEof = true
        endReason = event.reason
        fileError = event.fields.get("file_error")?.asText()
    }

    /** Sona %95'ten fazla yaklaşıldıysa ya da mpv "eof" bildirdiyse true. */
    fun reachedEnd(): Boolean {
        if (sawEof) return true
        val pos = position ?: return false
        val dur = duration ?: return false
        return dur > 0.0 && pos / dur > END_THRESHOLD
    }

    /** Bildirilecek son konum: "eof" geldiyse ve süre biliniyorsa süre, yoksa son ölçüm. */
    fun finalPosition(): Double? = if (sawEof) (duration ?: position) else position

    companion object {
        const val END_THRESHOLD = 0.95
    }
}

/**
 * Bir oynatma oturumunun sonucu ve uygulamanın bundan çıkaracağı kararlar (sıradaki bölüm,
 * yedek kaynağa geçiş). Karar mantığı saf olduğu için mpv olmadan sınanır.
 *
 * @property started Dosya yüklendi ve konum alındı.
 * @property processStarted mpv süreci başlatılabildi (başlatılamadıysa kaynak suçlu değildir).
 * @property userStopped Uygulama [PlaybackSession.stop] çağırdı.
 * @property endReason Son `end-file` nedeni; olay hiç görülmediyse null.
 * @property replaced Oturum, aynı pencereye yüklenen başka bir dosyayla (ya da yeni bir mpv süreciyle) değiştirildi.
 *   Hata ya da bitiş değildir: [userStopped] da true olur, yani kullanıcı durdurması gibi sayılır.
 */
data class PlaybackSummary(
    val position: Double?,
    val duration: Double?,
    val reachedEnd: Boolean,
    val error: String?,
    val started: Boolean = false,
    val processStarted: Boolean = true,
    val userStopped: Boolean = false,
    val endReason: String? = null,
    val fileError: String? = null,
    val startSeconds: Double = 0.0,
    val replaced: Boolean = false,
) {
    /** Başlangıç noktasından beri gerçekten oynayan süre (saniye). */
    val playedSeconds: Double get() = ((position ?: startSeconds) - startSeconds).coerceAtLeast(0.0)

    /**
     * Kaynak açılamadı: mpv yükleme hatası bildirdi (kısa süre içinde), dosya oynamadan bitti ya da
     * mpv hiç oynatamadan hata koduyla kapandı. Kullanıcının pencereyi kapatması ya da uygulamanın
     * durdurması hata sayılmaz.
     */
    val startFailed: Boolean
        get() {
            if (userStopped || !processStarted) return false
            return when {
                endReason == "error" -> playedSeconds < LOAD_ERROR_WINDOW_SECONDS
                endReason == "eof" -> playedSeconds < MIN_PLAYED_SECONDS
                endReason == null -> !started && error != null
                else -> false
            }
        }

    /**
     * Bölüm kendi sonuna kadar oynadı. mpv "eof" bildirdiyse kesindir; olay okunamadıysa (mpv
     * borusu kapanmadan önce) son birkaç saniye içinde olmak yeterli sayılır. Kullanıcı pencereyi
     * kapattıysa ("quit") ya da uygulama durdurduysa doğal bitiş değildir.
     */
    val naturalEnd: Boolean
        get() {
            if (userStopped || startFailed) return false
            return when (endReason) {
                "eof" -> true
                null -> {
                    val pos = position
                    val dur = duration
                    started && error == null && pos != null && dur != null && dur > 0.0 && dur - pos <= EOF_TOLERANCE_SECONDS
                }
                else -> false
            }
        }

    companion object {
        /** Bu süreden az oynayıp biten dosya açılamamış sayılır. */
        const val MIN_PLAYED_SECONDS = 3.0

        /** mpv'nin "error" bildirdiği oynatma bu süreden kısaysa kaynak açılamamıştır; sonrası ağ kopmasıdır. */
        const val LOAD_ERROR_WINDOW_SECONDS = 15.0

        /** "eof" olayı kaçırıldığında sona bu kadar yakın konum doğal bitiş sayılır. */
        const val EOF_TOLERANCE_SECONDS = 3.0
    }
}

/** mpv'nin son birkaç çıktı satırını tutar (hata mesajı için). İş parçacığı güvenlidir. */
class OutputTail(private val capacity: Int = 20) {
    private val lines = ArrayDeque<String>()

    fun add(raw: String) {
        // Durum satırları satır başı (\r) ile gelir; son parçayı al, denetim karakterlerini at
        val line = raw.substringAfterLast('\r').filter { it >= ' ' }.trim()
        if (line.isEmpty()) return
        synchronized(lines) {
            if (lines.size >= capacity) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun last(count: Int): List<String> = synchronized(lines) { lines.toList().takeLast(count) }

    fun clear() = synchronized(lines) { lines.clear() }
}

/** Harici altyazıların eklenme planı: hangisi seçilecek, hangisi yalnızca menüye girecek. */
object SubtitlePlanner {
    data class Item(val track: SubtitleTrack, val select: Boolean)

    private val twoLetter = mapOf(
        "tur" to "tr", "eng" to "en", "ger" to "de", "deu" to "de", "fre" to "fr", "fra" to "fr",
        "spa" to "es", "rus" to "ru", "ara" to "ar", "ita" to "it", "por" to "pt", "jpn" to "ja",
        "kor" to "ko", "chi" to "zh", "zho" to "zh",
    )

    /** "tur", "TR", "tr-TR", "pt_BR" → "tr", "tr", "tr", "pt". */
    fun normalizeLang(lang: String?): String? {
        val base = lang?.trim()?.lowercase(Locale.ROOT)?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: return null
        return twoLetter[base] ?: base
    }

    /** İki harfli kodun kendisi ve bilinen üç harfli karşılıkları: "tr" → ["tr", "tur"]. */
    fun codesFor(twoLetterCode: String): List<String> =
        listOf(twoLetterCode) + twoLetter.filterValues { it == twoLetterCode }.keys

    /**
     * Tercih edilen dilde olan ilk altyazı seçilir; yoksa ilk altyazı. Seçilen başa alınır ve
     * `select` ile eklenir; diğerleri `auto` ile (yalnızca menüye) eklenir.
     */
    fun plan(tracks: List<SubtitleTrack>, preferredLang: String?): List<Item> {
        if (tracks.isEmpty()) return emptyList()
        val wanted = normalizeLang(preferredLang)
        val chosen = if (wanted != null) tracks.indexOfFirst { normalizeLang(it.lang) == wanted } else -1
        val selectedIndex = if (chosen >= 0) chosen else 0
        val ordered = listOf(tracks[selectedIndex]) + tracks.filterIndexed { i, _ -> i != selectedIndex }
        return ordered.mapIndexed { i, track -> Item(track, select = i == 0) }
    }

    /** mpv `sub-add` komutu: `sub-add <url> <select|auto> [başlık [dil]]`. */
    fun toCommand(item: Item, defaultTitle: String = "Altyazı"): List<String> {
        val command = mutableListOf("sub-add", item.track.url, if (item.select) "select" else "auto")
        val title = item.track.title.ifBlank { item.track.lang ?: defaultTitle }
        command += title
        item.track.lang?.takeIf { it.isNotBlank() }?.let { command += it }
        return command
    }
}

/** mpv komut satırı üretimi. */
object MpvArgs {
    /** Her mpv sürecine verilen güvenli varsayılanlar. */
    internal val SAFE_DEFAULTS = listOf(
        "--hwdec=auto-safe",
        "--demuxer-lavf-o=extension_picky=0",
        "--save-position-on-quit=no",
        "--ytdl=no", // adresi yerel aktarıcı verir; yt-dlp denemesi gereksiz ve hata mesajlarını kirletir
        "--no-input-terminal",
        "--msg-level=all=warn,cplayer=info",
        "--term-status-msg=",
    )

    /** `--title` değerinde `$` özellik açılımı başlatır; `$$` ile kaçırılır. Çift tırnak Windows'ta sorun çıkarır. */
    fun sanitizeTitle(title: String): String =
        title.replace("\"", "'").replace("$", "$$").filter { it >= ' ' }.trim()

    /**
     * Pencere başlığı (`title` seçeneği özellik açılımı yaptığı için dolar işareti ikiye katlanarak kaçırılır).
     * Denetim karakterleri atılır.
     */
    fun windowTitle(title: String): String = title.filter { it >= ' ' }.replace("$", "$$").trim()

    /** `force-media-title` değeri (açılım yapılmaz, dolar işareti olduğu gibi kalır). */
    fun mediaTitle(title: String): String = title.filter { it >= ' ' }.trim()

    /** Tercih edilen altyazı dilinin eski dosyalarda yaygın kod sayfası; bilinmiyorsa null. */
    fun codepageFor(lang: String?): String? = when (lang) {
        "tr" -> "cp1254"
        else -> null
    }

    /**
     * Seyir oynatıcı arayüzü seçenekleri. `--config-dir` mpv'nin yapılandırma klasörünü arayüz paketine
     * çevirir: kullanıcının kendi `mpv.conf`, betik ve kısayolları okunmaz (arayüz bozulmasın), uosc
     * (`scripts/`), thumbfast, yazı tipleri ve `seyir.lua` buradan yüklenir. mpv'nin kendi OSC'si ve ilerleme
     * çubuğu uosc ile çakıştığı için kapatılır.
     */
    fun uiArgs(ui: PlayerUi): List<String> = listOf(
        "--config-dir=${ui.configDir}",
        "--osc=no",
        "--osd-bar=no",
        "--osd-font=Inter",
        "--script-opts-append=" + MpvUi.scriptOpts(ui),
    )

    /**
     * Gömülü oynatma seçenekleri ([PlayRequest.embed]): mpv [windowId] penceresinin içine çizer. Fare ve klavye
     * uygulamanın denetim katmanına gider; mpv'nin OSC'si, ekran iletileri ve kısayolları kapatılır.
     */
    fun embedArgs(windowId: Long): List<String> = listOf(
        "--wid=$windowId",
        "--osc=no",
        "--osd-bar=no",
        "--osd-level=0",
        "--input-default-bindings=no",
        "--input-vo-keyboard=no",
        "--no-input-cursor",
        "--cursor-autohide=no",
        "--keepaspect-window=no",
    )

    /**
     * Varsayılan seçenekler, ardından [PlayRequest.extraArgs] (varsayılanları ezebilir), en sonda `--` ve adres.
     * Altyazılar burada değil, başladıktan sonra IPC ile eklenir.
     */
    fun build(request: PlayRequest, ipcEndpoint: String, windowArgs: List<String> = emptyList()): List<String> {
        val title = sanitizeTitle(request.title)
        val args = mutableListOf(
            "--force-window=immediate",
            "--title=$title",
            "--force-media-title=$title",
            "--start=" + String.format(Locale.ROOT, "%.3f", request.startSeconds.coerceAtLeast(0.0)),
            "--input-ipc-server=$ipcEndpoint",
            "--keep-open=no",
            "--idle=no",
        )
        args += SAFE_DEFAULTS
        val wanted = SubtitlePlanner.normalizeLang(request.preferredSubtitleLang)
        if (wanted != null) {
            // Gömülü altyazı parçaları için dil tercihi (iki ve üç harfli kod)
            args += "--slang=" + SubtitlePlanner.codesFor(wanted).joinToString(",")
        }
        // Yerel aktarıcı altyazıları zaten UTF-8'e çevirir; bu, mpv'nin kendi açtığı harici altyazılar için
        // yedektir (önce UTF-8 denenir, geçersizse bu kod sayfası kullanılır)
        codepageFor(wanted)?.let { args += "--sub-codepage=$it" }
        for (audio in request.audioFiles) args += "--audio-file=$audio"
        val embed = request.embed
        if (embed != null) {
            args += embedArgs(embed)
        } else {
            args += windowArgs
            request.ui?.let { args += uiArgs(it) }
        }
        args += request.extraArgs
        args += "--"
        args += request.url
        return args
    }
}
