package seyir.core

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.newEpisode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayablesTest {
    private fun ep(data: String, season: Int?, episode: Int?) = Playable("x", data, season, episode)

    private val series = listOf(
        ep("a", 1, 1), ep("b", 1, 2), ep("c", 1, 3),
        ep("d", 2, 1), ep("e", 2, 2),
    )

    @Test
    fun `next episode follows within the season`() {
        assertEquals("b", series.nextAfter(series[0])?.data)
        assertEquals("c", series.nextAfter(series[1])?.data)
    }

    @Test
    fun `last episode of a season falls through to the next season`() {
        assertEquals("d", series.nextAfter(series[2])?.data)
    }

    @Test
    fun `last episode of the series has no next`() {
        assertNull(series.nextAfter(series.last()))
    }

    @Test
    fun `previous episode goes back across seasons`() {
        assertEquals("d", series.previousBefore(series[4])?.data)
        assertEquals("c", series.previousBefore(series[3])?.data)
        assertNull(series.previousBefore(series[0]))
    }

    @Test
    fun `movie and unknown item have no next`() {
        assertNull(listOf(ep("m", null, null)).nextAfter(ep("m", null, null)))
        assertNull(series.nextAfter(ep("zzz", 1, 1)))
    }

    @Test
    fun `duplicate data entries are skipped`() {
        val list = listOf(ep("a", 1, 1), ep("a", 1, 2), ep("b", 1, 3))
        // aynı adresli ikinci kayıt "sıradaki" olamaz
        assertEquals("b", list.nextAfter(list[0])?.data)
    }

    @Test
    fun `duplicate data prefers the matching season and episode`() {
        val list = listOf(ep("a", 1, 1), ep("x", 1, 2), ep("a", 1, 3), ep("y", 1, 4))
        assertEquals("y", list.nextAfter(list[2])?.data)
    }

    @Test
    fun `episode labels follow the formatter and default to turkish`() = runBlocking {
        val api = object : MainAPI() {
            override var name = "test"
        }
        val episodes = with(api) {
            listOf(
                newEpisode("d1") { name = "Pilot"; season = 1; episode = 1 },
                newEpisode("d2") { season = 1; episode = 2 },
            )
        }
        assertEquals(listOf("S1 B1 · Pilot", "S1 B2"), episodes.toPlayables("Dizi", TurkishEpisodeLabels).map { it.title })
        val english = object : EpisodeLabels {
            override fun season(n: Int) = "S$n"
            override fun episode(n: Int) = "E$n"
        }
        assertEquals(listOf("S1 E1 · Pilot", "S1 E2"), episodes.toPlayables("Dizi", english).map { it.title })
    }
}
