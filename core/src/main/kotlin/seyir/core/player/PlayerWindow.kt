package seyir.core.player

import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.Locale

/**
 * Oynatıcı penceresinin hatırlanan durumu.
 *
 * [width] ve [height] penceresinin NORMAL (büyütülmemiş, tam ekran olmayan) iç alan boyutudur
 * (mpv `--geometry` boyutuyla aynı anlam; başlık çubuğu ve kenarlıklar sayılmaz). Pencere büyütülmüş ya da
 * tam ekranken bu değerler ezilmez; böylece durum kapatılınca da son normal boyut geri gelir.
 * [x] ve [y] pencerenin dış çerçevesinin sol üst köşesidir (ekran pikseli, mpv `--geometry` konumuyla
 * aynı anlam); okunamıyorsa (Linux) null kalır. 0 boyut "bilinmiyor" demektir.
 */
data class PlayerWindowState(
    val width: Int = 0,
    val height: Int = 0,
    val x: Int? = null,
    val y: Int? = null,
    val maximized: Boolean = false,
    val fullscreen: Boolean = false,
) {
    /** Boyut bilinen ve uygulanabilir mi. */
    @get:JsonIgnore
    val hasSize: Boolean get() = width >= WindowGeometry.MIN_WIDTH && height >= WindowGeometry.MIN_HEIGHT

    /** Saklamaya değer bir şey var mı (boş varsayılan durum kaydedilmez). */
    @get:JsonIgnore
    val isMeaningful: Boolean get() = hasSize || maximized || fullscreen
}

/** Bir ekranın (monitörün) sanal masaüstündeki dikdörtgeni; ekran pikseli. */
data class ScreenRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/** Pencerenin işletim sisteminden okunan çerçevesi: [x], [y] dış çerçevenin köşesi, boyut iç alanın boyutu. */
data class WindowFrame(val x: Int, val y: Int, val clientWidth: Int, val clientHeight: Int, val minimized: Boolean = false)

/**
 * Pencere konumunu ve ekran düzenini okur. mpv pencere konumunu vermediği için Windows'ta işletim sistemine
 * sorulur ([Win32WindowProbe]); diğer sistemlerde konum okunamaz (yalnız boyut, büyütme ve tam ekran hatırlanır).
 */
interface WindowProbe {
    /** Konum okunabiliyor mu (false ise [frame] hiç çağrılmaz). */
    val canReadFrame: Boolean

    /** [windowId] (mpv `window-id`) penceresinin çerçevesi; okunamıyorsa null. */
    fun frame(windowId: Long): WindowFrame?

    /** Bağlı ekranlar; bilinmiyorsa boş liste (konum denetimi yapılmaz). */
    fun screens(): List<ScreenRect>

    companion object {
        /** Çalışılan sisteme uygun okuyucu; Windows'ta JNA kullanılamazsa konumsuz okuyucuya düşer. */
        fun platform(): WindowProbe =
            if (seyir.core.AppDirs.isWindows) Win32WindowProbe.createOrNull() ?: AwtWindowProbe else AwtWindowProbe
    }
}

/** Konum okuyamayan okuyucu; ekranları AWT'den alır (başsız ortamda boş). */
object AwtWindowProbe : WindowProbe {
    override val canReadFrame: Boolean = false

    override fun frame(windowId: Long): WindowFrame? = null

    override fun screens(): List<ScreenRect> = runCatching {
        val env = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        if (java.awt.GraphicsEnvironment.isHeadless()) return@runCatching emptyList()
        env.screenDevices.map { device ->
            val config = device.defaultConfiguration
            val bounds = config.bounds
            // AWT sınırları ölçekli birimdedir; pencere boyutları ekran pikselidir
            val scale = config.defaultTransform.scaleX
            ScreenRect(
                (bounds.x * scale).toInt(),
                (bounds.y * scale).toInt(),
                (bounds.width * scale).toInt(),
                (bounds.height * scale).toInt(),
            )
        }
    }.getOrDefault(emptyList())
}

/** Bir ölçüm: mpv'nin özellikleri ve (varsa) işletim sisteminden okunan çerçeve. */
data class WindowSample(
    val fullscreen: Boolean,
    val maximized: Boolean,
    val minimized: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val x: Int? = null,
    val y: Int? = null,
)

/** Pencere geometrisi hesapları (saf; sınanır). */
object WindowGeometry {
    const val MIN_WIDTH = 160
    const val MIN_HEIGHT = 90

    /** Konumun görünür sayılması için çerçevenin üst kenarının ekranın üstünden en çok bu kadar yukarıda olması. */
    private const val TOP_SLACK = 12

