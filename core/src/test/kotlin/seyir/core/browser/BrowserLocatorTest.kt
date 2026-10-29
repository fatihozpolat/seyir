package seyir.core.browser

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserLocatorTest {
    @Test
    fun configuredPathWinsWhenFileExists() {
        val fake = Files.createTempFile("fake-browser", ".exe").toFile().apply { deleteOnExit() }
        assertEquals(fake, BrowserLocator.find(fake.absolutePath))
        assertEquals(fake, BrowserLocator.find("  \"${fake.absolutePath}\"  "))
    }

    @Test
    fun windowsCandidatesStartWithEdge() {
        val env = mapOf(
            "ProgramFiles" to "C:\\Program Files",
            "ProgramFiles(x86)" to "C:\\Program Files (x86)",
            "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local",
        )
        val list = BrowserLocator.candidates(true, env, ";").map { it.path.replace('/', '\\') }
        assertEquals("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe", list.first())
        assertTrue(list.contains("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"))
        assertTrue(list.contains("C:\\Users\\u\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe"))
        assertTrue(list.any { it.endsWith("brave.exe") })
        assertTrue(list.any { it.contains("Chromium") })
    }

    @Test
    fun linuxCandidatesSearchPathThenFixedDirs() {
        val list = BrowserLocator.candidates(false, mapOf("PATH" to "/opt/bin:/usr/bin"), ":")
        assertEquals(File("/opt/bin", "google-chrome"), list.first())
        assertTrue(list.contains(File("/snap/bin", "chromium")))
        assertTrue(list.contains(File("/usr/bin", "brave-browser")))
        assertTrue(list.contains(File("/usr/bin", "microsoft-edge")))
        // /usr/bin PATH'te zaten var, yinelenmemeli
        assertEquals(1, list.count { it == File("/usr/bin", "chromium") })
    }
}
