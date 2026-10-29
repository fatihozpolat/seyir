package seyir.app

import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProviderRegistryTest {
    private fun api(providerName: String): MainAPI = object : MainAPI() {
        override var name = providerName
    }

    private fun registry() = ProviderRegistry(uiContext = Dispatchers.Unconfined, initialStatus = "yükleniyor")

    @Test
    fun `ayni adli saglayicilardan yuksek surumlusu kalir`() {
        val old = api("Kanal D")
        val newer = api("Kanal D")
        val other = api("Baska")
        val result = ProviderRegistry.dedupeByName(
            listOf(ProviderCandidate(old, 1, 0), ProviderCandidate(other, 1, 1), ProviderCandidate(newer, 3, 2))
        )
        assertEquals(2, result.size)
        assertSame(newer, result.first { it.name == "Kanal D" })
    }

    @Test
    fun `surum esitse once yuklenen kalir`() {
        val first = api("Ayni")
        val second = api("Ayni")
        val result = ProviderRegistry.dedupeByName(listOf(ProviderCandidate(second, 2, 5), ProviderCandidate(first, 2, 1)))
        assertSame(first, result.single())
    }

    @Test
    fun `publish adlari tekillestirir ada gore siralar ve yalniz yenileri bildirir`() {
        val registry = registry()
        val b = api("b-site")
        val a = api("A-site")
        val seen = mutableListOf<MainAPI>()
        val first = runBlocking {
            registry.publish({ listOf(ProviderCandidate(b, 1, 0), ProviderCandidate(a, 1, 1), ProviderCandidate(api("A-site"), 1, 2)) }, markReady = true) { seen += it }
        }
        assertEquals(listOf("A-site", "b-site"), first.map { it.name })
        assertEquals(first, registry.providers)
        assertTrue(registry.ready)
        assertEquals(2, seen.size)

        seen.clear()
        val c = api("c-site")
        runBlocking { registry.publish({ listOf(ProviderCandidate(a, 1, 0), ProviderCandidate(c, 1, 1)) }) { seen += it } }
        assertEquals(listOf(c), seen)
        assertEquals(listOf("A-site", "c-site"), registry.providers.map { it.name })
    }

    @Test
    fun `hazir olma yalniz istenince acilir`() {
        val registry = registry()
        runBlocking { registry.publish({ emptyList() }) }
        assertFalse(registry.ready)
        runBlocking { registry.announce("hazır") }
        assertEquals("hazır", registry.status)
    }

    @Test
    fun `alan adi degisimi listeyi ezmez ve surumu arttirir`() {
        val registry = registry()
        val a = api("A")
        runBlocking { registry.publish({ listOf(ProviderCandidate(a, 1, 0)) }) }
        val before = registry.domainsVersion
        runBlocking { registry.domainsChanged("taşındı") }
        assertEquals(before + 1, registry.domainsVersion)
        assertEquals(listOf(a), registry.providers)
        assertEquals("taşındı", registry.status)
    }

    @Test
    fun `eszamanli yayinlar sirayla uygulanir`() {
        val registry = registry()
        runBlocking {
            (1..20).map { n ->
                async(Dispatchers.Default) { registry.publish({ listOf(ProviderCandidate(api("p$n"), 1, 0)) }) }
            }.awaitAll()
        }
        // Son yazan kazanır ama liste her zaman tek bir yayının tam anlık görüntüsüdür
        assertEquals(1, registry.providers.size)
    }

    @Test
    fun `adaylar kilit alindiktan sonra hesaplanir`() {
        val registry = registry()
        val a = api("A")
        val b = api("B")
        // Kaynak liste, ilk yayın kilidi beklerken değişir; ikinci yayın güncel listeyi görmeli
        var source = listOf(ProviderCandidate(a, 1, 0))
        runBlocking {
            val first = async(Dispatchers.Default) { registry.publish({ source }) }
            first.await()
            source = listOf(ProviderCandidate(b, 1, 0))
            registry.publish({ source })
        }
        assertEquals(listOf("B"), registry.providers.map { it.name })

        // Aday üretici yayın çağrısı yapılırken değil, kilit içinde çalışır
        var evaluated = false
        val lazy = { evaluated = true; emptyList<ProviderCandidate>() }
        assertFalse(evaluated)
        runBlocking { registry.publish(lazy) }
        assertTrue(evaluated)
    }
}
