package seyir.core

import com.lagradost.cloudstream3.MainAPI
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import seyir.core.net.Network
import seyir.core.net.NetworkSettings
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainTrackerTest {
    private val tracker = DomainTracker(File.createTempFile("domains", ".json").apply { deleteOnExit() })

    @Test
    fun acceptsTldAndNumberChanges() {
        assertTrue(tracker.looksLikeSameSite("ornekfilm.de", "ornekfilm.to"))
        assertTrue(tracker.looksLikeSameSite("www.ornekdizi.im", "www.ornekdizi.tel"))
        assertTrue(tracker.looksLikeSameSite("videosite6.com", "videosite8.com"))
        assertTrue(tracker.looksLikeSameSite("www.mediaportal2.site", "www.mediaportal2.biz"))
    }

    @Test
    fun rejectsParkingAndUnrelatedSites() {
        assertFalse(tracker.looksLikeSameSite("ornekfilm.cx", "ww38.ornekfilm.cx"))
        assertFalse(tracker.looksLikeSameSite("www.ornekfilm.my", "virtualsouthport.co.uk"))
        assertFalse(tracker.looksLikeSameSite("mediaportal.vip", "videosite.to"))
    }

    private class FakeApi(override var mainUrl: String) : MainAPI() {
        override var name = "Sahte"
    }

    @Test
    fun savedDomainIsDroppedWhenPluginUrlChanges() {
        val store = File.createTempFile("domains", ".json").apply { deleteOnExit() }
        store.writeText("""{"Sahte":{"url":"https://site.net","pluginUrl":"https://site.com"},"Eski":"https://x.org"}""")

        val sameVersion = FakeApi("https://site.com")
        DomainTracker(store).applySaved(sameVersion)
        assertEquals("https://site.net", sameVersion.mainUrl)

        // Eklenti güncellendi, kendi adresi artık .app: eski kayıt uygulanmamalı ve silinmeli
        val updated = FakeApi("https://site.app")
        DomainTracker(store).applySaved(updated)
        assertEquals("https://site.app", updated.mainUrl)
        assertFalse("Sahte" in store.readText())
    }

    @Test
    fun recentlyCheckedSitesAreSkippedAndCheckTimeIsPersisted() = runBlocking {
        val hits = AtomicInteger()
        var markerSeen: String? = null
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            markerSeen = ex.requestHeaders.getFirst(NO_SOLVE_HEADER)
            val bytes = "ok".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            Network.install(NetworkSettings(fragmentTls = false, dnsOverHttps = null))
            val store = File.createTempFile("domains", ".json").apply { deleteOnExit() }
            var clock = 1_700_000_000_000L
            val api = FakeApi("http://127.0.0.1:${server.address.port}")

            val first = DomainTracker(store) { clock }
            assertNull(first.refresh(api))
            assertEquals(1, hits.get())
            // İşaret başlığı çözücü içindir, siteye gitmemeli
            assertNull(markerSeen)

            clock += 60_000
            first.refresh(api)
            assertEquals(1, hits.get(), "12 saat dolmadan yeniden denetlenmemeli")

            // Kayıt diske yazıldı: yeni örnek de atlar
            DomainTracker(store) { clock }.refresh(api)
            assertEquals(1, hits.get())

            DomainTracker(store) { clock }.refresh(api, force = true)
            assertEquals(2, hits.get())

            clock += 13 * 60 * 60 * 1000L
            DomainTracker(store) { clock }.refresh(api)
            assertEquals(3, hits.get())
        } finally {
            server.stop(0)
        }
    }
}
