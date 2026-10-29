package seyir.core

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginCompatibilityTest {
    private val dir = Files.createTempDirectory("seyir-compat-test").toFile()

    /** Verilen Java kaynaklarını derler; [omit] içindekiler jar'a konmaz (eksik sınıf durumunu taklit eder). */
    private fun buildJar(sources: Map<String, String>, omit: Set<String> = emptySet(), manifest: String? = null): File {
        val src = File(dir, "src-${System.nanoTime()}").apply { mkdirs() }
        val out = File(dir, "out-${System.nanoTime()}").apply { mkdirs() }
        val files = sources.map { (name, code) -> File(src, "$name.java").also { it.parentFile.mkdirs(); it.writeText(code) } }
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("Test JDK'sında derleyici yok")
        val code = compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"), "-d", out.path, *files.map { it.path }.toTypedArray())
        assertEquals(0, code, "Test kaynakları derlenemedi")
        val jar = File(dir, "plugin-${System.nanoTime()}.jar")
        JarOutputStream(jar.outputStream()).use { zip ->
            out.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(out).path.replace('\\', '/')
                if (name.removeSuffix(".class") in omit) return@forEach
                zip.putNextEntry(JarEntry(name))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
            manifest?.let {
                zip.putNextEntry(JarEntry("manifest.json"))
                zip.write(it.toByteArray())
                zip.closeEntry()
            }
        }
        return jar
    }

    private val missingSource = mapOf(
        "demo/ui/Fancy" to "package demo.ui; public class Fancy {}",
        "demo/Main" to """
            package demo;
            public class Main {
                private final demo.ui.Fancy view = new demo.ui.Fancy();
                String text() { return String.valueOf(java.util.List.of(1)); }
            }
        """.trimIndent(),
    )

    @Test
    fun missingClassIsReportedByPackage() {
        val jar = buildJar(missingSource, omit = setOf("demo/ui/Fancy"))
        val report = PluginCompatibility.check(jar)
        assertFalse(report.compatible)
        assertEquals(listOf("demo.ui.Fancy"), report.missingClasses)
        assertEquals(setOf("demo.ui"), report.missingByPackage.keys)
    }

    @Test
    fun classesInsideJarAndJdkAreSatisfied() {
        val jar = buildJar(missingSource)
        val report = PluginCompatibility.check(jar)
        assertTrue(report.compatible, "Eksik: ${report.missingClasses}")
    }

    @Test
    fun runtimeClasspathClassesAreSatisfied() {
        // Seyir'in kendi sınıfı (Playable) ve Android taklidi eklentiye sınıf yolundan görünür
        val jar = buildJar(
            mapOf(
                "demo/Uses" to """
                    package demo;
                    public class Uses {
                        Object a = new seyir.core.Playable("t", "d", null, null, null);
                        Object b = new android.content.Context();
                    }
                """.trimIndent(),
            ),
        )
        val report = PluginCompatibility.check(jar)
        assertTrue(report.compatible, "Eksik: ${report.missingClasses}")
    }

    @Test
    fun resolverDecidesWhatCountsAsMissing() {
        val jar = buildJar(missingSource, omit = setOf("demo/ui/Fancy"))
        val report = PluginCompatibility.check(jar) { name -> name == "demo/ui/Fancy" || PluginCompatibility.runtimeResolver.exists(name) }
        assertTrue(report.compatible)
    }

    @Test
    fun requiresResourcesMakesPluginIncompatible() {
        val jar = buildJar(
            mapOf("demo/Plain" to "package demo; public class Plain {}"),
            manifest = """{"name":"Demo","pluginClassName":"demo.Plain","requiresResources":true}""",
        )
        val report = PluginCompatibility.check(jar)
        assertTrue(report.missingClasses.isEmpty())
        assertTrue(report.requiresResources)
        assertFalse(report.compatible)
    }

    @Test
    fun describeMissingTruncatesAfterLimit() {
        val report = CompatibilityReport(listOf("a.A", "a.B", "b.C", "b.D", "c.E"))
        assertEquals("a.A, a.B, b.C (+2)", report.describeMissing(3))
        assertEquals("a.A, a.B", CompatibilityReport(listOf("a.A", "a.B")).describeMissing(3))
    }

    @Test
    fun descriptorAndArrayTypesAreCollected() {
        val jar = buildJar(
            mapOf(
                "demo/Sig" to """
                    package demo;
                    public class Sig {
                        void call(java.util.Map<String, String> m) { Object o = new demo.gone.Thing[1]; take(null); }
                        void take(demo.gone.Other other) {}
                    }
                """.trimIndent(),
                "demo/gone/Thing" to "package demo.gone; public class Thing {}",
                "demo/gone/Other" to "package demo.gone; public class Other {}",
            ),
            omit = setOf("demo/gone/Thing", "demo/gone/Other"),
        )
        val report = PluginCompatibility.check(jar)
        assertEquals(listOf("demo.gone.Other", "demo.gone.Thing"), report.missingClasses)
    }

    /** Sabit havuzunda yalnız verilen sınıf girdileri bulunan en küçük geçerli sınıf dosyası (dex2jar artığını taklit eder). */
    private fun classWithConstants(name: String, vararg extraClassRefs: String): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(bytes)
        val refs = listOf("java/lang/Object") + extraClassRefs
        out.writeInt(0xCAFEBABE.toInt())
        out.writeShort(0)
        out.writeShort(52)
        // 1: Utf8 ad, 2: Class(1); ardından her başvuru için Utf8 + Class çifti
        out.writeShort(3 + refs.size * 2)
        out.writeByte(1); out.writeUTF(name)
        out.writeByte(7); out.writeShort(1)
        refs.forEachIndexed { i, ref ->
            out.writeByte(1); out.writeUTF(ref)
            out.writeByte(7); out.writeShort(3 + i * 2)
        }
        out.writeShort(0x21) // public super
        out.writeShort(2) // this
        out.writeShort(4) // super = java/lang/Object
        out.writeShort(0); out.writeShort(0); out.writeShort(0); out.writeShort(0)
        return bytes.toByteArray()
    }

    private fun jarOf(entries: Map<String, ByteArray>): File {
        val jar = File(dir, "synthetic-${System.nanoTime()}.jar")
        JarOutputStream(jar.outputStream()).use { zip ->
            entries.forEach { (name, data) -> zip.putNextEntry(JarEntry(name)); zip.write(data); zip.closeEntry() }
        }
        return jar
    }

    @Test
    fun dex2jarJavaUtilObjectArtifactIsIgnored() {
        val jar = jarOf(mapOf("demo/Dex.class" to classWithConstants("demo/Dex", "java/util/Object", "javax/swing/String")))
        val report = PluginCompatibility.check(jar)
        assertTrue(report.compatible, "Eksik: ${report.missingClasses}")
    }

    @Test
    fun otherMissingJdkLookalikesAreStillReported() {
        val jar = jarOf(mapOf("demo/Dex.class" to classWithConstants("demo/Dex", "java/util/Object", "java/util/Nothing", "com/example/Object")))
        val report = PluginCompatibility.check(jar)
        assertEquals(listOf("com.example.Object", "java.util.Nothing"), report.missingClasses)
    }
}
