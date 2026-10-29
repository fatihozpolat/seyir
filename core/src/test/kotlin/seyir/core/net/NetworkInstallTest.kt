package seyir.core.net

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkInstallTest {
    @Test
    fun reinstallClosesPreviousClientAndExposesNewOne() {
        val previousDelay = Network.retireDelayMs
        Network.retireDelayMs = 0
        try {
            Network.install(NetworkSettings(fragmentTls = false, dnsOverHttps = DohProvider.GOOGLE))
            val old = Network.client
            Network.install(NetworkSettings(fragmentTls = false, dnsOverHttps = null))
            val fresh = Network.client
            assertNotSame(old, fresh)
            assertSame(fresh, Network.client)
            assertTrue(old.dispatcher.executorService.isShutdown, "eski istemcinin iş parçacığı havuzu durdurulmalı")
            assertTrue(!fresh.dispatcher.executorService.isShutdown)
        } finally {
            Network.retireDelayMs = previousDelay
        }
    }
}
