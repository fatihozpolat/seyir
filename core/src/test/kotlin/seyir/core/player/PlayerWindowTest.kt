package seyir.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerWindowTest {
    private val single = listOf(ScreenRect(0, 0, 1920, 1080))
    private val dual = listOf(ScreenRect(0, 0, 1920, 1080), ScreenRect(1920, 0, 2560, 1440))

    @Test
    fun `geometry string has size and optional position`() {
        assertEquals("1000x600", WindowGeometry.geometry(1000, 600, null, null))
        assertEquals("1000x600+200+150", WindowGeometry.geometry(1000, 600, 200, 150))
        // Negatif koordinat "+-X": düz "-X" mpv'de ekranın sağından ölçer
        assertEquals("1920x800+-210+131", WindowGeometry.geometry(1920, 800, -210, 131))
    }

    @Test
    fun `saved geometry becomes mpv arguments`() {
        val state = PlayerWindowState(1000, 600, 200, 150)
        assertEquals(listOf("--geometry=1000x600+200+150"), WindowGeometry.mpvArgs(state, single))
        assertEquals(
            listOf("--geometry=1000x600+200+150", "--window-maximized=yes"),
            WindowGeometry.mpvArgs(state.copy(maximized = true), single),
        )
        assertEquals(
            listOf("--geometry=1000x600+200+150", "--fullscreen"),
            WindowGeometry.mpvArgs(state.copy(fullscreen = true), single),
        )
    }

    @Test
    fun `nothing is applied for an empty state`() {
        assertEquals(emptyList(), WindowGeometry.mpvArgs(null, single))
        assertEquals(emptyList(), WindowGeometry.mpvArgs(PlayerWindowState(), single))
    }

    @Test
    fun `unknown size gives no geometry but keeps flags`() {
        assertEquals(listOf("--fullscreen"), WindowGeometry.mpvArgs(PlayerWindowState(fullscreen = true), single))
    }

    @Test
    fun `position on a removed monitor is dropped and only the size is applied`() {
        // İkinci monitör (x=1920..4480) çıkarıldı
        val state = PlayerWindowState(1200, 700, 2500, 200)
        assertEquals(listOf("--geometry=1200x700"), WindowGeometry.mpvArgs(state, single))
        assertEquals(listOf("--geometry=1200x700+2500+200"), WindowGeometry.mpvArgs(state, dual))
    }

    @Test
    fun `position far below or above the screens is not visible`() {
        assertFalse(WindowGeometry.isVisible(100, 1070, 800, single)) // başlık çubuğu ekranın altında
        assertFalse(WindowGeometry.isVisible(100, -400, 800, single))
        assertFalse(WindowGeometry.isVisible(-1000, 100, 800, single)) // yatay orta ekran dışında
        assertTrue(WindowGeometry.isVisible(100, 100, 800, single))
        assertTrue(WindowGeometry.isVisible(-300, 100, 800, single)) // yarısından çoğu ekranda
        assertTrue(WindowGeometry.isVisible(0, 0, 800, single))
    }

    @Test
    fun `unknown screen layout keeps the position`() {
        assertTrue(WindowGeometry.isVisible(99999, 99999, 800, emptyList()))
        assertEquals(
            listOf("--geometry=800x450+99999+99999"),
            WindowGeometry.mpvArgs(PlayerWindowState(800, 450, 99999, 99999), emptyList()),
        )
    }

    @Test
    fun `size larger than all screens is shrunk`() {
        val fitted = WindowGeometry.fit(PlayerWindowState(5000, 3000, 0, 0), single)
        assertEquals(1920, fitted.width)
        assertEquals(1080, fitted.height)
    }

    @Test
    fun `half known position is dropped`() {
        val fitted = WindowGeometry.fit(PlayerWindowState(800, 450, 10, null), single)
        assertNull(fitted.x)
        assertNull(fitted.y)
    }

    @Test
    fun `tracker reports the normal window and only changes`() {
        val tracker = WindowTracker()
        val first = tracker.update(WindowSample(false, false, width = 1000, height = 600, x = 200, y = 150))
        assertEquals(PlayerWindowState(1000, 600, 200, 150), first)
        assertNull(tracker.update(WindowSample(false, false, width = 1000, height = 600, x = 200, y = 150)))
        val moved = tracker.update(WindowSample(false, false, width = 1000, height = 600, x = 210, y = 150))
        assertEquals(PlayerWindowState(1000, 600, 210, 150), moved)
    }

    @Test
    fun `maximized and fullscreen keep the last normal size`() {
        val tracker = WindowTracker()
        tracker.update(WindowSample(false, false, width = 1000, height = 600, x = 200, y = 150))
        val maximized = tracker.update(WindowSample(false, true, width = 1920, height = 1009, x = 0, y = 0))
        assertEquals(PlayerWindowState(1000, 600, 200, 150, maximized = true), maximized)
        val fullscreen = tracker.update(WindowSample(true, false, width = 1920, height = 1080, x = 0, y = 0))
        assertEquals(PlayerWindowState(1000, 600, 200, 150, fullscreen = true), fullscreen)
        val restored = tracker.update(WindowSample(false, false, width = 1000, height = 600, x = 200, y = 150))
        assertEquals(PlayerWindowState(1000, 600, 200, 150), restored)
    }

    @Test
    fun `minimized windows are never measured`() {
        val tracker = WindowTracker()
        tracker.update(WindowSample(false, false, width = 1000, height = 600, x = 200, y = 150))
        assertNull(tracker.update(WindowSample(false, false, minimized = true, width = 0, height = 0, x = -32000, y = -32000)))
        assertEquals(PlayerWindowState(1000, 600, 200, 150), tracker.current)
    }

    @Test
    fun `saved state survives a maximized first sample`() {
        val saved = PlayerWindowState(1000, 600, 200, 150)
        val tracker = WindowTracker(saved)
        assertNull(tracker.update(WindowSample(false, false, width = 1000, height = 600, x = 200, y = 150)))
        val maximized = tracker.update(WindowSample(false, true))
        assertEquals(saved.copy(maximized = true), maximized)
    }

    @Test
    fun `tiny or empty sizes are not saved and position-less platforms keep what they have`() {
        val tracker = WindowTracker()
        assertNull(tracker.update(WindowSample(false, false, width = 0, height = 0)))
        assertNull(tracker.update(WindowSample(false, false, width = 50, height = 30)))
        // Linux: konum okunamaz, yalnız boyut
        assertEquals(PlayerWindowState(1280, 720), tracker.update(WindowSample(false, false, width = 1280, height = 720)))
    }
}
