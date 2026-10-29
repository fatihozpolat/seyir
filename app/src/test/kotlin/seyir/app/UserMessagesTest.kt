package seyir.app

import seyir.app.i18n.EnglishStrings
import seyir.app.i18n.TurkishStrings
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserMessagesTest {
    private val t = TurkishStrings
    private val en = EnglishStrings

    @Test
    fun `timeout types and messages map to timeout`() {
        assertEquals(t.errorTimeout, userMessage(SocketTimeoutException("Read timed out"), t))
        assertEquals(en.errorTimeout, userMessage(IOException("timeout"), en))
    }

    @Test
    fun `unknown host maps to no network`() {
        assertEquals(t.errorNoNetwork, userMessage(UnknownHostException("example.invalid"), t))
        assertEquals(t.errorNoNetwork, userMessage(IOException("Unable to resolve host \"x\""), t))
    }

    @Test
    fun `ssl failures map to ssl message`() {
        assertEquals(t.errorSsl, userMessage(SSLHandshakeException("PKIX path building failed"), t))
    }

    @Test
    fun `http status codes are read from the message`() {
        assertEquals(t.errorForbidden, userMessage(IllegalStateException("HTTP 403"), t))
        assertEquals(t.errorNotFound, userMessage(IllegalStateException("HTTP error 404 Not Found"), t))
        assertEquals(t.errorServer(502), userMessage(IllegalStateException("HTTP 502"), t))
        assertEquals(en.errorServer(503), userMessage(RuntimeException("status code: 503"), en))
        assertEquals(t.errorHttp(429), userMessage(IllegalStateException("HTTP 429"), t))
    }

    @Test
    fun `cloudflare challenge wins over a 403`() {
        assertEquals(t.errorChallenge, userMessage(IllegalStateException("HTTP 403 Just a moment... cloudflare"), t))
    }

    @Test
    fun `cancellation maps to cancelled`() {
        assertEquals(t.errorCancelled, userMessage(CancellationException("x"), t))
        assertEquals(t.errorCancelled, userMessage(IOException("Canceled"), t))
    }

    @Test
    fun `wrapped causes are inspected`() {
        val wrapped = RuntimeException("loadLinks failed", SocketTimeoutException("timeout"))
        assertEquals(t.errorTimeout, userMessage(wrapped, t))
    }

    @Test
    fun `unknown errors keep a brief original message`() {
        val long = "x".repeat(500)
        val message = userMessage(IllegalArgumentException("bad thing\nsecond line $long"), t)
        assertTrue(message.startsWith(t.errorGeneric("bad thing")), message)
        assertEquals(t.errorGeneric("IllegalStateException"), userMessage(IllegalStateException(), t))
        assertTrue(userMessage(IllegalStateException(long), t).length < 220)
    }

    @Test
    fun `plain text overload classifies player output`() {
        assertEquals(t.errorForbidden, userMessage("HTTP error 403 Forbidden", t))
        assertEquals(en.errorGeneric("?"), userMessage(null, en))
    }
}
