package seyir.core.player

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvIpcTest {
    private val mapper = jacksonObjectMapper()

    /** Yazılan komutlara önceden hazırlanmış satırlarla karşılık veren sahte kanal. */
    private class FakeTransport(private val reply: (requestId: Long, command: List<String>) -> String) : IpcTransport {
        private val mapper = jacksonObjectMapper()
        private val outgoing = ArrayDeque<Byte>()
        val written = mutableListOf<String>()
        var chunkSize = Int.MAX_VALUE

        override fun write(bytes: ByteArray) {
            val node = mapper.readTree(bytes)
            val id = node.get("request_id").asLong()
            val command = node.get("command").map { it.asText() }
            written += command.joinToString(" ")
            reply(id, command).toByteArray().forEach { outgoing.addLast(it) }
        }

        override fun read(buffer: ByteArray): Int {
            if (outgoing.isEmpty()) return -1
            val count = minOf(buffer.size, outgoing.size, chunkSize)
            repeat(count) { buffer[it] = outgoing.removeFirst() }
            return count
        }

        override fun close() {}
    }

    @Test
    fun `parses a success response`() {
        val message = MpvMessageParser.parse("""{"data":12.5,"request_id":3,"error":"success"}""")
        val response = assertIs<MpvResponse>(message)
        assertEquals(3, response.requestId)
        assertTrue(response.isSuccess)
        assertEquals(12.5, response.data?.asDouble())
    }

    @Test
    fun `parses an error response`() {
        val response = assertIs<MpvResponse>(
            MpvMessageParser.parse("""{"request_id":7,"error":"property unavailable"}"""),
        )
        assertFalse(response.isSuccess)
        assertEquals("property unavailable", response.error)
        assertNull(response.data)
    }

    @Test
    fun `parses events with reason`() {
        val event = assertIs<MpvEvent>(MpvMessageParser.parse("""{"event":"end-file","reason":"eof"}"""))
        assertEquals("end-file", event.name)
        assertEquals("eof", event.reason)
        assertIs<MpvEvent>(MpvMessageParser.parse("""{"event":"file-loaded"}"""))
    }

    @Test
    fun `ignores garbage lines`() {
        assertNull(MpvMessageParser.parse(""))
        assertNull(MpvMessageParser.parse("   "))
        assertNull(MpvMessageParser.parse("not json"))
        assertNull(MpvMessageParser.parse("{broken"))
        assertNull(MpvMessageParser.parse("""{"foo":1}"""))
        assertNull(MpvMessageParser.parse("[1,2]"))
    }

    @Test
    fun `encodes commands as json lines`() {
        val line = MpvMessageParser.encodeCommand(5, listOf("sub-add", "http://x/a.vtt", "select", "Türkçe \"Altyazı\"", "tr"))
        val node = mapper.readTree(line)
        assertEquals(5, node.get("request_id").asLong())
        assertEquals("Türkçe \"Altyazı\"", node.get("command")[3].asText())
        assertFalse(line.contains('\n'))
    }

    @Test
    fun `skips interleaved events until request id matches`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport { id, _ ->
            """{"event":"file-loaded"}""" + "\n" +
                """{"request_id":${id + 100},"error":"success","data":1}""" + "\n" + // başka isteğin yanıtı
                """{"event":"playback-restart"}""" + "\n" +
                """{"request_id":$id,"error":"success","data":42.0}""" + "\n"
        }
        val client = MpvIpcClient(transport) { events += it.name }
        assertEquals(42.0, client.getDouble("time-pos"))
        assertEquals(listOf("file-loaded", "playback-restart"), events)
    }

    @Test
    fun `reassembles lines split across reads`() {
        val transport = FakeTransport { id, _ -> """{"request_id":$id,"error":"success","data":7.25}""" + "\n" }
        transport.chunkSize = 3
        assertEquals(7.25, MpvIpcClient(transport).getDouble("duration"))
    }

    @Test
    fun `handles several lines arriving in one read`() {
        var calls = 0
        val transport = FakeTransport { id, _ ->
            calls++
            // İlk çağrıda bir sonraki isteğin yanıtı da önceden gelmiş gibi davran
            """{"request_id":$id,"error":"success","data":1.0}""" + "\n"
        }
        val client = MpvIpcClient(transport)
        assertEquals(1.0, client.getDouble("a"))
        assertEquals(1.0, client.getDouble("b"))
        assertEquals(2, calls)
    }

    @Test
    fun `unavailable property gives null`() {
        val transport = FakeTransport { id, _ -> """{"request_id":$id,"error":"property unavailable"}""" + "\n" }
        assertNull(MpvIpcClient(transport).getDouble("time-pos"))
    }

    @Test
    fun `non numeric data gives null`() {
        val transport = FakeTransport { id, _ -> """{"request_id":$id,"error":"success","data":"x"}""" + "\n" }
        assertNull(MpvIpcClient(transport).getDouble("time-pos"))
    }

    @Test
    fun `closed stream raises IOException`() {
        val transport = FakeTransport { _, _ -> "" }
        assertFailsWith<IOException> { MpvIpcClient(transport).request(listOf("quit")) }
    }

    @Test
    fun `commands are sent with increasing ids`() {
        val ids = ByteArrayOutputStream()
        val transport = FakeTransport { id, _ ->
            ids.write(id.toInt())
            """{"request_id":$id,"error":"success"}""" + "\n"
        }
        val client = MpvIpcClient(transport)
        client.request(listOf("a"))
        client.request(listOf("b"))
        assertEquals(listOf(1, 2), ids.toByteArray().map { it.toInt() })
        assertEquals(listOf("a", "b"), transport.written)
    }
}
