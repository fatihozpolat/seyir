package seyir.core

import com.lagradost.cloudstream3.plugins.BasePlugin
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginLoaderTest {
    private val dir: File = Files.createTempDirectory("seyir-loader-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** İçine verilen girdileri koyan küçük bir zip yazar. */
    private fun zip(name: String, entries: Map<String, String>): File =
        File(dir, name).also { file ->
            ZipOutputStream(file.outputStream()).use { out ->
                entries.forEach { (path, text) ->
                    out.putNextEntry(ZipEntry(path))
                    out.write(text.toByteArray())
                    out.closeEntry()
                }
            }
        }

    private fun manifestJson(name: String, version: Int, className: String? = "demo.Plugin") =
        """{"name":"$name","version":$version${className?.let { ""","pluginClassName":"$it"""" } ?: ""}}"""

    private fun manifest(name: String, version: Int?) = BasePlugin.Manifest().also {
        it.name = name
        it.version = version
    }

    @Test
    fun `validate gecerli eklentiyi kabul eder`() {
        val jar = zip("ok.jar", mapOf("manifest.json" to manifestJson("Ok", 3), "demo/Plugin.class" to "x"))
        assertEquals("Ok", PluginLoader().validate(jar).name)
    }

    @Test
    fun `validate zip olmayan dosyayi reddeder`() {
        val bad = File(dir, "bad.jar").apply { writeText("zip degil") }
        assertFailsWith<Exception> { PluginLoader().validate(bad) }
    }

    @Test
    fun `validate manifestsiz jari reddeder`() {
        val jar = zip("nomanifest.jar", mapOf("demo/Plugin.class" to "x"))
        assertFailsWith<IllegalStateException> { PluginLoader().validate(jar) }
    }

    @Test
    fun `validate giris sinifi eksik jari reddeder`() {
        val jar = zip("noclass.jar", mapOf("manifest.json" to manifestJson("NoClass", 1)))
        assertFailsWith<IllegalStateException> { PluginLoader().validate(jar) }
    }

    @Test
    fun `validate pluginClassName olmayan manifesti reddeder`() {
        val jar = zip("nocn.jar", mapOf("manifest.json" to manifestJson("NoCn", 1, className = null)))
        assertFailsWith<IllegalStateException> { PluginLoader().validate(jar) }
    }

    @Test
    fun `selectLatest ayni adli eklentiden yuksek surumu birakir`() {
        val a1 = PluginCandidate(File("A-1.jar"), manifest("A", 1))
        val a2 = PluginCandidate(File("A-2.jar"), manifest("A", 2))
        val b = PluginCandidate(File("B.jar"), manifest("B", null))
        val result = PluginLoader.selectLatest(listOf(a1, b, a2))
        assertEquals(listOf(a2, b), result)
    }

    @Test
    fun `selectLatest surum esitse ilk gelen kalir`() {
        val first = PluginCandidate(File("A.jar"), manifest("A", 4))
        val second = PluginCandidate(File("A-kopya.jar"), manifest("A", 4))
        assertEquals(listOf(first), PluginLoader.selectLatest(listOf(first, second)))
    }

    @Test
    fun `discover bozuk jarlari atlar ve yalniz jar uzantisina bakar`() {
        zip("a1.jar", mapOf("manifest.json" to manifestJson("A", 1)))
        zip("a2.jar", mapOf("manifest.json" to manifestJson("A", 2)))
        File(dir, "broken.jar").writeText("bozuk")
        zip("old.plugin", mapOf("manifest.json" to manifestJson("Old", 1)))
        val found = PluginLoader().discover(dir)
        assertEquals(listOf("a2.jar"), found.map { it.file.name })
        assertTrue(found.single().manifest.version == 2)
    }
}
