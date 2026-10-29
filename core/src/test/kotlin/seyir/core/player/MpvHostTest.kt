package seyir.core.player

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Oturum değişim modelinin (aynı mpv penceresine yeni dosya yükleme) sahte mpv ile sınanması: gerçek süreç ya da
 * pencere yok; sahte mpv IPC'nin gerçek biçimini (yanıt, ardından olaylar) taklit eder.
 */
class MpvHostTest {
    /** mpv sürecini ve IPC kanalını taklit eder. */
    private class FakeMpv(val argv: List<String>) {
        private val mapper = jacksonObjectMapper()
        private val toClient = LinkedBlockingQueue<ByteArray>()
        private val exited = CountDownLatch(1)
        private val eof = ByteArray(0)
        private var leftover = ByteArray(0)

        val commands = CopyOnWriteArrayList<List<Any?>>()
        val props = java.util.concurrent.ConcurrentHashMap<String, Any?>(
            mapOf(
                "fullscreen" to false,
                "window-maximized" to false,
                "window-minimized" to false,
                "window-id" to 4242,
                "osd-width" to 1280,
                "osd-height" to 720,
            ),
        )

        @Volatile var exitCode = 0
        @Volatile var failNextLoad = false
        @Volatile var currentEntry = 0L
        private var nextEntry = 1L
        val alive: Boolean get() = exited.count > 0

        val process = object : Process() {
            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
            override fun getInputStream(): InputStream = InputStream.nullInputStream()
            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
            override fun waitFor(): Int {
                exited.await()
                return exitCode
            }

            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
            override fun exitValue(): Int {
                if (exited.count > 0) throw IllegalThreadStateException()
                return exitCode
            }

            override fun destroy() = exit(137)
            override fun isAlive(): Boolean = exited.count > 0
        }

        val transport = object : IpcTransport {
            override fun read(buffer: ByteArray): Int {
                if (leftover.isEmpty()) {
                    val chunk = toClient.take()
                    if (chunk === eof) return -1
                    leftover = chunk
                }
                val count = minOf(buffer.size, leftover.size)
                System.arraycopy(leftover, 0, buffer, 0, count)
                leftover = leftover.copyOfRange(count, leftover.size)
                return count
            }

            override fun write(bytes: ByteArray) = handle(mapper.readTree(bytes))
            override fun close() {}
        }

        fun emit(json: String) {
            toClient.put((json + "\n").toByteArray())
        }

        @Synchronized
        fun exit(code: Int = 0) {
            if (!alive) return
            exitCode = code
            exited.countDown()
            toClient.put(eof)
        }

        private fun respond(id: Long, error: String = "success", data: String? = null) {
            val dataPart = if (data != null) ""","data":$data""" else ""
            emit("""{"request_id":$id,"error":"$error"$dataPart}""")
        }

        private fun handle(node: JsonNode) {
            val id = node.get("request_id").asLong()
            val command = node.get("command").map { if (it.isNumber) it.numberValue() else it.asText() }
            commands += command
            when (command[0]) {
                "get_property" -> {
                    val value = props[command[1] as String]
                    if (value == null) respond(id, "property unavailable") else respond(id, data = mapper.writeValueAsString(value))
                }
                "loadfile" -> {
                    val entry = nextEntry++
                    respond(id, data = """{"playlist_entry_id":$entry}""")
                    val previous = currentEntry
                    if (previous != 0L) emit("""{"event":"end-file","reason":"stop","playlist_entry_id":$previous}""")
                    currentEntry = entry
                    emit("""{"event":"start-file","playlist_entry_id":$entry}""")
                    if (failNextLoad) {
                        failNextLoad = false
                        currentEntry = 0
                        props.remove("time-pos")
                        emit("""{"event":"end-file","reason":"error","playlist_entry_id":$entry,"file_error":"loading failed"}""")
                    } else {
                        props["time-pos"] = 10.0
                        props["duration"] = 100.0
                        emit("""{"event":"file-loaded"}""")
                    }
                }
                "quit" -> {
                    respond(id)
                    emit("""{"event":"end-file","reason":"quit","playlist_entry_id":$currentEntry}""")
                    exit(0)
                }
                else -> respond(id)
            }
        }

