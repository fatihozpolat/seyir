package seyir.core.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsQualityTest {
    private fun variant(height: Int?, bandwidth: Long, audioOnly: Boolean = false) = HlsVariant(
        url = "https://x/$height-$bandwidth.m3u8", bandwidth = bandwidth, width = null, height = height,
        codecs = null, frameRate = null, audioGroup = null, audioOnly = audioOnly,
    )

    private val ladder = listOf(
        variant(240, 250_000), variant(1080, 6_000_000), variant(480, 800_000), variant(720, 2_000_000),
    )

    @Test
    fun layersAreSortedDescendingByHeight() {
        assertEquals(listOf(1080, 720, 480, 240), HlsQuality.layers(ladder).map { it.height })
    }

    @Test
    fun sameHeightKeepsHighestBandwidth() {
        val layers = HlsQuality.layers(listOf(variant(720, 1_500_000), variant(720, 2_500_000), variant(720, 2_000_000), variant(480, 700_000)))
        assertEquals(listOf(720, 480), layers.map { it.height })
        assertEquals(2_500_000L, layers[0].bandwidth)
    }

    @Test
    fun audioOnlyAndZeroBandwidthAreDropped() {
        val layers = HlsQuality.layers(listOf(variant(null, 40_000, audioOnly = true), variant(360, 0), variant(360, 500_000)))
        assertEquals(1, layers.size)
        assertEquals(500_000L, layers[0].bandwidth)
    }

    @Test
    fun chooseHonoursPreferenceWithoutExceedingIt() {
        val layers = HlsQuality.layers(ladder)
        assertEquals(1080, HlsQuality.choose(layers, QualityTarget.Highest)?.height)
        assertEquals(240, HlsQuality.choose(layers, QualityTarget.Lowest)?.height)
        assertEquals(720, HlsQuality.choose(layers, QualityTarget.MaxHeight(720))?.height)
        // Tam eşleşme yoksa tercihi aşmayan en yüksek katman
        assertEquals(480, HlsQuality.choose(layers, QualityTarget.MaxHeight(600))?.height)
        // Hiçbiri tercihin altında değilse en düşüğü
        assertEquals(240, HlsQuality.choose(layers.filter { it.height!! >= 240 }, QualityTarget.MaxHeight(144))?.height)
        assertNull(HlsQuality.choose(emptyList(), QualityTarget.Highest))
    }

    @Test
    fun mpvArgsUseExplicitBandwidthAndNothingForHighest() {
        val layers = HlsQuality.layers(ladder)
        assertTrue(HlsQuality.mpvArgs(layers, QualityTarget.Highest).isEmpty())
        assertEquals(listOf("--hls-bitrate=2000000"), HlsQuality.mpvArgs(layers, QualityTarget.MaxHeight(720)))
        assertEquals(listOf("--hls-bitrate=250000"), HlsQuality.mpvArgs(layers, QualityTarget.Lowest))
        assertTrue(HlsQuality.mpvArgs(emptyList(), QualityTarget.Lowest).isEmpty())
    }

    @Test
    fun preferenceIdsRoundTripAndFallBackToHighest() {
        QualityPreference.entries.forEach { assertEquals(it, QualityPreference.fromId(it.id)) }
        assertEquals(QualityPreference.HIGHEST, QualityPreference.fromId(null))
        assertEquals(QualityPreference.HIGHEST, QualityPreference.fromId("bilinmeyen"))
    }
}
