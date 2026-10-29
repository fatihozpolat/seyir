package seyir.app

import seyir.app.data.JsonStore
import seyir.app.i18n.TurkishStrings
import seyir.core.FailureKind
import seyir.app.i18n.EnglishStrings
import seyir.core.ProbeFailure
import seyir.core.ProbeResult
import seyir.core.ProbeStatus
import seyir.core.ProbeStepKind
import seyir.core.StepResult
import seyir.core.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProviderHealthTest {
    private fun h(ok: Boolean, source: HealthSource?, message: String? = null) = ProviderHealth(ok, message, 1L, source)

    @Test
    fun `probe result always wins`() {
        val red = h(false, HealthSource.SEARCH, "x")
        val report = h(true, HealthSource.PROBE)
        assertSame(report, HealthPolicy.resolve(red, report))
        val green = h(true, HealthSource.PROBE)
        val redProbe = h(false, HealthSource.PROBE, "y")
        assertSame(redProbe, HealthPolicy.resolve(green, redProbe))
    }

    @Test
    fun `successful search does not clear a probe red`() {
        val probeRed = h(false, HealthSource.PROBE, "Arama: boş")
        assertSame(probeRed, HealthPolicy.resolve(probeRed, h(true, HealthSource.SEARCH)))
        assertSame(probeRed, HealthPolicy.resolve(probeRed, h(true, HealthSource.HOME)))
    }

    @Test
    fun `search error does not overwrite probe red but downgrades green`() {
        val probeRed = h(false, HealthSource.PROBE, "Arama: boş")
        assertSame(probeRed, HealthPolicy.resolve(probeRed, h(false, HealthSource.SEARCH, "z")))
        val green = h(true, HealthSource.PROBE)
        val error = h(false, HealthSource.HOME, "z")
        assertSame(error, HealthPolicy.resolve(green, error))
    }

    @Test
    fun `a source clears only its own error`() {
        val searchRed = h(false, HealthSource.SEARCH, "e")
        val homeOk = h(true, HealthSource.HOME)
        assertSame(searchRed, HealthPolicy.resolve(searchRed, homeOk))
        val searchOk = h(true, HealthSource.SEARCH)
        assertSame(searchOk, HealthPolicy.resolve(searchRed, searchOk))
    }

    @Test
    fun `no record is created green by search`() {
        val ok = h(true, HealthSource.SEARCH)
        assertSame(ok, HealthPolicy.resolve(null, ok))
    }

    @Test
    fun `legacy red without source is protected from search and home`() {
        val legacy = h(false, null, "eski")
        assertSame(legacy, HealthPolicy.resolve(legacy, h(true, HealthSource.SEARCH)))
        assertSame(legacy, HealthPolicy.resolve(legacy, h(false, HealthSource.HOME, "yeni")))
    }

    @Test
    fun `old health json without source still loads`() {
        val json = """{"Kanal":{"ok":false,"message":"boş","at":5}}"""
        val map = JsonStore.mapper.readValue(json, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, ProviderHealth>>() {})
        assertEquals(false, map.getValue("Kanal").ok)
        assertNull(map.getValue("Kanal").source)
        val roundTrip = JsonStore.mapper.writeValueAsString(h(false, HealthSource.HOME, "m"))
        assertTrue("HOME" in roundTrip)
    }

    @Test
    fun `probe error details reuse the known message mapping`() {
        val t = TurkishStrings
        assertEquals(t.errorForbidden, probeFailureText(t, ProbeFailure(FailureKind.ERROR, "HTTP 403 Forbidden")))
        assertEquals(t.errorSsl, probeFailureText(t, ProbeFailure(FailureKind.NETWORK, "PKIX path building failed")))
        assertEquals(t.probeFailNetwork, probeFailureText(t, ProbeFailure(FailureKind.NETWORK, "Unable to resolve host \"x\"")))
        assertEquals(t.probeFailError("boom"), probeFailureText(t, ProbeFailure(FailureKind.ERROR, "boom")))
    }

    private fun challengeResult(): ProbeResult {
        val blocked = StepResult(ProbeStepKind.SEARCH, StepStatus.FAILED, ProbeFailure(FailureKind.CLOUDFLARE, "site.example"))
        val skipped = listOf(ProbeStepKind.LOAD, ProbeStepKind.LINKS, ProbeStepKind.LINK_CHECK)
            .map { StepResult(it, StepStatus.SKIPPED, ProbeFailure(FailureKind.PREVIOUS_FAILED)) }
        return ProbeResult("Kanal", listOf(blocked) + skipped)
    }

    @Test
    fun `cloudflare challenge has its own text and label and is not red`() {
        val result = challengeResult()
        for (t in listOf(TurkishStrings, EnglishStrings)) {
            assertEquals(t.probeFailCloudflare, probeFailureText(t, ProbeFailure(FailureKind.CLOUDFLARE, "site.example")))
            assertEquals(t.probeStatusChallenge, probeResultLabel(t, result))
        }
        assertTrue("Cloudflare" in TurkishStrings.probeFailCloudflare && "Cloudflare" in EnglishStrings.probeFailCloudflare)
        // Rozet rengi sarı; sağlık kaydı için kırmızı değil
        assertEquals(ProbeStatus.YELLOW, result.status)
        // Sıradan sarı sonuç eski etiketini korur
        val partial = ProbeResult("Kanal", listOf(StepResult(ProbeStepKind.SEARCH, StepStatus.OK), StepResult(ProbeStepKind.LOAD, StepStatus.OK)))
        assertEquals(TurkishStrings.probeStatusPartial, probeResultLabel(TurkishStrings, partial))
    }

    @Test
    fun `cloudflare probe clears an earlier red record`() {
        // Sarı sonuç sağlık kaydına "çalışıyor" (ok) yazılır; önceki kırmızı Sına kaydını ezer
        val previousRed = h(false, HealthSource.PROBE, "Arama: Sonuç boş döndü")
        val report = h(challengeResult().status != ProbeStatus.RED, HealthSource.PROBE)
        assertSame(report, HealthPolicy.resolve(previousRed, report))
        assertTrue(report.ok)
    }
}
