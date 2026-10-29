package seyir.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateThrottleTest {
    @Test
    fun `first call passes then calls inside the interval are dropped`() {
        var now = 1_000L
        val throttle = UpdateThrottle(intervalMs = 250) { now }
        assertTrue(throttle.ready())
        now += 100
        assertFalse(throttle.ready())
        now += 149
        assertFalse(throttle.ready())
        now += 1
        assertTrue(throttle.ready())
    }

    @Test
    fun `chunked download produces about four updates per second`() {
        var now = 0L
        val throttle = UpdateThrottle { now }
        // 10 sn boyunca her 10 ms'de bir parça gelir
        var updates = 0
        repeat(1000) {
            now += 10
            if (throttle.ready()) updates++
        }
        assertEquals(40, updates, "10 sn içinde saniyede 4 güncelleme beklenir")
    }
}
