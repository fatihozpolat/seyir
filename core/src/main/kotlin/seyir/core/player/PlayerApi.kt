package seyir.core.player

/** Oynatıcıya eklenecek harici altyazı. [lang] ISO 639 kodudur ("tr", "tur", "en"...). */
data class SubtitleTrack(val url: String, val title: String, val lang: String?)

/**
 * Bir oynatma isteği.
 *
 * @property url Oynatılacak adres (genellikle yerel aktarıcıdan gelen `http://127.0.0.1:P/...m3u8`).
 *   Başlık/referer işini aktarıcı yaptığı için mpv'ye yalnızca bu adres verilir.
 * @property title Oynatıcı penceresinin başlığı.
 * @property startSeconds Başlangıç konumu (saniye); 0 ise baştan başlar.
 * @property subtitles Başladıktan sonra IPC ile eklenen harici altyazılar.
 * @property preferredSubtitleLang Otomatik seçilecek altyazı dili; eşleşen yoksa ilk altyazı seçilir.
 * @property audioFiles Görüntüden ayrı gelen ses akışları (ör. YouTube'un yalnız görüntü içeren
 *   kaliteleri); `--audio-file` ile eklenir, mpv dil tercihine göre birini seçer.
 * @property extraArgs mpv'ye varsayılanlardan SONRA eklenen ek seçenekler (varsayılanları ezebilir).
 * @property osdMessage Dosya yüklenince mpv penceresinde kısa süre gösterilen ileti (ör. "Sonraki bölüm: S1 B2").
 * @property messages Oynatıcının ürettiği hata metinlerinin dili; varsayılan Türkçe.
 * @property ui Seyir oynatıcı arayüzü; null ise düz mpv (kendi OSC'siyle) açılır.
 * @property window Yeni bir mpv penceresi açılırken uygulanacak kayıtlı pencere durumu (boyut, konum, büyütülmüş,
 *   tam ekran); null ise mpv kendi varsayılanını kullanır. Var olan pencereye yüklenen dosyalarda yok sayılır.
 * @property embed Gömülü oynatma: mpv kendi penceresini açmaz, bu yerel pencereye (Windows'ta HWND, Linux'ta X11
 *   pencere kimliği) çizer. mpv'nin arayüzü ve kısayolları kapanır; denetimi uygulama yapar ([MpvPlayer.controls]).
 *   Verilince [ui] ve [window] yok sayılır, [osdMessage] gösterilmez.
 */
data class PlayRequest(
    val url: String,
    val title: String,
    val startSeconds: Double = 0.0,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val preferredSubtitleLang: String? = "tr",
    val audioFiles: List<String> = emptyList(),
    val extraArgs: List<String> = emptyList(),
    val osdMessage: String? = null,
    val messages: PlayerMessages = TurkishPlayerMessages,
    val ui: PlayerUi? = null,
    val window: PlayerWindowState? = null,
    val embed: Long? = null,
)

/**
 * Oynatıcı katmanının kullanıcıya ulaşabilen metinleri. Çekirdek arayüz dilini bilmediği için
 * uygulama kendi dilindeki karşılığını [PlayRequest.messages] ile verir.
 */
interface PlayerMessages {
    /** mpv süreci başlatılamadı. */
    fun startFailed(reason: String?): String

    /** mpv beklenmedik bir çıkış koduyla kapandı; [tail] son çıktı satırlarıdır (boş olabilir). */
    fun exitedWithCode(code: Int, tail: String): String

    /** İzleme döngüsü beklenmedik bir hatayla bitti. */
    fun sessionFailed(reason: String?): String

    /** Başlığı olmayan altyazı parçasının menüde görünen adı. */
    val subtitleTitle: String
}

/** Varsayılan Türkçe metinler (komut satırı aracı bunları kullanır). */
object TurkishPlayerMessages : PlayerMessages {
    override fun startFailed(reason: String?) = "mpv başlatılamadı: $reason"
    override fun exitedWithCode(code: Int, tail: String) = "mpv $code koduyla kapandı" + if (tail.isNotEmpty()) ": $tail" else ""
    override fun sessionFailed(reason: String?) = "oynatma hatası: $reason"
    override val subtitleTitle = "Altyazı"
}

/** Oynatma olayları. Çağrılar oturumun kendi iş parçacığından gelir; uzun iş yapmayın. */
interface PlaybackListener {
    /** Dosya yüklendi, oynatma başladı. */
    fun onStarted() {}

    /** Kullanıcı arayüzdeki "sonraki bölüm" düğmesine bastı (oturumun kendi iş parçacığından gelir). */
    fun onNextRequested() {}

    /** Kullanıcı arayüzdeki "önceki bölüm" düğmesine bastı (oturumun kendi iş parçacığından gelir). */
    fun onPreviousRequested() {}

    /** Yaklaşık 2 saniyede bir; [duration] canlı akışlarda null olabilir. */
    fun onProgress(position: Double, duration: Double?) {}

    /**
     * mpv kapandıktan sonra, [onExit]'ten hemen sonra çağrılır; karar vermek için gereken bütün
     * ayrıntıları verir (doğal bitiş, açılamama, kullanıcı durdurması).
     */
    fun onFinished(summary: PlaybackSummary) {}

    /**
     * mpv kapandı.
     *
     * @param position Son bilinen konum (saniye); hiç alınamadıysa null.
     * @param reachedEnd Sona %95'ten fazla yaklaşıldı ya da mpv "eof" bildirdi.
     * @param error Kullanıcı kapatmadan beklenmedik biçimde sonlandıysa hata metni, yoksa null.
     */
    fun onExit(position: Double?, duration: Double?, reachedEnd: Boolean, error: String?) {}

    /**
     * Pencere boyutu, konumu, büyütülmüş ya da tam ekran durumu değişti (en çok saniyede bir). [final] mpv
     * kapanırken gelen son durumdur; hemen saklanmalıdır. Oturum bittikten sonra da (aynı pencere açıkken)
     * son oturumun dinleyicisine gelebilir.
     */
    fun onWindowState(state: PlayerWindowState, final: Boolean = false) {}
}

/** Çalışan bir oynatma oturumu. */
interface PlaybackSession {
    /**
     * mpv'yi nazikçe kapatır (önce `quit`, olmazsa süreci sonlandırır). Birden çok kez çağrılabilir.
     * Oturum başka bir oturumla değiştirildiyse (aynı pencereye yeni dosya yüklendi) hiçbir şey yapmaz;
     * yeni oturumu etkilemez. Dosya bitmiş ama pencere boşta açıksa pencereyi kapatır.
     */
    fun stop()

    /** [PlaybackListener.onExit] çağrılana kadar true (oturum bitince, pencere açık kalsa bile false olur). */
    val isRunning: Boolean

    /**
     * Arayüzdeki "sonraki bölüm" düğmesini ve başlığını ayarlar; null sonraki bölüm yok demektir.
     * Arayüz kapalıyken etkisizdir. Her iş parçacığından çağrılabilir; oturum henüz yüklenmediyse
     * yüklenince iletilir.
     */
    fun setNextEpisode(title: String?) {}

    /** Arayüzdeki "önceki bölüm" düğmesini ayarlar; null önceki bölüm yok demektir ([setNextEpisode] gibi). */
    fun setPreviousEpisode(title: String?) {}
}
