package seyir.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtomicFileTest {
    private fun tempDir(): File = Files.createTempDirectory("atomic").toFile().apply { deleteOnExit() }

    @Test
    fun replacesExistingTarget() {
        val target = File(tempDir(), "data.json").apply { writeText("eski") }
        AtomicFile.replace(target) { it.writeText("yeni") }
        assertEquals("yeni", target.readText())
        assertEquals(listOf("data.json"), target.parentFile.list()!!.toList())
    }

    @Test
    fun failedWriteKeepsTargetAndCleansUp() {
        val target = File(tempDir(), "data.json").apply { writeText("eski") }
        assertFailsWith<IllegalStateException> {
            AtomicFile.replace(target) { it.writeText("yarım"); error("disk doldu") }
        }
        assertEquals("eski", target.readText())
        assertEquals(listOf("data.json"), target.parentFile.list()!!.toList())
    }

    @Test
    fun concurrentWritersNeverCorruptTheFile() {
        val target = File(tempDir(), "data.json")
        val pool = Executors.newFixedThreadPool(8)
        val body = "x".repeat(50_000)
        val futures = (0 until 200).map { n ->
            pool.submit { AtomicFile.replace(target) { it.writeText("$n:$body") } }
        }
        // Eşzamanlı yazımların hiçbiri hata vermemeli (ortak geçici dosya olsaydı çakışırdı)
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(target.readText().endsWith(body))
        assertTrue(Regex("""\d+:x+""").matches(target.readText()), "içerik bütün olmalı")
        assertEquals(listOf("data.json"), target.parentFile.list()!!.toList())
    }
}
