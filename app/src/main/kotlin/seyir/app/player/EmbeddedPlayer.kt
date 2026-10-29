package seyir.app.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.WindowPlacement
import com.sun.jna.Native
import seyir.core.Playable
import seyir.core.SeyirLog
import seyir.core.player.PlayerControls
import seyir.core.player.ThumbnailGenerator
import java.awt.Canvas
import java.awt.Color

/** Oynatıcının açılır menüleri; [Episodes] sağdan açılan bölüm listesidir. */
enum class PlayerMenu { Subtitles, Audio, Settings, Sources, Episodes }

/** Oynatma zincirindeki bir kaynak (kaynaklar menüsünde). */
data class SourceOption(val index: Int, val name: String, val detail: String?)

/** Oynatıcının üstünde kısa süre görünen ileti (kaynak değişimi, sıradaki bölüm). [id] her iletide farklıdır. */
data class PlayerNotice(val text: String, val id: Long = System.nanoTime())

/**
 * Uygulama içi oynatıcının ekran durumu. [seyir.app.PlaybackService] yazar, oynatıcı ekranı okur. Durum alanları
 * Compose durumudur; her iş parçacığından yazılabilir.
 */
class EmbeddedPlayer {
    /** mpv'nin çizdiği yerel pencere; oynatıcı ekranı açıkken uygulama penceresinin içindedir. */
    val surface = VideoSurface()

    var open by mutableStateOf(false)

    /** Yapımın adı (üst şeritte büyük). */
    var title by mutableStateOf("")

    /** Bölümün adı ya da kodu; film için null. */
    var subtitle by mutableStateOf<String?>(null)

    /** Kaynak hazırlanıyor ya da sıradaki bölüm aranıyor (dönen gösterge). */
    var preparing by mutableStateOf(false)

    /** Gidilebilecek sonraki/önceki bölümün başlığı; null ise düğme gizlenir. */
    var nextTitle by mutableStateOf<String?>(null)
    var previousTitle by mutableStateOf<String?>(null)

    var notice by mutableStateOf<PlayerNotice?>(null)

    /** Oynayan bölümün kaynak zinciri ve zincirde oynayanın sırası. */
    var sources by mutableStateOf<List<SourceOption>>(emptyList())
    var currentSource by mutableStateOf(0)

    /** Yapımın bölümleri (filmde tek öğe) ve oynayan bölüm; [provider] izleme geçmişine bakmak için. */
    var episodes by mutableStateOf<List<Playable>>(emptyList())
    var currentEpisode by mutableStateOf<String?>(null)
    var provider by mutableStateOf<String?>(null)

    /** İleri sarma önizlemesi; oynayan dosya değişince yenilenir, yalnız ilk istekte süreç açar. */
    var thumbnails by mutableStateOf<ThumbnailSource?>(null)

    /** Oynayan mpv'nin denetimi; mpv açılmadan önce null. */
    var controls by mutableStateOf<PlayerControls?>(null)

    /** Açık menü; null ise kapalı. */
    var menu by mutableStateOf<PlayerMenu?>(null)

    /** Oynatıcı ekranı açıkken tam ekrana geçirir/çıkarır (ekran kurar). */
    var toggleFullscreen: (() -> Unit)? = null

    /** Klavye etkinliği: her tuşta artar, oynatıcı denetimleri bir süre görünür. */
    var activity by mutableLongStateOf(0L)

    /** Tam ekrana geçmeden önceki pencere yerleşimi; tam ekrandan çıkarken geri yüklenir. */
    var placementBeforeFullscreen: WindowPlacement = WindowPlacement.Floating

    fun showNotice(text: String?) {
        if (!text.isNullOrBlank()) notice = PlayerNotice(text)
    }
}

/**
 * mpv'nin içine çizdiği AWT tuvali. Tuval uygulama penceresine eklenince yerel penceresi (HWND / X11 penceresi)
 * oluşur; kaldırılınca yok olur, bir sonraki eklenişte yeni kimlik alır.
 */
class VideoSurface {
    val canvas: Canvas = Canvas().apply {
        background = Color.BLACK
        // Fare ve klavye mpv'ye değil üstteki denetim katmanına gider
        isFocusable = false
    }

    /** Tuval ekrandaysa yerel pencere kimliği, değilse null. */
    fun windowId(): Long? = try {
        if (canvas.isDisplayable && canvas.isShowing) Native.getComponentID(canvas).takeIf { it != 0L } else null
    } catch (e: Throwable) {
        SeyirLog.w(TAG, "Video tuvalinin pencere kimliği alınamadı", e)
        null
    }

    /** Tuval ekrana gelene kadar en çok [timeoutMs] bekler (oynatıcı ekranı yeni açıldıysa); arayüz iş parçacığında çağrılmaz. */
    fun awaitWindowId(timeoutMs: Long): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            windowId()?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(20)
        }
    }

    private companion object {
        const val TAG = "VideoSurface"
    }
}