        /** Dosya doğal bitti (mpv boşta kalır). */
        fun endFile(reason: String, extra: String = "") {
            val entry = currentEntry
            currentEntry = 0
            props.remove("time-pos")
            emit("""{"event":"end-file","reason":"$reason","playlist_entry_id":$entry$extra}""")
            emit("""{"event":"idle"}""")
        }

        fun loadCommands() = commands.filter { it[0] == "loadfile" }
    }

    private class FakeRuntime(private val version: String? = "mpv v0.41.0-1227-ge471496c5 Copyright") : MpvRuntime {
        val processes = CopyOnWriteArrayList<FakeMpv>()
        private var counter = 0

        override fun newEndpoint() = "fake-endpoint-${counter++}"

        override fun start(argv: List<String>): Process {
            val mpv = FakeMpv(argv)
            processes += mpv
            return mpv.process
        }

        override fun open(endpoint: String): IpcTransport {
            val index = endpoint.substringAfterLast('-').toInt()
            return processes.getOrNull(index)?.transport ?: throw IOException("henüz yok")
        }

        override fun versionOutput() = version
        override fun cleanup(endpoint: String) {}
    }

    private class FakeProbe(var frame: WindowFrame? = null, val canRead: Boolean = true) : WindowProbe {
        override val canReadFrame: Boolean get() = canRead
        override fun frame(windowId: Long): WindowFrame? = frame
        override fun screens(): List<ScreenRect> = listOf(ScreenRect(0, 0, 1920, 1080))
    }

    private class Recorder : PlaybackListener {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)

        @Volatile var summary: PlaybackSummary? = null
        @Volatile var exitPosition: Double? = null
        val order = CopyOnWriteArrayList<String>()
        val windowStates = CopyOnWriteArrayList<Pair<PlayerWindowState, Boolean>>()
        val nextRequests = CopyOnWriteArrayList<String>()

        override fun onStarted() = started.countDown()
        override fun onExit(position: Double?, duration: Double?, reachedEnd: Boolean, error: String?) {
            exitPosition = position
            order += "exit"
        }

        override fun onFinished(summary: PlaybackSummary) {
            this.summary = summary
            order += "finished"
            finished.countDown()
        }

        override fun onWindowState(state: PlayerWindowState, final: Boolean) {
            windowStates += state to final
        }

