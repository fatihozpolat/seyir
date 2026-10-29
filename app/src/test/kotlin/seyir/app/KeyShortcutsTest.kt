package seyir.app

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyShortcutsTest {
    private fun pre(key: Key, alt: Boolean = false, ctrl: Boolean = false, back: Boolean = true, overlay: Boolean = false) =
        shortcutFor(key, 0, alt, ctrl, preview = true, overlayOpen = overlay, canGoBack = back)

    private fun post(key: Key, codePoint: Int = 0, back: Boolean = true, overlay: Boolean = false) =
        shortcutFor(key, codePoint, alt = false, ctrl = false, preview = false, overlayOpen = overlay, canGoBack = back)

    @Test
    fun altLeftGoesBackOnlyWhenPossible() {
        assertEquals(ShortcutAction.Back, pre(Key.DirectionLeft, alt = true))
        assertNull(pre(Key.DirectionLeft, alt = true, back = false))
        assertNull(pre(Key.DirectionLeft))
    }

    @Test
    fun ctrlFFocusesSearch() {
        assertEquals(ShortcutAction.FocusSearch, pre(Key.F, ctrl = true))
        assertNull(pre(Key.F))
    }

    @Test
    fun escapeGoesBackAfterChildrenDeclined() {
        assertEquals(ShortcutAction.Back, post(Key.Escape))
        assertNull(post(Key.Escape, back = false))
        // Önizleme aşamasında Esc işlenmez: menü ve pencereler kendileri kapatır
        assertNull(pre(Key.Escape))
    }

    @Test
    fun slashFocusesSearchByCharacter() {
        assertEquals(ShortcutAction.FocusSearch, post(Key.Seven, codePoint = '/'.code))
        assertEquals(ShortcutAction.FocusSearch, post(Key.Slash, codePoint = '/'.code))
        assertNull(post(Key.Seven, codePoint = '7'.code))
    }

    @Test
    fun nothingWorksWhileDialogIsOpen() {
        assertNull(pre(Key.F, ctrl = true, overlay = true))
        assertNull(post(Key.Escape, overlay = true))
        assertNull(post(Key.Slash, codePoint = '/'.code, overlay = true))
    }
}
