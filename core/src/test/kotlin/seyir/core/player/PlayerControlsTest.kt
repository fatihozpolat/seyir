package seyir.core.player

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerControlsTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `HLS surumlerinden kalite katmanlari cikarilir`() {
        val editions = mapper.readTree(
            """
            [
              {"id": 0, "title": "Bitrate: 8.224 Mbps (h264 [High] 1920x1080 60 fps 8224 kbps) (ac3)"},
              {"id": 1, "title": "Bitrate: 7.900 Mbps (h264 [High] 1920x1080 60 fps 7900 kbps) (aac)"},
              {"id": 2, "title": "Bitrate: 3.439 Mbps (h264 [High] 1280x720 60 fps 3439 kbps)"},
              {"id": 3, "title": "Bitrate: 764 Kbps (h264 [Main] 480x270 30 fps 764 kbps)"},
              {"id": 4, "title": "Bitrate: 128 Kbps (aac)"}
            ]
            """.trimIndent(),
        )
        val layers = PlayerStateMapping.qualities(editions)
        assertEquals(listOf(1080, 720, 270), layers.map { it.height })
        // Aynı yükseklikte en yüksek bit hızlısı kalır
        assertEquals(0, layers.first().editionId)
        assertEquals("1080p60", layers.first().label)
        assertEquals("270p", layers.last().label)
        assertEquals(764.0, layers.last().kbps)
    }

    @Test
    fun `tek katmanli yayinda kalite listesi bos`() {
        val single = mapper.readTree("""[{"id": 0, "title": "Bitrate: 2 Mbps (h264 1280x720)"}, {"id": 1, "title": "Bitrate: 128 Kbps (aac)"}]""")
        assertTrue(PlayerStateMapping.qualities(single).isEmpty())
        assertTrue(PlayerStateMapping.qualities(null).isEmpty())
    }

    @Test
    fun `iz listesi ses ve altyazi izlerine ayrilir, kapak gorseli atilir`() {
        val tracks = mapper.readTree(
            """
            [
              {"id": 1, "type": "video", "selected": true, "codec": "h264"},
              {"id": 2, "type": "video", "albumart": true},
              {"id": 1, "type": "audio", "lang": "tur", "title": "Türkçe", "selected": true, "demux-channel-count": 6},
              {"id": 2, "type": "audio", "lang": "eng"},
              {"id": 1, "type": "sub", "lang": "tr", "external": true, "title": ""}
            ]
            """.trimIndent(),
        )
        val state = PlayerStateMapping.apply(PlayerState(), "track-list", tracks)
        assertEquals(4, state.tracks.size)
        assertEquals(listOf(1, 2), state.audioTracks.map { it.id })
        assertEquals(6, state.audioTracks.first().channels)
        val sub = state.subtitleTracks.single()
        assertTrue(sub.external)
        assertNull(sub.title)
        assertFalse(sub.selected)
    }

    @Test
    fun `durum ozellikleri ve bekleme gostergesi`() {
        var state = PlayerState()
        state = PlayerStateMapping.apply(state, "pause", mapper.readTree("true"))
        state = PlayerStateMapping.apply(state, "volume", mapper.readTree("65.5"))
        state = PlayerStateMapping.apply(state, "paused-for-cache", mapper.readTree("true"))
        assertTrue(state.paused)
        assertEquals(65.5, state.volume)
        assertTrue(state.buffering)
        state = PlayerStateMapping.apply(state, "paused-for-cache", mapper.readTree("false"))
        state = PlayerStateMapping.apply(state, "seeking", mapper.readTree("true"))
        assertTrue(state.buffering)
        state = PlayerStateMapping.apply(state, "seeking", mapper.readTree("false"))
        assertFalse(state.buffering)
        // Süre yoksa (canlı yayın) null
        assertNull(PlayerStateMapping.apply(state, "duration", null).duration)
    }

    @Test
    fun `komutlar surec yokken atilir, varken siraya girer ve arayuz hemen guncellenir`() {
        val controls = PlayerControls()
        controls.togglePause()
        assertTrue(controls.queue.isEmpty())
        assertFalse(controls.state.value.paused)

        var woken = 0
        controls.wake = { woken++ }
        controls.update { it.copy(position = 100.0) }
        controls.togglePause()
        controls.seekBy(-10.0)
        controls.setVolume(500.0)
        assertEquals(3, controls.queue.size)
        assertEquals(3, woken)
        assertTrue(controls.state.value.paused)
        assertEquals(90.0, controls.state.value.position)
        assertEquals(PlayerControls.MAX_VOLUME, controls.state.value.volume)
    }

    @Test
    fun `gomulu oynatmada pencere ve arayuz secenekleri yerine wid verilir`() {
        val ui = PlayerUi("C:/ui")
        val args = MpvLoad.hostArgs("pipe", ui, emptyList(), listOf("--geometry=800x600"), embed = 4242L)
        assertTrue("--wid=4242" in args)
        assertTrue("--input-default-bindings=no" in args)
        assertFalse(args.any { it.startsWith("--config-dir") })
        assertFalse("--geometry=800x600" in args)

        val legacy = MpvArgs.build(PlayRequest(url = "http://x/a.m3u8", title = "A", ui = ui, embed = 7L), "pipe", listOf("--geometry=800x600"))
        assertTrue("--wid=7" in legacy)
        assertFalse(legacy.any { it.startsWith("--config-dir") })
        assertEquals("http://x/a.m3u8", legacy.last())
    }

    @Test
    fun `onizleme yuksekligi en-boy oranindan cift sayiya yuvarlanir`() {
        assertEquals(162, ThumbnailGenerator.heightFor(1920, 1080))
        assertEquals(122, ThumbnailGenerator.heightFor(1920, 816))
        // Boyut bilinmiyorsa 16:9 varsayılır
        assertEquals(162, ThumbnailGenerator.heightFor(0, 0))
    }

    @Test
    fun `altyazi boyutu dosya basina verilir, sureci yeniden baslatmaz`() {
        val (perFile, process) = MpvLoad.splitExtraArgs(listOf("--sub-scale=1.25", "--mute=yes"))
        assertEquals(listOf("sub-scale" to "1.25"), perFile)
        assertEquals(listOf("--mute=yes"), process)
    }

    @Test
    fun `gomulu istekte surec anahtari arayuzu yok sayar ve pencereyi icerir`() {
        val ui = PlayerUi("C:/ui")
        val key = HostKey.of(PlayRequest(url = "u", title = "t", ui = ui, embed = 9L))
        assertNull(key.ui)
        assertEquals(9L, key.embed)
        // Başka bir tuvale (yeni pencere kimliği) yüklenen dosya yeni süreç ister
        assertFalse(key == HostKey.of(PlayRequest(url = "u", title = "t", ui = ui, embed = 10L)))
    }
}
