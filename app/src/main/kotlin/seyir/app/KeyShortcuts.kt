package seyir.app

import androidx.compose.ui.input.key.Key

/** Pencere düzeyindeki klavye kısayollarının eylemleri. */
enum class ShortcutAction {
    /** Bir önceki ekrana dön. */
    Back,

    /** Arama ekranını aç ve arama kutusuna odaklan. */
    FocusSearch,
}

/**
 * Pencere düzeyinde dinlenen tuşları eyleme çevirir; Compose'a bağlı olmadığı için tek başına sınanır.
 *
 * İki aşama vardır. Önizleme aşamasında ([preview] doğru) yalnız metin kutusunda da anlamlı olan
 * bileşimler (Alt+Sol, Ctrl+F) işlenir. Esc ve "/" tuşları ise alt bileşenler tüketmediyse
 * gelen aşamada işlenir: böylece açık menü ya da pencere Esc'yi kendisi kapatır, arama kutusuna
 * yazılan "/" harfi de kısayol sayılmaz.
 *
 * @param codePoint basılan tuşun ürettiği karakter (Türkçe klavyede "/" Shift+7 olduğu için tuş
 *   koduna değil karaktere bakılır); yoksa 0
 * @param overlayOpen bir pencere (kaynak listesi, eklenti seçimi) açıkken kısayollar çalışmaz
 * @param canGoBack geri dönülecek bir ekran var mı
 */
fun shortcutFor(
    key: Key,
    codePoint: Int,
    alt: Boolean,
    ctrl: Boolean,
    preview: Boolean,
    overlayOpen: Boolean,
    canGoBack: Boolean,
): ShortcutAction? {
    if (overlayOpen) return null
    return if (preview) {
        when {
            alt && !ctrl && key == Key.DirectionLeft -> if (canGoBack) ShortcutAction.Back else null
            ctrl && !alt && key == Key.F -> ShortcutAction.FocusSearch
            else -> null
        }
    } else {
        when {
            !alt && !ctrl && key == Key.Escape -> if (canGoBack) ShortcutAction.Back else null
            !alt && !ctrl && codePoint == '/'.code -> ShortcutAction.FocusSearch
            else -> null
        }
    }
}
