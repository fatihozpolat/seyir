package seyir.core.player

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvLogicTest {
    private fun event(json: String) = MpvMessageParser.parse(json) as MpvEvent

    @Test
    fun `no end without position or duration`() {
        val progress = PlaybackProgress()
        assertFalse(progress.reachedEnd())
        progress.update(100.0, null)
        assertFalse(progress.reachedEnd())
        assertEquals(100.0, progress.finalPosition())
    }

    @Test
    fun `end is reached above ninety five percent`() {
        val progress = PlaybackProgress()
        progress.update(94.9, 100.0)
        assertFalse(progress.reachedEnd())
        progress.update(95.0, 100.0) // tam %95 "fazla" sayılmaz
        assertFalse(progress.reachedEnd())
        progress.update(95.1, 100.0)
        assertTrue(progress.reachedEnd())
    }

    @Test
    fun `eof event marks end and reports duration as final position`() {
        val progress = PlaybackProgress()
        progress.update(40.0, 7200.0)
        progress.onEvent(event("""{"event":"end-file","reason":"eof"}"""))
        assertTrue(progress.reachedEnd())
        assertEquals(7200.0, progress.finalPosition())
    }

    @Test
    fun `other end-file reasons do not count as end`() {
        val progress = PlaybackProgress()
        progress.update(40.0, 7200.0)
        progress.onEvent(event("""{"event":"end-file","reason":"quit"}"""))
        progress.onEvent(event("""{"event":"end-file","reason":"error"}"""))
        progress.onEvent(event("""{"event":"file-loaded"}"""))
        assertFalse(progress.reachedEnd())
        assertEquals(40.0, progress.finalPosition())
    }

    @Test
    fun `null measurements keep last known values`() {
        val progress = PlaybackProgress()
        progress.update(12.0, 600.0)
        progress.update(null, null)
        progress.update(-1.0, 0.0)
        assertEquals(12.0, progress.position)
        assertEquals(600.0, progress.duration)
    }

    @Test
    fun `live stream without duration never reaches end by position`() {
        val progress = PlaybackProgress()
        progress.update(9999.0, null)
        assertFalse(progress.reachedEnd())
    }

    @Test
    fun `output tail keeps only the last lines and cleans control characters`() {
        val tail = OutputTail(3)
        listOf("a", "b", "", "c\u0007", "status\rfinal", "e").forEach(tail::add)
        assertEquals(listOf("c", "final", "e"), tail.last(10))
        assertEquals(listOf("e"), tail.last(1))
    }

    @Test
    fun `language normalization maps three letter codes`() {
        assertEquals("tr", SubtitlePlanner.normalizeLang("tur"))
        assertEquals("tr", SubtitlePlanner.normalizeLang("TR"))
        assertEquals("tr", SubtitlePlanner.normalizeLang("tr-TR"))
        assertEquals("pt", SubtitlePlanner.normalizeLang("pt_BR"))
        assertNull(SubtitlePlanner.normalizeLang(null))
        assertNull(SubtitlePlanner.normalizeLang(" "))
    }

    private val en = SubtitleTrack("http://x/en.vtt", "English", "en")
    private val tr = SubtitleTrack("http://x/tr.vtt", "Türkçe Altyazı", "tur")
    private val none = SubtitleTrack("http://x/n.vtt", "", null)

    @Test
    fun `preferred language track is selected and added first`() {
        val plan = SubtitlePlanner.plan(listOf(en, tr, none), "tr")
        assertEquals(listOf(tr, en, none), plan.map { it.track })
        assertEquals(listOf(true, false, false), plan.map { it.select })
    }

    @Test
    fun `first track is selected when no language matches`() {
        val plan = SubtitlePlanner.plan(listOf(en, none), "tr")
        assertEquals(listOf(en, none), plan.map { it.track })
        assertEquals(true, plan.first().select)
        assertEquals(1, plan.count { it.select })
    }

    @Test
    fun `first track is selected when preferred language is null`() {
        val plan = SubtitlePlanner.plan(listOf(en, tr), null)
        assertEquals(listOf(en, tr), plan.map { it.track })
    }

    @Test
    fun `empty subtitle list gives empty plan`() {
        assertTrue(SubtitlePlanner.plan(emptyList(), "tr").isEmpty())
    }

    @Test
    fun `sub-add command carries title and language`() {
        assertEquals(
            listOf("sub-add", "http://x/tr.vtt", "select", "Türkçe Altyazı", "tur"),
            SubtitlePlanner.toCommand(SubtitlePlanner.Item(tr, true)),
        )
        assertEquals(
            listOf("sub-add", "http://x/n.vtt", "auto", "Altyazı"),
            SubtitlePlanner.toCommand(SubtitlePlanner.Item(none, false)),
        )
    }

    @Test
    fun `mpv arguments contain safe defaults and end with url`() {
        val request = PlayRequest(
            url = "http://127.0.0.1:8123/a/b.m3u8",
            title = "Dizi \"Bir\" \$x",
            startSeconds = 5.0,
            extraArgs = listOf("--volume=50"),
        )
        val args = MpvArgs.build(request, "\\\\.\\pipe\\seyir-mpv-abc")
        assertTrue("--force-window=immediate" in args)
        assertTrue("--start=5.000" in args)
        assertTrue("--input-ipc-server=\\\\.\\pipe\\seyir-mpv-abc" in args)
        assertTrue("--keep-open=no" in args)
        assertTrue("--ytdl=no" in args)
        assertTrue("--idle=no" in args)
        assertTrue("--hwdec=auto-safe" in args)
        assertTrue("--demuxer-lavf-o=extension_picky=0" in args)
        assertTrue("--title=Dizi 'Bir' \$\$x" in args)
        assertTrue("--slang=tr,tur" in args)
        assertTrue(args.indexOf("--volume=50") < args.indexOf("--"))
        assertEquals(listOf("--", request.url), args.takeLast(2))
    }

    @Test
    fun `start position is never negative and slang can be disabled`() {
        val args = MpvArgs.build(PlayRequest("http://h/x.m3u8", "t", startSeconds = -3.0, preferredSubtitleLang = null), "p")
        assertTrue("--start=0.000" in args)
        assertTrue(args.none { it.startsWith("--slang") })
    }

    @Test
    fun `json encoding of command stays single line for odd titles`() {
        val line = MpvMessageParser.encodeCommand(1, listOf("sub-add", "u", "auto", "satır\nsonu"))
        assertFalse(line.contains('\n'))
        assertEquals("satır\nsonu", jacksonObjectMapper().readTree(line).get("command")[3].asText())
    }
}
