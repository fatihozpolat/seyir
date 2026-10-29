package seyir.core.browser

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import seyir.core.SeyirLog
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gerçek tarayıcıyla duman testi. Pencere açar ve ağa çıkar, bu yüzden varsayılan olarak kapalıdır:
 *
 *     SEYIR_MANUAL_BROWSER=1 SEYIR_DATA=<geçici klasör> ./gradlew :core:test --tests '*BrowserChallengeSolverManualTest*' -i
 *
 * `SEYIR_MANUAL_URL` ile hedef sayfa değiştirilebilir (varsayılan https://nowsecure.nl).
 */
@Tag("manual")
@EnabledIfEnvironmentVariable(named = "SEYIR_MANUAL_BROWSER", matches = ".+")
class BrowserChallengeSolverManualTest {
    @Test
    fun solvesRealCloudflarePage() {
        SeyirLog.minLevel = 3
        val url = System.getenv("SEYIR_MANUAL_URL") ?: "https://nowsecure.nl"
        val browser = BrowserLocator.find(System.getenv("SEYIR_BROWSER"))
        assertNotNull(browser, "Tarayıcı bulunamadı")
        println("MANUAL: tarayıcı = $browser")

        val solver = BrowserChallengeSolver({ browser }, { null }, timeoutMs = 90_000)
        val started = System.currentTimeMillis()
        val result = solver.solve(url)
        val seconds = (System.currentTimeMillis() - started) / 1000.0

        println("MANUAL: süre = %.1f sn".format(seconds))
        println("MANUAL: sonuç = ${if (result == null) "NULL" else "çerezler=${result.cookies.keys} UA=${result.userAgent}"}")
        println("MANUAL: cf_clearance geldi mi = ${result?.cookies?.containsKey("cf_clearance")}")

        assertNotNull(result, "Sınama çözülemedi")
        assertTrue(result.cookies.containsKey("cf_clearance"))
        assertNotNull(result.userAgent)
    }
}
