package seyir.app

import seyir.app.data.SettingsStore
import seyir.app.data.TokenCipher
import seyir.app.data.WindowsDpapi
import seyir.core.AppDirs
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettingsStoreTest {
    private val dir: File = Files.createTempDirectory("seyir-settings-test").toFile()
    private val file = File(dir, "settings.json")

    /** Gerçek DPAPI yerine: değeri tersine çeviren sahte şifreleyici. */
    private object FakeCipher : TokenCipher {
        override fun protect(plain: String) = "fake:" + plain.reversed()
        override fun unprotect(stored: String) = stored.removePrefix("fake:").reversed().takeIf { stored.startsWith("fake:") }
    }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `anahtar diske sifreli yazilir bellekte duz kalir`() {
        val store = SettingsStore(file, FakeCipher)
        store.update { it.copy(githubToken = "ghp_gizli") }
        val text = file.readText()
        assertFalse(text.contains("ghp_gizli"))
        assertTrue(text.contains("fake:"))
        assertEquals("ghp_gizli", store.current.githubToken)
        assertEquals("", store.current.githubTokenProtected)
        // Yeniden açılışta aynı değer okunur
        assertEquals("ghp_gizli", SettingsStore(file, FakeCipher).current.githubToken)
    }

    @Test
    fun `eski duz metin anahtar acilista sifrelenir`() {
        file.writeText("""{"githubToken":"ghp_eski","language":"tr"}""")
        val store = SettingsStore(file, FakeCipher)
        assertEquals("ghp_eski", store.current.githubToken)
        assertEquals("tr", store.current.language)
        assertFalse(file.readText().contains("ghp_eski"))
        assertEquals("ghp_eski", SettingsStore(file, FakeCipher).current.githubToken)
    }

    @Test
    fun `sifreleyici yoksa anahtar duz metin kalir`() {
        val store = SettingsStore(file, cipher = null)
        store.update { it.copy(githubToken = "ghp_duz") }
        assertTrue(file.readText().contains("ghp_duz"))
        assertEquals("ghp_duz", SettingsStore(file, cipher = null).current.githubToken)
    }

    @Test
    fun `cozulemeyen anahtar blogu ayar kaydedilince korunur`() {
        file.writeText("""{"githubTokenProtected":"bozuk-blok","language":"tr"}""")
        val store = SettingsStore(file, FakeCipher)
        assertEquals("", store.current.githubToken)
        store.update { it.copy(language = "en") }
        assertTrue(file.readText().contains("bozuk-blok"))
        // Yeniden açılışta da korunur; kullanıcı yeni anahtar girince blok onunla değişir
        SettingsStore(file, FakeCipher).update { it.copy(proxy = "http://127.0.0.1:1") }
        assertTrue(file.readText().contains("bozuk-blok"))
        store.update { it.copy(githubToken = "ghp_yeni") }
        assertFalse(file.readText().contains("bozuk-blok"))
        assertEquals("ghp_yeni", SettingsStore(file, FakeCipher).current.githubToken)
    }

    @Test
    fun `bos anahtar sifrelenmez`() {
        SettingsStore(file, FakeCipher).update { it.copy(language = "en") }
        assertFalse(file.readText().contains("fake:"))
    }

    @Test
    fun `linuxta ayar dosyasi yalniz sahibine acik yazilir`() {
        if (AppDirs.isWindows) return
        SettingsStore(file, cipher = null).update { it.copy(githubToken = "ghp_duz") }
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), Files.getPosixFilePermissions(file.toPath()).map { it.name }.toSet())
    }

    @Test
    fun `dpapi gidis donus yapar`() {
        if (!AppDirs.isWindows) return
        val protected = WindowsDpapi.protect("ghp_dpapi_deneme")!!
        assertNotEquals("ghp_dpapi_deneme", protected)
        assertFalse(protected.contains("ghp_dpapi_deneme"))
        assertEquals("ghp_dpapi_deneme", WindowsDpapi.unprotect(protected))
        assertEquals(null, WindowsDpapi.unprotect("dpapi:bozuk"))
    }
}
