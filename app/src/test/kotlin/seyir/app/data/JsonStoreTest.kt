package seyir.app.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("jsonstore").toFile().apply { deleteOnExit() }

    private fun entry(n: Int, updatedAt: Long = n.toLong()) =
        WatchEntry("P", "https://x/$n", "Dizi", null, "Bölüm $n", "data$n", updatedAt = updatedAt)

    @Test
    fun corruptFileIsBackedUpBeforeFallingBackToDefault() {
        val file = File(tempDir(), "library.json").apply { writeText("{ bozuk json") }
        val value = JsonStore.read(file) { LibraryFile() }
        assertEquals(LibraryFile(), value)
        assertTrue(!file.exists(), "bozuk dosya kenara alınmalı")
        val backups = file.parentFile.listFiles { f -> f.name.startsWith("library.json.corrupt-") }!!
        assertEquals(1, backups.size)
        assertEquals("{ bozuk json", backups[0].readText())

        // Sonraki yazım yedeği ezmemeli
        JsonStore.write(file, LibraryFile(history = listOf(entry(1))))
        assertEquals("{ bozuk json", backups[0].readText())
    }

    @Test
    fun missingFileGivesDefaultWithoutBackup() {
        val dir = tempDir()
        assertEquals(LibraryFile(), JsonStore.read(File(dir, "yok.json")) { LibraryFile() })
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun writeReplacesExistingFileAndLeavesNoTempFiles() {
        val file = File(tempDir(), "settings.json")
        JsonStore.write(file, AppSettings(language = "tr"))
        JsonStore.write(file, AppSettings(language = "en"))
        assertEquals("en", JsonStore.read(file) { AppSettings() }.language)
        assertEquals(listOf("settings.json"), file.parentFile.list()!!.toList())
    }

    @Test
    fun concurrentWritesLeaveAValidFile() {
        val file = File(tempDir(), "health.json")
        val pool = Executors.newFixedThreadPool(8)
        repeat(100) { n -> pool.execute { JsonStore.write(file, mapOf("n" to n, "pad" to "x".repeat(20_000))) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val read = JsonStore.read<Map<String, Any>>(file) { emptyMap() }
        assertTrue("n" in read)
        assertEquals(listOf("health.json"), file.parentFile.list()!!.toList())
    }

    @Test
    fun debouncedWriterCoalescesBurstsIntoOneWrite() {
        val file = File(tempDir(), "burst.json")
        var counter = 0
        val writer = DebouncedWriter(file, delayMs = 100) { counter++; mapOf("v" to counter) }
        repeat(50) { writer.schedule() }
        Thread.sleep(600)
        assertEquals(1, counter, "50 değişiklik tek yazıma inmeli")
        assertTrue(file.exists())
    }

    @Test
    fun flushWritesPendingChangesImmediately() {
        val file = File(tempDir(), "flush.json")
        val writer = DebouncedWriter(file, delayMs = 60_000) { mapOf("v" to 1) }
        writer.schedule()
        assertTrue(!file.exists())
        JsonStore.flushAll()
        assertTrue(file.exists())
    }

    @Test
    fun libraryHistoryIsCappedAndNewestSurvive() {
        val file = File(tempDir(), "library.json")
        val store = LibraryStore(file)
        repeat(LibraryStore.MAX_HISTORY + 20) { store.record(entry(it)) }
        assertEquals(LibraryStore.MAX_HISTORY, store.history.size)
        // En yeni kayıt başta, en eski 20 kayıt atılmış
        assertEquals("data${LibraryStore.MAX_HISTORY + 19}", store.history.first().itemData)
        assertTrue(store.entry("P", "data0") == null)
        assertTrue(store.entry("P", "data20") != null)

        store.flush()
        assertEquals(LibraryStore.MAX_HISTORY, LibraryStore(file).history.size)
    }

    @Test
    fun libraryViewIsCachedUntilChanged() {
        val store = LibraryStore(File(tempDir(), "library.json"))
        store.record(entry(1))
        val first = store.history
        assertTrue(first === store.history, "değişmediyse aynı sıralı liste dönmeli")
        store.record(entry(2))
        assertEquals(2, store.history.size)
    }

    @Test
    fun corruptLibraryIsKeptNextToFreshOne() {
        val file = File(tempDir(), "library.json").apply { writeText("[[[") }
        val store = LibraryStore(file)
        store.record(entry(1))
        store.flush()
        assertEquals(1, LibraryStore(file).history.size)
        assertEquals(1, file.parentFile.listFiles { f -> ".corrupt-" in f.name }!!.size)
    }
}
