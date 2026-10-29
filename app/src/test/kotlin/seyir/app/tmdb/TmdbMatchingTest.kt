package seyir.app.tmdb

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbMatchingTest {
    private fun info(
        id: Int,
        title: String,
        year: Int?,
        kind: TmdbKind = TmdbKind.Movie,
        original: String? = null,
    ) = TmdbInfo(id, kind, title, original, year, overview = "özet $id", posterUrl = "p$id", backdropUrl = "b$id", rating = 7.5, voteCount = 100)

    @Test
    fun normalizesCaseAccentsAndTurkishLetters() {
        assertEquals(normalizeTitle("Şüphe: Büyük  Oyun!"), normalizeTitle("suphe buyuk oyun"))
        assertEquals("isparta", normalizeTitle("ISPARTA"))
        assertEquals("istanbul", normalizeTitle("İstanbul"))
        assertEquals("el camino", normalizeTitle("El Camino (2019)"))
        assertEquals("el camino", normalizeTitle("El Camino Full İzle"))
        assertEquals("ask 101", normalizeTitle("Aşk 101"))
    }

    @Test
    fun readsYearFromName() {
        assertEquals(2019, yearInName("El Camino (2019)"))
        assertNull(yearInName("Blade Runner 2049"))
    }

    @Test
    fun picksExactTitleWithMatchingYear() {
        val candidates = listOf(info(1, "Dune", 1984), info(2, "Dune", 2021), info(3, "Dune: Part Two", 2024))
        assertEquals(2, pickBest(candidates, "Dune", 2021)?.id)
        assertEquals(2, pickBest(candidates, "Dune (2021)")?.id)
    }

    @Test
    fun acceptsOffByOneYear() {
        assertEquals(5, pickBest(listOf(info(5, "Ölüm Çağrısı", 2020)), "Ölüm Çağrısı", 2021)?.id)
    }

    @Test
    fun rejectsDistantYear() {
        assertNull(pickBest(listOf(info(1, "Dune", 1984)), "Dune", 2021))
    }

    @Test
    fun rejectsAmbiguousTitleWithoutYear() {
        val candidates = listOf(info(1, "Dune", 1984), info(2, "Dune", 2021))
        assertNull(pickBest(candidates, "Dune"))
        // Tek eşleşme varsa yıl olmadan da kabul edilir
        assertEquals(2, pickBest(listOf(info(2, "Dune", 2021), info(3, "Dune Part Two", 2024)), "Dune")?.id)
    }

    @Test
    fun rejectsSameYearMovieAndSeries() {
        val candidates = listOf(info(1, "Fargo", 2014, TmdbKind.Tv), info(2, "Fargo", 2014, TmdbKind.Movie))
        assertNull(pickBest(candidates, "Fargo", 2014))
    }

    @Test
    fun rejectsPartialOrDifferentTitle() {
        val candidates = listOf(info(1, "The Matrix Reloaded", 2003), info(2, "Matrix", 1993))
        assertNull(pickBest(candidates, "The Matrix", 1999))
        assertNull(pickBest(emptyList(), "Whatever"))
        assertNull(pickBest(candidates, "   "))
    }

    @Test
    fun matchesOriginalTitle() {
        val candidates = listOf(info(9, "Parazit", 2019, original = "기생충"), info(8, "Parasite", 2019))
        assertEquals(9, pickBest(candidates, "기생충", 2019)?.id)
        assertEquals(8, pickBest(candidates, "Parasite", 2019)?.id)
    }

    @Test
    fun mergeNeverOverridesProviderData() {
        val tmdb = info(1, "X", 2020)
        val merged = mergeDetail("own-poster", "own-backdrop", "own plot", 2018, 6.1, tmdb)
        assertEquals("own-poster", merged.posterUrl)
        assertEquals("own-backdrop", merged.backdropUrl)
        assertEquals("own plot", merged.plot)
        assertEquals(2018, merged.year)
        assertEquals(6.1, merged.score)
        assertFalse(merged.posterFromTmdb || merged.backdropFromTmdb || merged.scoreFromTmdb)
    }

    @Test
    fun mergeFillsOnlyMissingFields() {
        val tmdb = info(1, "X", 2020)
        val merged = mergeDetail("", null, "  ", null, null, tmdb)
        assertEquals("p1", merged.posterUrl)
        assertEquals("b1", merged.backdropUrl)
        assertEquals("özet 1", merged.plot)
        assertEquals(2020, merged.year)
        assertEquals(7.5, merged.score)
        assertTrue(merged.posterFromTmdb && merged.backdropFromTmdb && merged.scoreFromTmdb)

        val partial = mergeDetail("own-poster", null, "own plot", 2018, null, tmdb)
        assertEquals("own-poster", partial.posterUrl)
        assertFalse(partial.posterFromTmdb)
        assertEquals("b1", partial.backdropUrl)
    }

    @Test
    fun mergeWithoutTmdbKeepsProviderValues() {
        val merged = mergeDetail(null, null, null, null, null, null)
        assertNull(merged.posterUrl)
        assertNull(merged.plot)
        assertNull(merged.score)
    }

    @Test
    fun choosesKindsFromProviderType() {
        assertEquals(listOf(TmdbKind.Movie), kindsFor("Movie"))
        assertEquals(listOf(TmdbKind.Tv), kindsFor("Anime"))
        assertEquals(listOf(TmdbKind.Movie, TmdbKind.Tv), kindsFor("Documentary", 1))
        assertEquals(listOf(TmdbKind.Tv), kindsFor("Documentary", 8))
        assertTrue(kindsFor("Live").isEmpty())
    }

    @Test
    fun detectsKeyForm() {
        assertTrue(isBearerToken("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2ln"))
        assertFalse(isBearerToken("0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun usesUiLanguageForTmdb() {
        assertEquals("tr-TR", tmdbLanguage(Locale.forLanguageTag("tr")))
        assertEquals("en-US", tmdbLanguage(Locale.GERMANY))
    }

    @Test
    fun parsesSearchResponse() {
        val json = """
            {"page":1,"results":[
              {"id":10,"title":"Dune","original_title":"Dune","release_date":"2021-09-15","poster_path":"/a.jpg","backdrop_path":null,
               "overview":"Çöl gezegeni","vote_average":7.8,"vote_count":900},
              {"id":11,"title":"Boş","release_date":"","poster_path":null,"overview":"","vote_average":0,"vote_count":0},
              {"title":"Kimliksiz"}
            ]}
        """.trimIndent()
        val parsed = TmdbClient.parseSearch(json, TmdbKind.Movie)
        assertEquals(2, parsed.size)
        val first = parsed.first()
        assertEquals(2021, first.year)
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", first.posterUrl)
        assertNull(first.backdropUrl)
        assertEquals(7.8, first.rating)
        val empty = parsed[1]
        assertNull(empty.year)
        assertNull(empty.overview)
        assertNull(empty.rating)
        assertNotNull(parsed.firstOrNull { it.id == 10 })
        assertTrue(TmdbClient.parseSearch("not json", TmdbKind.Tv).isEmpty())
    }
}
