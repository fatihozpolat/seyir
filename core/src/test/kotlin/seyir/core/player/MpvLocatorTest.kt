package seyir.core.player

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvLocatorTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val exeName = if (windows) "mpv.exe" else "mpv"

    private fun tempDir(): File = Files.createTempDirectory("seyir-mpv-test").toFile().apply { deleteOnExit() }

    private fun fakeExe(dir: File): File {
        dir.mkdirs()
        return File(dir, exeName).apply {
            writeText("x")
            setExecutable(true)
            deleteOnExit()
        }
    }

    /** Hiçbir gerçek konuma bakmayan, yalnızca verilen girdilerle çalışan arama. */
    private fun find(
        configured: String? = null,
        bundled: String? = null,
        data: File = tempDir(),
        path: String? = null,
        flatpak: Boolean = false,
    ) = MpvLocator.find(
        configured = configured,
        bundledResourcesDir = bundled,
        dataDir = { data },
        pathEnv = path,
        homeDir = tempDir().absolutePath,
        windows = windows,
        flatpakInstalled = { flatpak },
    )

    @Test
    fun `configured file wins over everything`() {
        val configured = fakeExe(tempDir())
        val bundledRoot = tempDir().also { fakeExe(File(it, "mpv")) }
        val command = find(configured = configured.absolutePath, bundled = bundledRoot.absolutePath)
        assertEquals(listOf(configured.absolutePath), command?.argv)
    }

    @Test
    fun `configured directory is searched for the executable`() {
        val dir = tempDir()
        val exe = fakeExe(dir)
        assertEquals(listOf(exe.absolutePath), find(configured = dir.absolutePath)?.argv)
    }

    @Test
    fun `invalid configured path falls through to bundled copy`() {
        val bundledRoot = tempDir()
        val exe = fakeExe(File(bundledRoot, "mpv"))
        val command = find(configured = File(tempDir(), "yok").absolutePath, bundled = bundledRoot.absolutePath)
        assertEquals(listOf(exe.absolutePath), command?.argv)
    }

    @Test
    fun `bundled copy wins over data directory and PATH`() {
        val bundledRoot = tempDir()
        val bundled = fakeExe(File(bundledRoot, "mpv"))
        val data = tempDir().also { fakeExe(File(it, "mpv")) }
        val pathDir = tempDir().also { fakeExe(it) }
        val command = find(bundled = bundledRoot.absolutePath, data = data, path = pathDir.absolutePath)
        assertEquals(listOf(bundled.absolutePath), command?.argv)
    }

    @Test
    fun `data directory wins over PATH`() {
        val data = tempDir()
        val exe = fakeExe(File(data, "mpv"))
        val pathDir = tempDir().also { fakeExe(it) }
        assertEquals(listOf(exe.absolutePath), find(data = data, path = pathDir.absolutePath)?.argv)
    }

    @Test
    fun `PATH is searched in order`() {
        val first = tempDir()
        val second = tempDir()
        val exe = fakeExe(second)
        val path = listOf(first, second).joinToString(File.pathSeparator) { it.absolutePath }
        assertEquals(listOf(exe.absolutePath), find(path = path)?.argv)
    }

    @Test
    fun `bare command name in configured setting is resolved through PATH`() {
        val dir = tempDir()
        val exe = fakeExe(dir)
        assertEquals(listOf(exe.absolutePath), find(configured = "mpv", path = dir.absolutePath)?.argv)
    }

    @Test
    fun `command requires a non empty argv`() {
        val result = runCatching { MpvCommand(emptyList()) }
        assertEquals(true, result.isFailure)
    }
}
