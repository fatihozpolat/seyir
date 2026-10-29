package seyir.core.net

import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlsRecordSplitterTest {
    private val host = "media.example.com"

    /** JSSE'nin gerçekten ürettiği ClientHello kaydı. */
    private fun clientHello(): ByteArray {
        val engine = SSLContext.getDefault().createSSLEngine(host, 443).apply {
            useClientMode = true
            sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
        }
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.beginHandshake()
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    @Test
    fun splitsInsideServerNameAndKeepsPayload() {
        val hello = clientHello()
        val records = assertNotNull(TlsRecordSplitter.split(hello, 0, hello.size))
        assertEquals(2, records.size)

        val hostBytes = host.toByteArray()
        // İki kaydın hiçbiri alan adının tamamını içermemeli
        records.forEach { r -> assertTrue(String(r, Charsets.ISO_8859_1).indexOf(host) < 0) }

        // Kayıt başlıkları atılınca özgün el sıkışma iletisi aynen çıkmalı
        val joined = records.flatMap { r ->
            assertEquals(0x16, r[0].toInt() and 0xff)
            assertEquals(r.size - 5, ((r[3].toInt() and 0xff) shl 8) or (r[4].toInt() and 0xff))
            r.drop(5)
        }.toByteArray()
        assertContentEquals(hello.copyOfRange(5, hello.size), joined)
        assertTrue(hostBytes.isNotEmpty())
    }

    @Test
    fun ignoresNonTlsAndIncompleteRecords() {
        val http = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        assertNull(TlsRecordSplitter.split(http, 0, http.size))
        val hello = clientHello()
        assertNull(TlsRecordSplitter.split(hello, 0, 40))
    }

    @Test
    fun readsServerName() {
        val hello = clientHello()
        assertEquals(host, TlsRecordSplitter.serverName(hello, 0, hello.size))
        assertNull(TlsRecordSplitter.serverName("GET / HTTP/1.1 ...".toByteArray(), 0, 16))
    }
}
