package seyir.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginOffersTest {
    private fun offers(): Pair<PluginOffers, File> {
        val file = File(Files.createTempDirectory("offers").toFile(), "plugin-offers.json")
        return PluginOffers(file) to file
    }

    @Test
    fun `depo icin kayit yoksa kurulu olmayan her eklenti yenidir`() {
        val (offers, _) = offers()
        assertEquals(listOf("C"), offers.newOnes("https://x/repo.json", listOf("A", "B", "C"), installed = setOf("A", "B")))
    }

    @Test
    fun `secim penceresinde gorulen ve kaldirilan eklentiler kurulmaz`() {
        val (offers, file) = offers()
        val repo = "https://x/repo.json"
        offers.markSeen(repo, listOf("A", "B", "C"))
        offers.markRemoved("A")
        // Depoya D eklendi; B ve C görülmüştü (C bilerek seçilmemişti), A kaldırılmıştı
        assertEquals(listOf("D"), offers.newOnes("$repo/", listOf("A", "B", "C", "D"), installed = setOf("B")))
        // Kalıcıdır
        val reloaded = PluginOffers(file)
        assertTrue(reloaded.isRemoved("A"))
        assertEquals(setOf("A", "B", "C"), reloaded.seen(repo))
        // Elle yeniden kurulunca kaldırılmış sayılmaz
        reloaded.clearRemoved("A")
        assertTrue(!PluginOffers(file).isRemoved("A"))
    }
}
