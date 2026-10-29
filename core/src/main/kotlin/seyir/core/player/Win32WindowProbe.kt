package seyir.core.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import seyir.core.SeyirLog

/**
 * Windows'ta mpv penceresinin çerçevesini ve ekranları okur.
 *
 * mpv `--geometry=WxH+X+Y` değerini şöyle uygular: W×H pencerenin İÇ alan boyutudur, X+Y ise dış çerçevenin
 * GÖRÜNEN sol üst köşesidir (Windows 10/11'de GetWindowRect'in saydığı görünmez 7 pikselik gölge kenarlık
 * sayılmaz). Bu yüzden konum `DwmGetWindowAttribute(DWMWA_EXTENDED_FRAME_BOUNDS)` ile, boyut `GetClientRect` ile
 * okunur; böylece okunan değerler aynen `--geometry`'ye verilir.
 *
 * DPI: mpv her monitör için DPI farkında (PerMonitorV2) olduğundan koordinatları ekran pikselidir. Uygulamanın
 * kendi iş parçacığı farklı bir DPI kipindeyse işletim sistemi başka sürecin penceresi için sanallaştırılmış
 * (ölçeklenmiş) değer döndürür; bu yüzden her okuma için çağıran iş parçacığı geçici olarak PerMonitorV2
 * kipine alınır ve sonra eski kipine döndürülür.
 */
internal class Win32WindowProbe private constructor(
    private val user32: User32,
    private val dwm: Dwm?,
) : WindowProbe {
    override val canReadFrame: Boolean = true

    override fun frame(windowId: Long): WindowFrame? = withPerMonitorDpi {
        val hwnd = Pointer.createConstant(windowId)
        if (!user32.IsWindow(hwnd)) return@withPerMonitorDpi null
        if (user32.IsIconic(hwnd)) return@withPerMonitorDpi WindowFrame(0, 0, 0, 0, minimized = true)
        val client = Memory(16)
        if (!user32.GetClientRect(hwnd, client)) return@withPerMonitorDpi null
        val width = client.getInt(8)
        val height = client.getInt(12)
        val bounds = Memory(16)
        val hasDwmBounds = dwm?.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, bounds, 16) == 0
        if (!hasDwmBounds && !user32.GetWindowRect(hwnd, bounds)) return@withPerMonitorDpi null
        WindowFrame(bounds.getInt(0), bounds.getInt(4), width, height)
    }

    override fun screens(): List<ScreenRect> = withPerMonitorDpi {
        val found = mutableListOf<ScreenRect>()
        val callback = object : MonitorEnumProc {
            override fun callback(monitor: Pointer?, hdc: Pointer?, rect: Pointer, data: Pointer?): Boolean {
                val left = rect.getInt(0)
                val top = rect.getInt(4)
                found += ScreenRect(left, top, rect.getInt(8) - left, rect.getInt(12) - top)
                return true
            }
        }
        user32.EnumDisplayMonitors(null, null, callback, null)
        found.toList()
    } ?: emptyList()

    private inline fun <T> withPerMonitorDpi(block: () -> T?): T? {
        val previous = runCatching { user32.SetThreadDpiAwarenessContext(Pointer.createConstant(PER_MONITOR_AWARE_V2)) }.getOrNull()
        return try {
            block()
        } catch (e: Throwable) {
            SeyirLog.d(TAG, "pencere okunamadı: ${e.message}")
            null
        } finally {
            if (previous != null) runCatching { user32.SetThreadDpiAwarenessContext(previous) }
        }
    }

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun IsWindow(hwnd: Pointer): Boolean
        fun IsIconic(hwnd: Pointer): Boolean
        fun GetClientRect(hwnd: Pointer, rect: Pointer): Boolean
        fun GetWindowRect(hwnd: Pointer, rect: Pointer): Boolean
        fun SetThreadDpiAwarenessContext(context: Pointer): Pointer?
        fun EnumDisplayMonitors(hdc: Pointer?, clip: Pointer?, callback: MonitorEnumProc, data: Pointer?): Boolean
    }

    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmGetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    interface MonitorEnumProc : Callback {
        fun callback(monitor: Pointer?, hdc: Pointer?, rect: Pointer, data: Pointer?): Boolean
    }

    companion object {
        private const val TAG = "Win32WindowProbe"
        private const val DWMWA_EXTENDED_FRAME_BOUNDS = 9

        /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = (HANDLE)-4 */
        private const val PER_MONITOR_AWARE_V2 = -4L

        /** JNA ve sistem kitaplıkları yüklenemezse null (konum hatırlanmaz). */
        fun createOrNull(): Win32WindowProbe? = try {
            val user32 = Native.load("user32", User32::class.java)
            val dwm = runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
            Win32WindowProbe(user32, dwm)
        } catch (e: Throwable) {
            SeyirLog.w(TAG, "Windows pencere okuyucusu yüklenemedi: ${e.message}")
            null
        }
    }
}
