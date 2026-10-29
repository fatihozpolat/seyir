package seyir.core.net

import okhttp3.Dns
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class AddressOrderDnsTest {
    private val v6 = InetAddress.getByName("2606:4700::1")
    private val v4 = InetAddress.getByName("104.16.0.1")
    private val both = Dns { listOf(v6, v4) }

    @Test
    fun dropsIpv6WithoutRoute() {
        assertEquals(listOf(v4), Network.AddressOrderDns(both) { false }.lookup("x"))
    }

    @Test
    fun putsIpv4FirstWhenIpv6Works() {
        assertEquals(listOf(v4, v6), Network.AddressOrderDns(both) { true }.lookup("x"))
    }

    @Test
    fun keepsIpv6OnlyHosts() {
        assertEquals(listOf(v6), Network.AddressOrderDns(Dns { listOf(v6) }) { false }.lookup("x"))
    }
}
