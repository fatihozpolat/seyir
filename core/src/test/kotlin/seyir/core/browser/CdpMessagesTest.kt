package seyir.core.browser

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdpMessagesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun buildsRequestWithParamsAndSession() {
        val json = mapper.readTree(
            CdpMessages.request(7, "Network.getCookies", mapOf("urls" to listOf("https://a.com/")), "S1"),
        )
        assertEquals(7, json["id"].asInt())
        assertEquals("Network.getCookies", json["method"].asText())
        assertEquals("https://a.com/", json["params"]["urls"][0].asText())
        assertEquals("S1", json["sessionId"].asText())
    }

    @Test
    fun omitsOptionalFields() {
        val json = mapper.readTree(CdpMessages.request(1, "Browser.close"))
        assertFalse(json.has("params"))
        assertFalse(json.has("sessionId"))
    }

    @Test
    fun parsesSuccessResponse() {
        val message = CdpMessages.parse("""{"id":3,"result":{"sessionId":"abc"},"sessionId":"S"}""")
        assertIs<CdpMessage.Response>(message)
        assertEquals(3, message.id)
        assertEquals("abc", message.result!!["sessionId"].asText())
        assertEquals("S", message.sessionId)
        assertNull(message.error)
    }

    @Test
    fun parsesErrorResponse() {
        val message = CdpMessages.parse("""{"id":4,"error":{"code":-32601,"message":"yok"}}""")
        assertIs<CdpMessage.Response>(message)
        assertEquals(-32601, message.error!!.code)
        assertEquals("yok", message.error.message)
    }

    @Test
    fun parsesEvent() {
        val message = CdpMessages.parse("""{"method":"Target.targetCreated","params":{"targetInfo":{}}}""")
        assertIs<CdpMessage.Event>(message)
        assertEquals("Target.targetCreated", message.method)
        assertNull(message.sessionId)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(CdpMessages.parse("not json"))
        assertNull(CdpMessages.parse("[1,2]"))
        assertNull(CdpMessages.parse("""{"foo":1}"""))
    }

    @Test
    fun convertsCookiesToMapPreferringLongerPath() {
        val result = mapper.readTree(
            """{"cookies":[
                {"name":"cf_clearance","value":"X","path":"/"},
                {"name":"a","value":"root","path":"/"},
                {"name":"a","value":"deep","path":"/deep/path"},
                {"name":"b","value":"1","path":"/"}
            ]}""",
        )
        val map = CdpMessages.cookiesToMap(result)
        assertEquals("X", map["cf_clearance"])
        assertEquals("deep", map["a"])
        assertEquals("1", map["b"])
        assertTrue(CdpMessages.cookiesToMap(null).isEmpty())
    }

    @Test
    fun parsesActivePortFile() {
        val parsed = CdpMessages.parseActivePort("51234\n/devtools/browser/abc-123\n")
        assertEquals(51234, parsed!!.port)
        assertEquals("/devtools/browser/abc-123", parsed.browserPath)
        assertNull(CdpMessages.parseActivePort(""))
        assertNull(CdpMessages.parseActivePort("abc"))
    }

    @Test
    fun detectsChallengeTitles() {
        assertTrue(CdpMessages.isChallengeTitle("Just a moment..."))
        assertTrue(CdpMessages.isChallengeTitle("Bir dakika lütfen…"))
        assertTrue(CdpMessages.isChallengeTitle(""))
        assertTrue(CdpMessages.isChallengeTitle(null))
        assertFalse(CdpMessages.isChallengeTitle("nowSecure"))
        assertFalse(CdpMessages.isChallengeTitle("Ornek Site - Ana sayfa"))
    }

    @Test
    fun fixesHeadlessUserAgent() {
        assertEquals(
            "Mozilla/5.0 Chrome/120.0 Safari/537.36",
            CdpMessages.fixUserAgent("Mozilla/5.0 HeadlessChrome/120.0 Safari/537.36"),
        )
        assertNull(CdpMessages.fixUserAgent(null))
    }

    @Test
    fun buildsLaunchCommand() {
        val solver = BrowserChallengeSolver({ null }, { null })
        val command = solver.buildCommand(File("edge.exe"), File("profile"), "127.0.0.1:8080", "https://x.com/")
        assertTrue("--remote-debugging-port=0" in command)
        assertTrue("--proxy-server=http://127.0.0.1:8080" in command)
        assertTrue(command.none { it.contains("headless") })
        assertEquals("https://x.com/", command.last())
        val noProxy = solver.buildCommand(File("edge.exe"), File("profile"), null, "https://x.com/")
        assertTrue(noProxy.none { it.startsWith("--proxy-server") })
    }

    @Test
    fun solveReturnsNullWithoutBrowser() {
        assertNull(BrowserChallengeSolver({ null }, { null }).solve("https://example.com/"))
    }
}