    /** Başlık çubuğunu tutacak kadar ekranın altından uzakta olmalı. */
    private const val BOTTOM_MARGIN = 80

    /**
     * Konum bir ekranda tutulabilir mi: pencerenin yatay ortası bir ekranın içinde ve üst kenarı (başlık çubuğu)
     * o ekranda görünür olmalı. Ekran listesi boşsa (bilinmiyorsa) görünür kabul edilir.
     */
    fun isVisible(x: Int, y: Int, width: Int, screens: List<ScreenRect>): Boolean {
        if (screens.isEmpty()) return true
        val centerX = x + width / 2
        return screens.any { s ->
            centerX >= s.x && centerX < s.right && y >= s.y - TOP_SLACK && y < s.bottom - BOTTOM_MARGIN
        }
    }

    /**
     * Kayıtlı durumu geçerli ekran düzenine uydurur: ekran dışında kalan konum atılır (yalnız boyut uygulanır),
     * bütün ekranların toplam alanından büyük boyut sığacak şekilde küçültülür.
     */
    fun fit(state: PlayerWindowState, screens: List<ScreenRect>): PlayerWindowState {
        var fitted = state
        if (screens.isNotEmpty() && fitted.hasSize) {
            val maxWidth = screens.maxOf { it.right } - screens.minOf { it.x }
            val maxHeight = screens.maxOf { it.bottom } - screens.minOf { it.y }
            fitted = fitted.copy(width = fitted.width.coerceAtMost(maxWidth), height = fitted.height.coerceAtMost(maxHeight))
        }
        val x = fitted.x
        val y = fitted.y
        if (x != null && y != null && !isVisible(x, y, fitted.width, screens)) {
            fitted = fitted.copy(x = null, y = null)
        }
        // Yarım kalmış konum kullanılmaz
        if ((fitted.x == null) != (fitted.y == null)) fitted = fitted.copy(x = null, y = null)
        return fitted
    }

    /** `--geometry` değeri: `WxH` ya da `WxH+X+Y` (negatif koordinat `+-X` yazılır; `-X` sağdan ölçer). */
    fun geometry(width: Int, height: Int, x: Int?, y: Int?): String {
        val size = "${width}x$height"
        if (x == null || y == null) return size
        return String.format(Locale.ROOT, "%s+%d+%d", size, x, y)
    }

    /**
     * mpv'nin pencereyi açarken alacağı seçenekler: geometri, büyütülmüş ve tam ekran. Boyut bilinmiyorsa
     * geometri verilmez (mpv kendi boyutunu seçer). Önce [fit] ile ekran düzenine uydurulur.
     */
    fun mpvArgs(state: PlayerWindowState?, screens: List<ScreenRect>): List<String> {
        if (state == null || !state.isMeaningful) return emptyList()
        val fitted = fit(state, screens)
        val args = mutableListOf<String>()
        if (fitted.hasSize) args += "--geometry=" + geometry(fitted.width, fitted.height, fitted.x, fitted.y)
        if (fitted.maximized) args += "--window-maximized=yes"
        if (fitted.fullscreen) args += "--fullscreen"
        return args
    }
}

/**
 * Ölçümlerden saklanacak pencere durumunu çıkarır. Büyütülmüş ya da tam ekranken ölçülen boyut ve konum
 * normal değildir; o durumlarda son normal boyut korunur. Küçültülmüş (simge durumunda) pencere hiç ölçülmez.
 * Tek iş parçacığından kullanılır.
 */
class WindowTracker(initial: PlayerWindowState? = null) {
    private var state: PlayerWindowState = initial ?: PlayerWindowState()
    private var reported: PlayerWindowState? = initial

    /** Şu anki durum. */
    val current: PlayerWindowState get() = state

    /** Yeni ölçümü uygular; saklanacak durum değiştiyse yeni durumu, değişmediyse null döner. */
    fun update(sample: WindowSample): PlayerWindowState? {
        if (sample.minimized) return null
        state = when {
            sample.fullscreen || sample.maximized ->
                state.copy(maximized = sample.maximized, fullscreen = sample.fullscreen)
            sample.width >= WindowGeometry.MIN_WIDTH && sample.height >= WindowGeometry.MIN_HEIGHT -> PlayerWindowState(
                width = sample.width,
                height = sample.height,
                x = sample.x ?: state.x,
                y = sample.y ?: state.y,
            )
            else -> state.copy(maximized = false, fullscreen = false)
        }
        if (!state.isMeaningful || state == reported) return null
        reported = state
        return state
    }
}
