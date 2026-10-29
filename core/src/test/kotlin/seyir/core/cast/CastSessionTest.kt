package seyir.core.cast

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sahte bir Cast alıcısına karşı oturum akışı: CONNECT → LAUNCH → LOAD → durum → komut → bitiş.
 * Gerçek alıcı TLS kullanır; burada yalnız çerçeveleme ve mesajlar sınanır, soket düz TCP'dir.
 */
class CastSessionTest {
    private val mapper = jacksonObjectMapper()
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val received = CopyOnWriteArrayList<Pair<String, JsonNode>>()
    private lateinit var peer: DataOutputStream
    private val peerReady = CountDownLatch(1)
    private var position = 30.0
    private var state = "PLAYING"

    @AfterTest
    fun tearDown() = server.close()

    private fun startReceiver() = Thread.ofVirtual().start {
        val socket = server.accept()
        val input = DataInputStream(socket.getInputStream())
        peer = DataOutputStream(socket.getOutputStream())
        peerReady.countDown()
        runCatching {
            while (true) {
                val bytes = ByteArray(input.readInt()).also { input.readFully(it) }
                val (destination, namespace, json) = decode(bytes)
                val payload = mapper.readTree(json)
                received += namespace to payload
                val id = payload.path("requestId").asInt()
                when (payload.path("type").asText()) {
                    "LAUNCH" -> send(
                        "receiver-0", CastConnection.NS_RECEIVER,
                        """{"type":"RECEIVER_STATUS","requestId":$id,"status":{"applications":[{"appId":"CC1AD845","transportId":"web-1","sessionId":"s1"}],"volume":{"level":0.4}}}""",
                    )
                    "LOAD" -> {
                        position = payload.path("currentTime").asDouble()
                        send("web-1", CastConnection.NS_MEDIA, mediaStatus(id))
                    }
                    "GET_STATUS" -> {
                        if (state == "PLAYING") position += 2
                        send("web-1", CastConnection.NS_MEDIA, mediaStatus(id))
                    }
                    "PAUSE" -> {
                        state = "PAUSED"
                        send("web-1", CastConnection.NS_MEDIA, mediaStatus(id))
                    }
                }
                check(destination.isNotEmpty())
            }
        }
    }

    private fun mediaStatus(id: Int, idleReason: String? = null) =
        """{"type":"MEDIA_STATUS","requestId":$id,"status":[{"mediaSessionId":7,"playerState":"$state",""" +
            (idleReason?.let { "\"idleReason\":\"$it\"," } ?: "") +
            """"currentTime":$position,"media":{"duration":5400.0}}]}"""

    @Test
    fun launchesLoadsReportsAndFinishes() {
        startReceiver()
        val statuses = CopyOnWriteArrayList<CastStatus>()
        val ended = CountDownLatch(1)
        var endFinished = false
        val device = CastDevice("Salon TV", "127.0.0.1", server.localPort)
        val connection = CastConnection(device) { Socket(it.host, it.port) }
        val session = CastSession.open(connection, device, object : CastListener {
            override fun onStatus(status: CastStatus) {
                statuses += status
            }

            override fun onEnd(status: CastStatus, finished: Boolean, error: String?) {
                endFinished = finished
                ended.countDown()
            }
        })
        session.load(
            CastMedia(
                url = "http://192.0.2.10:5000/r/x/y/index.m3u8",
                contentType = "application/x-mpegurl",
                title = "The Matrix",
                startSeconds = 30.0,
                tracks = listOf(CastTrack("http://192.0.2.10:5000/r/s/t/sub.vtt", "Türkçe", "tr")),
                activeTrack = 0,
            )
        )

        val load = received.first { it.second.path("type").asText() == "LOAD" }.second
        assertEquals("http://192.0.2.10:5000/r/x/y/index.m3u8", load.path("media").path("contentId").asText())
        assertEquals(30.0, load.path("currentTime").asDouble())
        assertEquals("text/vtt", load.path("media").path("tracks")[0].path("trackContentType").asText())
        assertEquals(1, load.path("activeTrackIds")[0].asInt())
        assertEquals(5400.0, session.status.duration)

        // Konum yoklamayla ilerler
        val deadline = System.currentTimeMillis() + 6_000
        while (session.status.position <= 30.0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(session.status.position > 30.0, "konum ilerlemedi: ${session.status}")

        session.pause()
        val pauseDeadline = System.currentTimeMillis() + 3_000
        while (session.status.state != "PAUSED" && System.currentTimeMillis() < pauseDeadline) Thread.sleep(50)
        assertEquals("PAUSED", session.status.state)
        assertTrue(received.any { it.second.path("type").asText() == "PAUSE" && it.second.path("mediaSessionId").asInt() == 7 })

        // Televizyonda video sona erer
        state = "IDLE"
        peerReady.await(1, TimeUnit.SECONDS)
        send("web-1", CastConnection.NS_MEDIA, mediaStatus(0, idleReason = "FINISHED"))
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        assertTrue(endFinished)
        assertTrue(statuses.isNotEmpty())
        // İlk mesaj alıcıya CONNECT olmalı
        assertEquals("CONNECT", received.first().second.path("type").asText())
    }

    // ---- çerçeve yardımcıları (alıcı tarafı) ----

    @Synchronized
    private fun send(source: String, namespace: String, json: String) {
        val out = ByteArrayOutputStream()
        fun varint(value: Int) {
            var v = value
            while (v and 0x7F.inv() != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v)
        }
        fun field(n: Int, text: String) {
            val b = text.toByteArray()
            varint((n shl 3) or 2)
            varint(b.size)
            out.write(b)
        }
        varint(8); varint(0)
        field(2, source)
        field(3, "sender-0")
        field(4, namespace)
        varint(40); varint(0)
        field(6, json)
        val bytes = out.toByteArray()
        peer.writeInt(bytes.size)
        peer.write(bytes)
        peer.flush()
    }

    private fun decode(bytes: ByteArray): Triple<String, String, String> {
        var pos = 0
        fun varint(): Int {
            var shift = 0
            var result = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        var destination = ""
        var namespace = ""
        var payload = ""
        while (pos < bytes.size) {
            val key = varint()
            if (key and 7 == 0) {
                varint()
                continue
            }
            val len = varint()
            val text = String(bytes, pos, len)
            pos += len
            when (key ushr 3) {
                3 -> destination = text
                4 -> namespace = text
                6 -> payload = text
            }
        }
        return Triple(destination, namespace, payload)
    }
}
