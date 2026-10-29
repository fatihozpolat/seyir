package seyir.core.hls

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsMasterParserTest {
    private val base = "https://cdn.example.com/video/master.m3u8".toHttpUrl()

    private val muxLike = """
        #EXTM3U
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2149280,CODECS="mp4a.40.2,avc1.64001f",RESOLUTION=1280x720,NAME="720"
        url_0/hd.m3u8
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=246440,CODECS="mp4a.40.5,avc1.42000d",RESOLUTION=320x184,NAME="240"
        url_2/ld.m3u8
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=6221600,CODECS="mp4a.40.2,avc1.640028",RESOLUTION=1920x1080,FRAME-RATE=29.970
        url_8/fhd.m3u8
    """.trimIndent()

    @Test
    fun parsesBandwidthResolutionCodecsFrameRateAndAbsoluteUrl() {
        val variants = HlsMasterParser.parse(muxLike, base)
        assertEquals(3, variants.size)
        val fhd = variants[2]
        assertEquals(6221600L, fhd.bandwidth)
        assertEquals(1920, fhd.width)
        assertEquals(1080, fhd.height)
        assertEquals("mp4a.40.2,avc1.640028", fhd.codecs)
        assertEquals(29.97, fhd.frameRate)
        // Göreli adres ana liste adresine göre mutlak çözülür
        assertEquals("https://cdn.example.com/video/url_8/fhd.m3u8", fhd.url)
        assertEquals("1080p", fhd.label)
    }

    @Test
    fun resolvesRootRelativeAndAbsoluteUrls() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360
            /abs/path/low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            https://other.example.net/hi.m3u8?token=a,b
        """.trimIndent()
        val urls = HlsMasterParser.parse(text, base).map { it.url }
        assertEquals(listOf("https://cdn.example.com/abs/path/low.m3u8", "https://other.example.net/hi.m3u8?token=a,b"), urls)
    }

    @Test
    fun keepsRawUrlWhenBaseIsMissing() {
        val variants = HlsMasterParser.parse(muxLike)
        assertEquals("url_0/hd.m3u8", variants[0].url)
    }

    @Test
    fun toleratesByteOrderMarkAndCrLf() {
        val text = "﻿" + muxLike.replace("\n", "\r\n")
        val variants = HlsMasterParser.parse(text, base)
        assertEquals(3, variants.size)
        assertEquals("https://cdn.example.com/video/url_0/hd.m3u8", variants[0].url)
        assertTrue(HlsMasterParser.isMaster(text))
    }

    @Test
    fun quotedAttributeWithCommasDoesNotBreakFollowingAttributes() {
        val attrs = HlsMasterParser.parseAttributes("""BANDWIDTH=900000,CODECS="mp4a.40.2, avc1.4d401f",RESOLUTION=960x540,AUDIO="grp,1"""")
        assertEquals("900000", attrs["BANDWIDTH"])
        assertEquals("mp4a.40.2, avc1.4d401f", attrs["CODECS"])
        assertEquals("960x540", attrs["RESOLUTION"])
        assertEquals("grp,1", attrs["AUDIO"])
    }

    @Test
    fun acceptsQuotedResolutionAndLowerCaseSeparator() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=\"1280X720\"\nv.m3u8\n"
        assertEquals(720, HlsMasterParser.parse(text, base).single().height)
    }

    @Test
    fun mediaGroupsAndIFrameStreamsAreNotVariants() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Turkce",LANGUAGE="tr",DEFAULT=YES,URI="audio/tr.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=263851,CODECS="mp4a.40.2, avc1.4d400d",RESOLUTION=416x234,AUDIO="aud",SUBTITLES="subs"
            gear1/prog.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=28451,CODECS="avc1.4d400d",URI="gear1/iframe.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1924009,CODECS="mp4a.40.2, avc1.4d401f",RESOLUTION=1920x1080,AUDIO="aud",SUBTITLES="subs"
            gear5/prog.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=669554,CODECS="avc1.4d401f",URI="gear5/iframe.m3u8"
        """.trimIndent()
        val variants = HlsMasterParser.parse(text, base)
        assertEquals(listOf("gear1/prog.m3u8", "gear5/prog.m3u8").map { "https://cdn.example.com/video/$it" }, variants.map { it.url })
        assertEquals("aud", variants[0].audioGroup)
    }

    @Test
    fun urlMayBeSeparatedFromTagByCommentLine() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=854x480\n# yorum\n\nlow.m3u8\n"
        assertEquals(1, HlsMasterParser.parse(text, base).size)
    }

    @Test
    fun mediaPlaylistIsNotMaster() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nseg1.ts\n#EXT-X-ENDLIST\n"
        assertFalse(HlsMasterParser.isMaster(media))
        assertTrue(HlsMasterParser.parse(media, base).isEmpty())
        assertTrue(HlsMasterParser.parse("<html>hata</html>", base).isEmpty())
        assertTrue(HlsMasterParser.parse("", base).isEmpty())
    }

    @Test
    fun audioOnlyVariantIsDetectedByCodecs() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=41457,CODECS="mp4a.40.2"
            gear0/prog.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=263851,CODECS="mp4a.40.2, avc1.4d400d",RESOLUTION=416x234
            gear1/prog.m3u8
        """.trimIndent()
        val variants = HlsMasterParser.parse(text, base)
        assertTrue(variants[0].audioOnly)
        assertFalse(variants[1].audioOnly)
        assertEquals(listOf(234), HlsQuality.layers(variants).map { it.height })
    }

    @Test
    fun missingBandwidthFallsBackToAverageThenZero() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=800000,RESOLUTION=640x360\na.m3u8\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nb.m3u8\n"
        val variants = HlsMasterParser.parse(text, base)
        assertEquals(800000L, variants[0].bandwidth)
        assertEquals(0L, variants[1].bandwidth)
        // Bant genişliği bilinmeyen katman --hls-bitrate ile seçilemeyeceği için listede yer almaz
        assertEquals(listOf(360), HlsQuality.layers(variants).map { it.height })
    }

    @Test
    fun missingResolutionIsEstimatedFromBandwidth() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3500000\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=8000000\nb.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=300000\nc.m3u8\n"
        val variants = HlsMasterParser.parse(text, base)
        assertNull(variants[0].height)
        assertTrue(variants[0].heightEstimated)
        assertEquals(listOf("~720p", "~1080p", "~240p"), variants.map { it.label })
    }

    @Test
    fun labelsUseFourKForUhd() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=20000000,RESOLUTION=3840x2160\na.m3u8\n"
        assertEquals("4K", HlsMasterParser.parse(text, base).single().label)
    }
}