        override fun onNextRequested() {
            nextRequests += "next"
        }
    }

    private val runtime = FakeRuntime()
    private val probe = FakeProbe()
    private fun player(reuse: Boolean? = true, rt: MpvRuntime = runtime) =
        MpvPlayer(MpvCommand(listOf("mpv")), { probe }, rt, reuse)

    private fun request(url: String, title: String = "Başlık $url", ui: PlayerUi? = null, extra: List<String> = emptyList(), window: PlayerWindowState? = null) =
        PlayRequest(url, title, startSeconds = 5.0, ui = ui, extraArgs = extra, window = window)

    private fun await(latch: CountDownLatch, what: String) =
        assertTrue(latch.await(10, TimeUnit.SECONDS), "$what zamanında gelmedi")

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "$what zamanında olmadı")
            Thread.sleep(20)
        }
    }

    @Test
    fun `second play loads into the same process and tells the old listener it was replaced`() {
        val player = player()
        val a = Recorder()
        val b = Recorder()
        val sessionA = player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        val sessionB = player.play(request("http://h/b.m3u8", extra = listOf("--hls-bitrate=500000")), b)
        await(b.started, "B başlamadı")

        assertEquals(1, runtime.processes.size, "ikinci oynatma yeni süreç açmamalı")
        val mpv = runtime.processes[0]
        assertTrue(mpv.alive)
        assertTrue("--idle=yes" in mpv.argv, "süreç dosyasız açılmalı")
        assertTrue(mpv.argv.none { it == "http://h/a.m3u8" }, "adres komut satırında olmamalı")

        val summaryA = assertNotNull(a.summary)
        assertTrue(summaryA.replaced)
        assertTrue(summaryA.userStopped)
        assertNull(summaryA.error)
        assertFalse(summaryA.startFailed, "değiştirilen oturum açılamadı sayılmamalı")
        assertFalse(summaryA.naturalEnd, "değiştirilen oturum bitti sayılmamalı")
        assertEquals(10.0, a.exitPosition, "eski oturumun son konumu bildirilmeli")
        assertEquals(listOf("exit", "finished"), a.order)
        assertFalse(sessionA.isRunning)

        assertNull(b.summary)
        assertTrue(sessionB.isRunning)
        val loads = mpv.loadCommands()
        assertEquals(2, loads.size)
        assertEquals("http://h/a.m3u8", loads[0][1])
        assertEquals("http://h/b.m3u8", loads[1][1])
        assertEquals("replace", loads[1][2])
        assertTrue((loads[1][4] as String).contains("hls-bitrate=500000"))
        assertFalse((loads[0][4] as String).contains("hls-bitrate"))
        player.close()
    }

    @Test
    fun `late end-file of the previous file does not end the new session`() {
        val player = player()
        val a = Recorder()
        val b = Recorder()
        player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        player.play(request("http://h/b.m3u8"), b)
        await(b.started, "B başlamadı")
        val mpv = runtime.processes[0]
        // Önceki dosyanın (kimlik 1) geç gelen bitişi
        mpv.emit("""{"event":"end-file","reason":"eof","playlist_entry_id":1}""")
        Thread.sleep(800)
        assertNull(b.summary, "eski dosyanın olayı yeni oturumu bitirmemeli")
        player.close()
    }

    @Test
    fun `new file that fails to open ends only the new session with an error and keeps the window`() {
        val player = player()
        val a = Recorder()
        val b = Recorder()
        player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        runtime.processes[0].failNextLoad = true
        player.play(request("http://h/bad.m3u8"), b)
        await(b.finished, "B bitişi gelmedi")

        val summary = assertNotNull(b.summary)
        assertEquals("error", summary.endReason)
        assertEquals("loading failed", summary.fileError)
        assertTrue(summary.startFailed, "yedek kaynak mantığı çalışabilmeli")
        assertFalse(summary.replaced)
        assertFalse(summary.userStopped)
        assertTrue(runtime.processes[0].alive, "pencere açık kalmalı")

        // Yedek kaynak aynı pencereye yüklenir
        val c = Recorder()
        player.play(request("http://h/good.m3u8"), c)
        await(c.started, "C başlamadı")
        assertEquals(1, runtime.processes.size)
        player.close()
    }

    @Test
    fun `natural end finishes the session but leaves the process idle until the next file`() {
        val player = player()
        val a = Recorder()
        val sessionA = player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        runtime.processes[0].endFile("eof")
        await(a.finished, "A bitişi gelmedi")

        val summary = assertNotNull(a.summary)
        assertTrue(summary.naturalEnd)
        assertTrue(summary.reachedEnd)
        assertEquals(100.0, a.exitPosition, "eof'ta son konum süre olmalı")
        assertFalse(summary.replaced)
        assertFalse(sessionA.isRunning)
        assertTrue(runtime.processes[0].alive, "pencere sonraki bölüm için açık kalmalı")

        val b = Recorder()
        player.play(request("http://h/b.m3u8"), b)
        await(b.started, "B başlamadı")
        assertEquals(1, runtime.processes.size)
        assertNull(a.summary?.takeIf { it.replaced }, "zaten biten oturuma ikinci bitiş gitmemeli")
        player.close()
    }

    @Test
    fun `closeIdle quits an idle window but not a playing one`() {
        val player = player()
        val a = Recorder()
        player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        player.closeIdle()
        Thread.sleep(600)
        assertTrue(runtime.processes[0].alive, "oynatılan dosya varken pencere kapanmamalı")

        runtime.processes[0].endFile("eof")
        await(a.finished, "A bitişi gelmedi")
        player.closeIdle()
        waitUntil("pencere kapanması") { !runtime.processes[0].alive }
        assertEquals(0, runtime.processes[0].exitCode)
    }

    @Test
    fun `stop closes the window only for the latest session`() {
        val player = player()
        val a = Recorder()
        val b = Recorder()
        val sessionA = player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        val sessionB = player.play(request("http://h/b.m3u8"), b)
        await(b.started, "B başlamadı")

        sessionA.stop() // değiştirilmiş eski oturum: yeni oynatmayı etkilememeli
        Thread.sleep(600)
        assertTrue(runtime.processes[0].alive)
        assertNull(b.summary)

        sessionB.stop()
        await(b.finished, "B bitişi gelmedi")
        val summary = assertNotNull(b.summary)
        assertTrue(summary.userStopped)
        assertFalse(summary.replaced)
        assertFalse(summary.startFailed)
        assertFalse(summary.naturalEnd)
        waitUntil("süreç çıkışı") { !runtime.processes[0].alive }
    }

    @Test
    fun `stop on a finished session closes the idle window`() {
        val player = player()
        val a = Recorder()
        val session = player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        runtime.processes[0].endFile("eof")
        await(a.finished, "A bitişi gelmedi")
        session.stop() // uygulama kapanırken
        waitUntil("pencere kapanması") { !runtime.processes[0].alive }
    }

    @Test
    fun `window closed by the user ends the session as a user close and the next play opens a new process`() {
        val player = player()
        val a = Recorder()
        player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        val mpv = runtime.processes[0]
        mpv.emit("""{"event":"end-file","reason":"quit","playlist_entry_id":${mpv.currentEntry}}""")
        mpv.exit(0)
        await(a.finished, "A bitişi gelmedi")
        val summary = assertNotNull(a.summary)
        assertFalse(summary.userStopped)
        assertFalse(summary.replaced)
        // Süreç, "quit" olayı okunmadan da bitebilir
        assertTrue(summary.endReason == null || summary.endReason == "quit")
        assertFalse(summary.startFailed)
        assertFalse(summary.naturalEnd)

        val b = Recorder()
        player.play(request("http://h/b.m3u8"), b)
        await(b.started, "B başlamadı")
        assertEquals(2, runtime.processes.size, "kapanmış pencere yerine yeni süreç açılmalı")
        player.close()
    }

    @Test
    fun `request that arrives right after the user closed the window is not lost`() {
        val player = player()
        val a = Recorder()
        val b = Recorder()
        player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        val mpv = runtime.processes[0]
        // Süreç ölür ama izleme döngüsü henüz fark etmeden istek sıraya girer
        mpv.exit(0)
        player.play(request("http://h/b.m3u8"), b)
        await(b.started, "B başlamadı")
        assertEquals(2, runtime.processes.size)
        player.close()
    }

    @Test
    fun `old mpv without loadfile index support opens a process per play`() {
        val rt = FakeRuntime(version = "mpv 0.37.0 Copyright")
        val player = MpvPlayer(MpvCommand(listOf("mpv")), { probe }, rt, null)
        val a = Recorder()
        val b = Recorder()
        player.play(request("http://h/a.m3u8"), a)
        waitUntil("ilk süreç") { rt.processes.size == 1 }
        assertEquals("http://h/a.m3u8", rt.processes[0].argv.last(), "eski kipte adres komut satırında")
        assertTrue("--idle=no" in rt.processes[0].argv)
        player.play(request("http://h/b.m3u8"), b)
        await(a.finished, "A bitişi gelmedi")
        waitUntil("ikinci süreç") { rt.processes.size == 2 }
        val summary = assertNotNull(a.summary)
        assertTrue(summary.replaced)
        assertTrue(summary.userStopped)
        assertFalse(summary.startFailed)
        player.close()
    }

    @Test
    fun `changed interface or process option cannot reuse the window`() {
        val player = player()
        val a = Recorder()
        val b = Recorder()
        val c = Recorder()
        player.play(request("http://h/a.m3u8", ui = PlayerUi("dir1")), a)
        await(a.started, "A başlamadı")
        player.play(request("http://h/b.m3u8", ui = PlayerUi("dir2")), b)
        await(b.started, "B başlamadı")
        assertEquals(2, runtime.processes.size)
        assertTrue(assertNotNull(a.summary).replaced)
        player.play(request("http://h/c.m3u8", ui = PlayerUi("dir2"), extra = listOf("--mute=yes")), c)
        await(c.started, "C başlamadı")
        assertEquals(3, runtime.processes.size)
        assertTrue("--mute=yes" in runtime.processes[2].argv)
        player.close()
    }

    @Test
    fun `episode buttons of the old file are not sent to the new file`() {
        val player = player()
        val ui = PlayerUi("dir")
        val a = Recorder()
        val b = Recorder()
        val sessionA = player.play(request("http://h/a.m3u8", ui = ui), a)
        await(a.started, "A başlamadı")
        sessionA.setNextEpisode("A sonrası")
        val mpv = runtime.processes[0]
        waitUntil("A bilgisi") { mpv.commands.any { it.contains("A sonrası") } }

        val sessionB = player.play(request("http://h/b.m3u8", ui = ui), b)
        await(b.started, "B başlamadı")
        sessionA.setNextEpisode("geç gelen")
        sessionA.setPreviousEpisode("geç gelen önceki")
        Thread.sleep(600)
        assertTrue(mpv.commands.none { it.contains("geç gelen") || it.contains("geç gelen önceki") }, "eski oturumun bilgisi gönderilmemeli")
        val afterSecondLoad = mpv.commands.drop(mpv.commands.indexOf(mpv.loadCommands()[1]))
        assertTrue(afterSecondLoad.none { it.contains("A sonrası") })

        sessionB.setNextEpisode("B sonrası")
        sessionB.setPreviousEpisode("B öncesi")
        waitUntil("B bilgisi") { mpv.commands.any { it.contains("B sonrası") } && mpv.commands.any { it.contains("B öncesi") } }
        assertTrue(mpv.commands.any { it == listOf("script-message-to", "seyir", "seyir-next-info", "B sonrası") })
        player.close()
    }

    @Test
    fun `button messages before the new file starts are dropped`() {
        val player = player()
        val a = Recorder()
        val b = Recorder()
        player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        val mpv = runtime.processes[0]
        player.play(request("http://h/b.m3u8"), b)
        await(b.started, "B başlamadı")
        mpv.emit("""{"event":"client-message","args":["seyir-next"]}""")
        waitUntil("sonraki bölüm isteği") { b.nextRequests.isNotEmpty() }
        player.close()
    }

    @Test
    fun `window state is reported on change, ignores maximized size and is flushed on close`() {
        val player = player()
        probe.frame = WindowFrame(200, 150, 1000, 600)
        val a = Recorder()
        val session = player.play(request("http://h/a.m3u8", window = PlayerWindowState(900, 500, 100, 100)), a)
        await(a.started, "A başlamadı")
        waitUntil("ilk pencere durumu") { a.windowStates.any { it.first == PlayerWindowState(1000, 600, 200, 150) } }

        // Büyütülünce çerçeve tam ekran boyutundadır; normal boyut korunmalı
        runtime.processes[0].props["window-maximized"] = true
        probe.frame = WindowFrame(0, 0, 1920, 1009)
        waitUntil("büyütülmüş durum") { a.windowStates.any { it.first == PlayerWindowState(1000, 600, 200, 150, maximized = true) } }
        assertTrue(a.windowStates.none { it.first.width == 1920 })

        session.stop()
        await(a.finished, "A bitişi gelmedi")
        val last = a.windowStates.last()
        assertTrue(last.second, "kapanışta son durum final olarak bildirilmeli")
        assertEquals(PlayerWindowState(1000, 600, 200, 150, maximized = true), last.first)
    }

    @Test
    fun `saved window state is applied when a new process opens and not when the window is reused`() {
        val player = player()
        val saved = PlayerWindowState(1000, 600, 200, 150, maximized = true)
        val a = Recorder()
        val b = Recorder()
        player.play(request("http://h/a.m3u8", window = saved), a)
        await(a.started, "A başlamadı")
        assertTrue("--geometry=1000x600+200+150" in runtime.processes[0].argv)
        assertTrue("--window-maximized=yes" in runtime.processes[0].argv)
        player.play(request("http://h/b.m3u8", window = PlayerWindowState(300, 200, 5, 5)), b)
        await(b.started, "B başlamadı")
        assertEquals(1, runtime.processes.size)
        assertTrue(runtime.processes[0].commands.none { it.toString().contains("geometry") })
        player.close()
    }

    @Test
    fun `without position support only size and flags are tracked`() {
        val noPosition = FakeProbe(canRead = false)
        val player = MpvPlayer(MpvCommand(listOf("mpv")), { noPosition }, runtime, true)
        val a = Recorder()
        player.play(request("http://h/a.m3u8"), a)
        await(a.started, "A başlamadı")
        waitUntil("boyut") { a.windowStates.any { it.first == PlayerWindowState(1280, 720) } }
        player.close()
    }
}
