package seyir.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import seyir.core.AppDirs
import seyir.core.SeyirLog
import java.awt.Window

/**
 * Windows'ta pencerenin başlık çubuğunu uygulamanın koyu zeminine uydurur. Sistem açık temadayken
 * beyaz kalan başlık çubuğu karanlık arayüzün üstünde göz alıyor. Linux'ta pencere yöneticisi
 * karar verir; orada hiçbir şey yapılmaz.
 */
object DarkTitleBar {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private const val USE_IMMERSIVE_DARK_MODE = 20
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    // COLORREF sırası 0x00BBGGRR: zemin #0F0F12, yazı #C5C6CD
    private const val CAPTION_RGB = 0x00120F0F
    private const val TEXT_RGB = 0x00CDC6C5

    fun apply(window: Window) {
        if (!AppDirs.isWindows) return
        runCatching {
            val dwm = Native.load("dwmapi", Dwm::class.java)
            val hwnd = Native.getWindowPointer(window)
            dwm.DwmSetWindowAttribute(hwnd, USE_IMMERSIVE_DARK_MODE, IntByReference(1), 4)
            // Renk öznitelikleri yalnız Windows 11'de var; Windows 10 koyu kipte kalır
            dwm.DwmSetWindowAttribute(hwnd, CAPTION_COLOR, IntByReference(CAPTION_RGB), 4)
            dwm.DwmSetWindowAttribute(hwnd, TEXT_COLOR, IntByReference(TEXT_RGB), 4)
        }.onFailure { SeyirLog.w("DarkTitleBar", "Başlık çubuğu rengi ayarlanamadı: ${it.message}") }
    }
}
