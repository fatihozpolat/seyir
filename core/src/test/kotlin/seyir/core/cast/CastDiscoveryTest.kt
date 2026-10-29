package seyir.core.cast

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class CastDiscoveryTest {
    /** Google TV'nin gönderdiğine benzer yanıt: PTR, SRV, TXT ve A; adlar sıkıştırma işaretçileriyle. */
    @Test
    fun parsesGoogleTvAnswer() {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        fun name(vararg labels: String, pointer: Int? = null) {
            labels.forEach { out.write(it.length); out.write(it.toByteArray()) }
            if (pointer != null) u16(0xC000 or pointer) else out.write(0)
        }
        fun record(type: Int, body: ByteArrayOutputStream.() -> Unit) {
            u16(type); u16(0x8001); u16(0); u16(120)
            val data = ByteArrayOutputStream().apply(body).toByteArray()
            u16(data.size)
            out.write(data)
        }

        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(3)
        val serviceAt = out.size()
        name("_googlecast", "_tcp", "local") // 12. bayttan başlar
        record(12) {
            val label = "Google-TV-abc123"
            write(label.length); write(label.toByteArray())
            write(0xC0); write(serviceAt)
        }
        val instanceAt = out.size()
        name("Google-TV-abc123", pointer = serviceAt)
        record(33) {
            write(0); write(0); write(0); write(0); write(0x1F); write(0x49) // port 8009
            val host = "abc123"
            write(host.length); write(host.toByteArray()); write(0xC0); write(serviceAt + 17) // .local
        }
        name(pointer = instanceAt)
        record(16) {
            listOf("id=abc123", "md=TCL Google TV", "fn=Salon TV").forEach { write(it.length); write(it.toByteArray()) }
        }
        name("abc123", pointer = serviceAt + 17)
        record(1) { write(byteArrayOf(192.toByte(), 0, 2, 50)) }

        val devices = CastDiscovery.parse(out.toByteArray(), InetAddress.getByName("192.0.2.99"))
        assertEquals(listOf(CastDevice("Salon TV", "192.0.2.50", 8009, "TCL Google TV", "abc123")), devices)
    }

    @Test
    fun queryAsksForGoogleCastPtr() {
        val query = CastDiscovery.buildQuery()
        assertEquals(1, query[5].toInt())
        assertEquals("_googlecast", String(query, 13, 11))
    }
}
