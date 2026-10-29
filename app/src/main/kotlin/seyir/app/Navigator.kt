package seyir.app

import androidx.compose.runtime.mutableStateListOf

/** Uygulamadaki ekranlar. */
sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object History : Screen
    data object Bookmarks : Screen
    data object Downloads : Screen
    data object Plugins : Screen
    data object Settings : Screen
    data class Detail(val provider: String, val url: String, val name: String, val posterUrl: String?) : Screen
}

/** Ekran yığınını tutar; geri düğmesi ve kenar menüsü buradan yönlenir. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun open(screen: Screen) {
        if (current != screen) stack.add(screen)
    }

    /** Kenar menüsünden seçilen ana ekrana geçer, geçmişi temizler. */
    fun root(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun back() {
        if (canGoBack) stack.removeAt(stack.lastIndex)
    }
}
