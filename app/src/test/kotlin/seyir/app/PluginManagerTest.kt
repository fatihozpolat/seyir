package seyir.app

import kotlinx.coroutines.runBlocking
import seyir.core.PluginLoader
import seyir.app.testplugin.SamplePlugin
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginManagerTest {
    private val root: File = Files.createTempDirectory("seyir-manager-test").toFile()
    private val userDir = File(root, "plugins").apply { mkdirs() }
    private val shadowDir = File(root, "shadow")
    private val sources = PluginSources(File(root, "sources.json"))
    private val manager = PluginManager(
        PluginLoader(), sources, { userDir }, shadowDir, retireDelayMs = 0, offers = PluginOffers(File(root, "offers.json")),
    )

    @AfterTest
    fun cleanup() {
        manager.plugins.forEach { runCatching { manager.uninstall(it) } }
        root.deleteRecursively()
    }

    // ---- saf işlevler ----

    @Test
    fun `repositoryUrls github adresini ham adreslere cevirir`() {
        val raw = "https://raw.githubusercontent.com/sahip/depo"
        val expected = listOf("$raw/builds/repo.json", "$raw/master/repo.json", "$raw/main/repo.json", "$raw/builds/plugins.json")
        assertEquals(expected, PluginManager.repositoryUrls("https://github.com/sahip/depo"))
        assertEquals(expected, PluginManager.repositoryUrls("  https://github.com/sahip/depo/  "))
        assertEquals(expected, PluginManager.repositoryUrls("https://www.github.com/sahip/depo.git"))
        assertEquals(expected, PluginManager.repositoryUrls("https://github.com/sahip/depo/tree/main/alt"))
    }

    @Test
    fun `repositoryUrls http github adresini https ham adrese yukseltir`() {
        assertTrue(PluginManager.repositoryUrls("http://github.com/sahip/depo").all { it.startsWith("https://raw.githubusercontent.com/") })
    }

    @Test
    fun `repositoryUrls ote adresleri oldugu gibi birakir`() {
        assertEquals(listOf("https://example.com/plugins.json"), PluginManager.repositoryUrls("https://example.com/plugins.json/"))
        assertEquals(listOf("C:\\depo\\plugins.json"), PluginManager.repositoryUrls("C:\\depo\\plugins.json"))
    }

    @Test
    fun `checksumMatches ozet yoksa gecer varsa karsilastirir`() {
        val digest = "a".repeat(64)
        assertTrue(PluginManager.checksumMatches(null, digest))
        assertTrue(PluginManager.checksumMatches(digest, digest))
        assertTrue(PluginManager.checksumMatches(digest.uppercase(), digest))
        assertFalse(PluginManager.checksumMatches("b".repeat(64), digest))
        // Geçerli bir sha256 olmayan değer, özet vermemek sayılmaz
        assertFalse(PluginManager.checksumMatches("kisa", digest))
        assertFalse(PluginManager.checksumMatches("", digest))
    }

    @Test
    fun `erisim anahtari yalniz https github adreslerine eklenir`() {
        val ok = PluginManager.authHeadersFor("https://raw.githubusercontent.com/a/b/builds/repo.json", " gizli ")
        assertEquals(mapOf("Authorization" to "token gizli"), ok)
        assertTrue(PluginManager.authHeadersFor("https://example.com/repo.json", "gizli").isEmpty())
        assertTrue(PluginManager.authHeadersFor("https://github.com.example.com/x", "gizli").isEmpty())
        assertTrue(PluginManager.authHeadersFor("http://github.com/x", "gizli").isEmpty())
        assertTrue(PluginManager.authHeadersFor("https://github.com/x", null).isEmpty())
        assertTrue(PluginManager.authHeadersFor("https://github.com/x", "  ").isEmpty())
    }

    // ---- kurulum ----

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Test eklentisinin sınıfını ve manifestini içeren bir jar yazar. */
    private fun pluginJar(name: String, version: Int, withClass: Boolean = true): File {
        val file = File(root, "$name-$version-${System.nanoTime()}.jar")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write("""{"name":"$name","version":$version,"pluginClassName":"${SamplePlugin::class.java.name}"}""".toByteArray())
            out.closeEntry()
            if (withClass) {
                val path = SamplePlugin::class.java.name.replace('.', '/') + ".class"
                out.putNextEntry(ZipEntry(path))
                out.write(SamplePlugin::class.java.classLoader.getResourceAsStream(path)!!.readBytes())
                out.closeEntry()
            }
        }
        return file
    }

    private fun entry(jar: File, name: String, version: Int, sha: String? = sha256(jar)) =
        RepositoryPlugin(url = jar.path, internalName = name, version = version, sha256 = sha)

    @Test
    fun `sha256 uyusmazsa kurulum reddedilir`() {
        val jar = pluginJar("Demo", 1)
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { manager.install(entry(jar, "Demo", 1, sha = "0".repeat(64)), "https://example.com/plugins.json") }
        }
        assertTrue(error.message!!.contains("sha256"))
        assertTrue(manager.plugins.isEmpty())
        assertTrue(userDir.listFiles().isNullOrEmpty())
        assertNull(sources.get("Demo"))
    }

    @Test
    fun `sha256 yoksa ve varsa dogruysa kurulur ve kaynak kaydedilir`() {
        val repo = "https://example.com/plugins.json"
        runBlocking { manager.install(entry(pluginJar("Demo", 1), "Demo", 1, sha = null), repo) }
        assertEquals(1, manager.installedVersion("Demo"))
        runBlocking { manager.install(entry(pluginJar("Other", 1), "Other", 1), repo) }
        assertEquals(1, manager.installedVersion("Other"))
        assertEquals(repo, sources.get("Demo"))
    }

    @Test
    fun `gecersiz jar kurulmaz ve eski surum yerinde kalir`() {
        runBlocking { manager.install(entry(pluginJar("Demo", 1), "Demo", 1)) }
        val installedBytes = File(userDir, "Demo.jar").readBytes()
        val broken = pluginJar("Demo", 2, withClass = false)
        assertFailsWith<IllegalArgumentException> { runBlocking { manager.install(entry(broken, "Demo", 2)) } }
        assertEquals(1, manager.installedVersion("Demo"))
        assertTrue(installedBytes.contentEquals(File(userDir, "Demo.jar").readBytes()))
    }

    @Test
    fun `yeni surum eskinin yerine gecer ve kopya jar birikmez`() {
        runBlocking { manager.install(entry(pluginJar("Demo", 1), "Demo", 1)) }
        runBlocking { manager.install(entry(pluginJar("Demo", 2), "Demo", 2)) }
        assertEquals(1, manager.plugins.size)
        assertEquals(2, manager.installedVersion("Demo"))
        assertEquals(1, shadowDir.listFiles().orEmpty().size)
        manager.uninstall(manager.plugins.single())
        assertTrue(manager.plugins.isEmpty())
        assertEquals(0, shadowDir.listFiles().orEmpty().size)
        assertNull(sources.get("Demo"))
    }

    @Test
    fun `toplu kurulum basarisizlari nedeniyle bildirir`() {
        val good = entry(pluginJar("Good", 1), "Good", 1)
        val bad = entry(pluginJar("Bad", 1), "Bad", 1, sha = "1".repeat(64))
        val report = runBlocking { manager.installSelected(listOf(good, bad), "https://example.com/plugins.json") }
        assertEquals(listOf("Good"), report.installed)
        assertTrue(report.failures.getValue("Bad").contains("sha256"))
        assertEquals(1, manager.plugins.size)
    }

    // ---- kaynak sabitleme ----

    private fun writeRepo(dirName: String, name: String, version: Int, jar: File): String {
        val dir = File(root, dirName).apply { mkdirs() }
        jar.copyTo(File(dir, "$name.jar"), overwrite = true)
        File(dir, "plugins.json").writeText(
            """[{"url":"$name.jar","internalName":"$name","version":$version,"sha256":"${sha256(jar)}"}]"""
        )
        return File(dir, "plugins.json").path
    }

    @Test
    fun `guncelleme yalniz eklentinin kendi deposundan yapilir`() {
        val repoA = writeRepo("a", "Demo", 1, pluginJar("Demo", 1))
        val repoB = writeRepo("b", "Demo", 5, pluginJar("Demo", 5))
        runBlocking { manager.installAll(manager.fetchRepository(repoA)) }
        assertEquals(repoA, sources.get("Demo"))
        // B daha yeni sürümü listeler ama eklentinin kaynağı A; güncelleme olmaz
        assertEquals(emptyList(), runBlocking { manager.updateFrom(listOf(repoA, repoB)) })
        assertEquals(1, manager.installedVersion("Demo"))
        // A yeni sürüm yayımlayınca güncellenir
        writeRepo("a", "Demo", 2, pluginJar("Demo", 2))
        assertEquals(listOf("Demo"), runBlocking { manager.updateFrom(listOf(repoB, repoA)) })
        assertEquals(2, manager.installedVersion("Demo"))
    }

    @Test
    fun `kaynak deposundan cikarilan eklenti isaretlenir`() {
        val repoA = writeRepo("a", "Demo", 1, pluginJar("Demo", 1))
        val repoB = writeRepo("b", "Other", 1, pluginJar("Other", 1))
        runBlocking { manager.installAll(manager.fetchRepository(repoA)) }
        manager.installFile(pluginJar("Local", 1))
        assertEquals(emptySet(), runBlocking { manager.delistedFrom(manager.fetchRepositories(listOf(repoA))) })
        // A artık Demo'yu listelemiyor; dosyadan kurulan ve kaynağı okunmayan depolar sayılmaz
        File(root, "a/plugins.json").writeText("[]")
        assertEquals(setOf("Demo"), runBlocking { manager.delistedFrom(manager.fetchRepositories(listOf(repoA, repoB))) })
        assertEquals(emptySet(), runBlocking { manager.delistedFrom(manager.fetchRepositories(listOf(repoB))) })
    }

    @Test
    fun `dosyadan kurulan eklenti kendiliginden guncellenmez`() {
        manager.installFile(pluginJar("Demo", 1))
        val repo = writeRepo("a", "Demo", 9, pluginJar("Demo", 9))
        assertEquals(PluginSources.LOCAL, sources.get("Demo"))
        assertEquals(emptyList(), runBlocking { manager.updateFrom(listOf(repo)) })
        assertEquals(1, manager.installedVersion("Demo"))
    }

    @Test
    fun `kaynagi bilinmeyen eklenti tek depo listeliyorsa o depoya baglanir`() {
        manager.installFile(pluginJar("Demo", 1))
        sources.remove("Demo")
        val repoA = writeRepo("a", "Demo", 3, pluginJar("Demo", 3))
        assertEquals(listOf("Demo"), runBlocking { manager.updateFrom(listOf(repoA)) })
        assertEquals(repoA, sources.get("Demo"))
    }

    @Test
    fun `kaynagi bilinmeyen eklenti birden fazla depoda varsa guncellenmez`() {
        manager.installFile(pluginJar("Demo", 1))
        sources.remove("Demo")
        val repoA = writeRepo("a", "Demo", 3, pluginJar("Demo", 3))
        val repoB = writeRepo("b", "Demo", 4, pluginJar("Demo", 4))
        assertEquals(emptyList(), runBlocking { manager.updateFrom(listOf(repoA, repoB)) })
        assertEquals(1, manager.installedVersion("Demo"))
    }

    @Test
    fun `guvensiz depo adresi okunmaz`() {
        assertFailsWith<InsecureUrlException> { runBlocking { manager.fetchRepository("http://example.com/plugins.json") } }
    }
}
