package seyir.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchGroupingTest {
    private data class Hit(val name: String, val year: Int? = null)

    private fun group(vararg results: Pair<String, List<Hit>>) =
        groupByTitle(results.toList(), { it.name }, { it.year })

    @Test
    fun `normalizeTitle buyuk kucuk harf aksan ve noktalamayi yok sayar`() {
        assertEquals("spiderman", normalizeTitle("Spider-Man"))
        assertEquals("spiderman", normalizeTitle("  SPIDER MAN!  "))
        assertEquals("amelie", normalizeTitle("Amélie"))
        assertEquals("elcamino", normalizeTitle("El Camino (2019)"))
    }

    @Test
    fun `normalizeTitle Turkce harfleri katlar`() {
        assertEquals("isikli sehir gucu".replace(" ", ""), normalizeTitle("IŞIKLI Şehir Gücü"))
        assertEquals(normalizeTitle("İstanbul Çöpçüsü Ğ"), normalizeTitle("istanbul copcusu g"))
        assertEquals(normalizeTitle("Işık"), normalizeTitle("isik"))
        assertEquals(normalizeTitle("ışık"), normalizeTitle("ISIK"))
    }

    @Test
    fun `yearFromTitle yalniz sondaki yili okur`() {
        assertEquals(2019, yearFromTitle("El Camino (2019)"))
        assertNull(yearFromTitle("El Camino"))
        assertNull(yearFromTitle("2019 (film)"))
    }

    @Test
    fun `ayni baslik ve ayni yil tek grupta toplanir`() {
        val groups = group(
            "A" to listOf(Hit("Kurtlar Vadisi", 2003)),
            "B" to listOf(Hit("KURTLAR VADİSİ", 2003)),
            "C" to listOf(Hit("Başka Dizi", 2003)),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("A", "B"), groups[0].providers)
        assertEquals("A", groups[0].primary.provider)
        assertEquals(2003, groups[0].year)
    }

    @Test
    fun `yillar farkliysa ayri kalir`() {
        val groups = group(
            "A" to listOf(Hit("Dune", 1984)),
            "B" to listOf(Hit("Dune", 2021)),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `yili olmayan sonuc ada gore birlesir`() {
        val groups = group(
            "A" to listOf(Hit("Dune", 2021)),
            "B" to listOf(Hit("Dune")),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("A", "B"), groups[0].providers)
        // Yılsız sonuç önce gelse de grup, yıllı üyeyle yıl kazanır
        val reversed = group(
            "B" to listOf(Hit("Dune")),
            "A" to listOf(Hit("Dune", 2021)),
        )
        assertEquals(1, reversed.size)
        assertEquals(2021, reversed[0].year)
        assertEquals("B", reversed[0].primary.provider)
    }

    @Test
    fun `ayni saglayicinin iki sonucu saglayici listesinde tek kez gorunur`() {
        val groups = group("A" to listOf(Hit("Dune", 2021), Hit("DUNE", 2021)))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].entries.size)
        assertEquals(listOf("A"), groups[0].providers)
    }

    @Test
    fun `giris sirasi korunur ve adi bos kalanlar birlesmez`() {
        val groups = group(
            "A" to listOf(Hit("Zeta"), Hit("???"), Hit("Alfa")),
            "B" to listOf(Hit("!!!"), Hit("alfa")),
        )
        assertEquals(listOf("zeta", "", "alfa", ""), groups.map { it.key })
        assertEquals(listOf("A", "B"), groups[2].providers)
    }
}
