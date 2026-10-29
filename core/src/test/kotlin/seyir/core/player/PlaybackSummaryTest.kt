package seyir.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSummaryTest {
    private fun summary(
        position: Double? = null,
        duration: Double? = null,
        error: String? = null,
        started: Boolean = false,
        userStopped: Boolean = false,
        endReason: String? = null,
        processStarted: Boolean = true,
        startSeconds: Double = 0.0,
    ) = PlaybackSummary(
        position = position,
        duration = duration,
        reachedEnd = false,
        error = error,
        started = started,
        processStarted = processStarted,
        userStopped = userStopped,
        endReason = endReason,
        startSeconds = startSeconds,
    )

    private fun event(json: String) = MpvMessageParser.parse(json) as MpvEvent

    @Test
    fun `eof after full playback is a natural end`() {
        val s = summary(position = 2700.0, duration = 2700.0, started = true, endReason = "eof")
        assertTrue(s.naturalEnd)
        assertFalse(s.startFailed)
    }

    @Test
    fun `user closing the window is not a natural end`() {
        val s = summary(position = 2699.0, duration = 2700.0, started = true, endReason = "quit")
        assertFalse(s.naturalEnd)
        assertFalse(s.startFailed)
    }

    @Test
    fun `app initiated stop never autoplays or falls back`() {
        val s = summary(position = 10.0, duration = 2700.0, started = true, endReason = "eof", userStopped = true)
        assertFalse(s.naturalEnd)
        assertFalse(s.startFailed)
        assertFalse(summary(endReason = "error", userStopped = true).startFailed)
    }

    @Test
    fun `missed eof event counts as natural end only right at the end`() {
        assertTrue(summary(position = 2698.5, duration = 2700.0, started = true).naturalEnd)
        assertFalse(summary(position = 2600.0, duration = 2700.0, started = true).naturalEnd)
        assertFalse(summary(position = 2699.0, duration = null, started = true).naturalEnd)
        // hata koduyla kapanan oturum bitmiş sayılmaz
        assertFalse(summary(position = 2699.0, duration = 2700.0, started = true, error = "mpv 2 koduyla kapandı").naturalEnd)
    }

    @Test
    fun `load error is a start failure`() {
        val s = summary(error = "mpv 2 koduyla kapandı", endReason = "error")
        assertTrue(s.startFailed)
        assertFalse(s.naturalEnd)
    }

    @Test
    fun `eof within the first seconds without playing is a start failure`() {
        assertTrue(summary(position = 0.5, started = true, endReason = "eof").startFailed)
        assertTrue(summary(endReason = "eof").startFailed)
        assertFalse(summary(endReason = "eof").naturalEnd)
    }

    @Test
    fun `resume offset is not counted as played time`() {
        // 40 dk'dan devam edilen kaynak 1 sn sonra bitti: oynamamıştır
        assertTrue(summary(position = 2401.0, started = true, endReason = "eof", startSeconds = 2400.0).startFailed)
        assertFalse(summary(position = 2430.0, duration = 2430.0, started = true, endReason = "eof", startSeconds = 2400.0).startFailed)
    }

    @Test
    fun `error long after start is a dropped stream and not a start failure`() {
        val s = summary(position = 600.0, duration = 2700.0, started = true, endReason = "error", error = "mpv 2 koduyla kapandı")
        assertFalse(s.startFailed)
        assertFalse(s.naturalEnd)
    }

    @Test
    fun `nonzero exit before any playback without events is a start failure`() {
        assertTrue(summary(error = "mpv 1 koduyla kapandı").startFailed)
    }

    @Test
    fun `closing the window before anything loaded is not a failure`() {
        assertFalse(summary().startFailed)
        assertFalse(summary(endReason = "quit").startFailed)
    }

    @Test
    fun `failure to spawn mpv is not blamed on the source`() {
        assertFalse(summary(error = "mpv başlatılamadı", processStarted = false).startFailed)
    }

    @Test
    fun `progress records end reason and file error`() {
        val progress = PlaybackProgress()
        assertNull(progress.endReason)
        progress.onEvent(event("""{"event":"end-file","reason":"error","file_error":"loading failed"}"""))
        assertEquals("error", progress.endReason)
        assertEquals("loading failed", progress.fileError)
        assertFalse(progress.sawEof)
    }

    @Test
    fun `redirect end-file does not replace the real end reason`() {
        val progress = PlaybackProgress()
        progress.onEvent(event("""{"event":"end-file","reason":"redirect"}"""))
        assertNull(progress.endReason)
        progress.onEvent(event("""{"event":"end-file","reason":"eof"}"""))
        assertEquals("eof", progress.endReason)
        assertTrue(progress.sawEof)
    }

    @Test
    fun `turkish codepage is passed for turkish subtitles only`() {
        val tr = MpvArgs.build(PlayRequest("http://h/x.m3u8", "t", preferredSubtitleLang = "tur"), "p")
        assertTrue("--sub-codepage=cp1254" in tr)
        val en = MpvArgs.build(PlayRequest("http://h/x.m3u8", "t", preferredSubtitleLang = "en"), "p")
        assertTrue(en.none { it.startsWith("--sub-codepage") })
    }

    @Test
    fun `default subtitle title can be localized`() {
        val track = SubtitleTrack("http://x/n.vtt", "", null)
        assertEquals(
            listOf("sub-add", "http://x/n.vtt", "auto", "Subtitle"),
            SubtitlePlanner.toCommand(SubtitlePlanner.Item(track, false), "Subtitle"),
        )
    }
}
