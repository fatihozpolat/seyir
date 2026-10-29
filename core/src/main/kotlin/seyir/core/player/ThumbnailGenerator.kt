package seyir.core.player

import seyir.core.AppDirs
import seyir.core.SeyirLog
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** İleri sarma önizlemesi için bir kare: [bgra] satır satır BGRA baytları ([width] × [height] × 4). */
class Thumbnail(val seconds: Double, val width: Int, val height: Int, val bgra: ByteArray)

/**
 * İleri sarma önizlemesi üretir: ikinci, görünmez bir mpv süreci aynı yayını en düşük kalitede açar, istenen
 * konuma sarıp kareyi ham BGRA olarak bir dosyaya yazar (thumbfast'in yöntemi). İstekler birleştirilir: üretim
 * sürerken gelen isteklerden yalnız sonuncusu işlenir. Süreç ilk istekte açılır, [close] ile (ve uygulama kapanırken)
 * kapanır.
 *
 * @param edition HLS yayınında açılacak sürüm (kalite katmanı); önizleme için en düşüğü yeter.
 */
class ThumbnailGenerator(
    private val command: MpvCommand,
    private val url: String,
    val width: Int,
    val height: Int,
    private val edition: Int? = null,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "seyir-thumbnails").apply { isDaemon = true } }
    private val latest = AtomicReference<Pair<Double, (Thumbnail?) -> Unit>?>()
    private val frameFile = File(AppDirs.data, "cache/thumb-${System.nanoTime()}.bgra")
    private val frameBytes = width * height * 4
    private val lock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var process: Process? = null
    private var client: MpvIpcClient? = null
    private var endpoint: String? = null

    /** Son açılış ya da üretim hatasının zamanı; [RETRY_AFTER_MS] geçene kadar yeniden denenmez. */
    private var failedAt = 0L

    /** İlk kare henüz gelmedi: süreç yayını açarken daha uzun beklenir. */
    private var warm = false

    /** Önceki istek zaman aşımına uğradı; geç gelen karesi sonraki isteğe karışmasın diye önce o beklenir. */
    private var lateFrame = false

    /** Uygulama kapanırken süreci öldürür (daemon üretim iş parçacığı o sırada çalışmayabilir). */
    private val shutdownHook = Thread({ process?.destroyForcibly() }, "seyir-thumbnails-exit")

    /** [seconds] konumunun karesini ister; hazır olunca (ya da üretilemezse null ile) [callback] üretim iş parçacığından çağrılır. */
    fun request(seconds: Double, callback: (Thumbnail?) -> Unit) {
        synchronized(lock) {
            if (closed) return
            latest.set(seconds to callback)
            try {
                worker.execute(::drain)
            } catch (_: RejectedExecutionException) {
                // Tam bu sırada kapandı
            }
        }
    }

    private fun drain() {
        val (seconds, callback) = latest.getAndSet(null) ?: return
        if (closed) return
        val frame = try {
            render(seconds)
        } catch (e: Exception) {
            SeyirLog.w(TAG, "Önizleme karesi üretilemedi: ${e.message}")
            failedAt = System.currentTimeMillis()
            shutdownProcess()
            null
        }
        runCatching { callback(frame) }
    }

    private fun render(seconds: Double): Thumbnail? {
        if (failedAt != 0L && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return null
        val ipc = client ?: start() ?: return null
        if (lateFrame) {
            // Önceki sarmanın karesi şimdi gelebilir; yazılmasını bekle ki bu isteğin karesi sanılmasın
            val modified = frameFile.lastModified()
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LATE_FRAME_WAIT_MS)
            while (System.nanoTime() < until && frameFile.lastModified() == modified && !closed) Thread.sleep(25)
            lateFrame = false
        }
        val before = if (frameFile.isFile) frameFile.lastModified() else 0L
        val response = ipc.request(listOf("seek", String.format(Locale.ROOT, "%.3f", seconds), "absolute+keyframes"))
        if (!response.isSuccess) return null
        // Kare dosyası her yeni karede baştan yazılır; değişmesi ve boyutunun tamamlanması beklenir
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(if (warm) FRAME_TIMEOUT_MS else FIRST_FRAME_TIMEOUT_MS)
        while (System.nanoTime() < deadline && !closed) {
            if (frameFile.isFile && frameFile.length() == frameBytes.toLong() && frameFile.lastModified() != before) {
                Thread.sleep(15)
                val bytes = runCatching { frameFile.readBytes() }.getOrNull()
                if (bytes != null && bytes.size == frameBytes) {
                    warm = true
                    return Thumbnail(seconds, width, height, bytes)
                }
            }
            Thread.sleep(25)
        }
        // Eski kare yanlış zamana ait olurdu; hiç göstermemek daha iyi
        lateFrame = true
        return null
    }

    private fun start(): MpvIpcClient? {
        frameFile.parentFile.mkdirs()
        val ep = MpvIpcTransports.newEndpoint(AppDirs.isWindows, command.sandboxIpcDir)
        endpoint = ep
        val argv = command.argv + args(ep) + url
        val proc = synchronized(lock) {
            if (closed) return null
            ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().also {
                process = it
                runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
            }
        }
        runCatching { proc.outputStream.close() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECT_TIMEOUT_S)
        while (proc.isAlive && !closed && System.nanoTime() < deadline) {
            val ipc = try {
                MpvIpcClient(MpvIpcTransports.open(ep, AppDirs.isWindows))
            } catch (_: IOException) {
                Thread.sleep(50)
                continue
            }
            // Dosya açılmadan gelen sarma komutu hata döner; yayının açılması beklenir
            while (proc.isAlive && !closed && System.nanoTime() < deadline) {
                if (ipc.getDouble("duration") != null) {
                    // Açılışta yazılan ilk kare (0:00) ilk isteğin karesi sanılmasın
                    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LATE_FRAME_WAIT_MS)
                    while (!frameFile.isFile && System.nanoTime() < until && !closed) Thread.sleep(25)
                    client = ipc
                    return ipc
                }
                Thread.sleep(100)
            }
            ipc.close()
            break
        }
        SeyirLog.w(TAG, "Önizleme süreci yayını açamadı")
        failedAt = System.currentTimeMillis()
        shutdownProcess()
        return null
    }

    /** thumbfast'in seçenekleri: yalnız görüntü, en hızlı çözme, küçük önbellek, kareyi ham BGRA dosyaya yaz. */
    private fun args(ipc: String): List<String> = buildList {
        add("--no-config")
        add("--msg-level=all=no")
        add("--pause=yes")
        add("--keep-open=always")
        add("--no-terminal")
        add("--load-scripts=no")
        add("--osc=no")
        add("--ytdl=no")
        add("--input-ipc-server=$ipc")
        add("--sub=no")
        add("--audio=no")
        add("--sub-auto=no")
        add("--hr-seek=no")
        add("--demuxer-readahead-secs=0")
        add("--demuxer-max-bytes=512KiB")
        add("--demuxer-lavf-o=extension_picky=0")
        add("--vd-lavc-skiploopfilter=all")
        add("--vd-lavc-software-fallback=1")
        add("--vd-lavc-fast")
        add("--vd-lavc-threads=2")
        add("--hwdec=no")
        add("--vf=scale=$width:$height,format=fmt=bgra")
        add("--sws-scaler=fast-bilinear")
        add("--ovc=rawvideo")
        add("--of=image2")
        add("--ofopts=update=1")
        add("--o=${frameFile.absolutePath}")
        edition?.let { add("--edition=$it") }
        add("--")
    }

    /** Süreci kapatır; hem üretim iş parçacığından hem [close]'dan çağrılabilir. */
    private fun shutdownProcess() {
        val ipc = client
        client = null
        runCatching { ipc?.request(listOf("quit")) }
        runCatching { ipc?.close() }
        val proc = synchronized(lock) { process.also { process = null } }
        if (proc != null) {
            if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
        if (!AppDirs.isWindows) endpoint?.let { runCatching { File(it).delete() } }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latest.set(null)
            worker.shutdown()
        }
        // Kapanış beklenmez: uygulama hemen ardından çıkabilir; süreç burada öldürülür, IPC kanalı üretim iş
        // parçacığı bitince (ya da JVM çıkınca) kapanır
        val proc = synchronized(lock) { process.also { process = null } }
        proc?.destroyForcibly()
        if (proc != null) runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        runCatching { frameFile.delete() }
    }

    companion object {
        private const val TAG = "Thumbnails"

        /** Sürecin açılıp yayını yüklemesi için en uzun süre. */
        private const val CONNECT_TIMEOUT_S = 25L
        private const val FRAME_TIMEOUT_MS = 2_500L
        private const val FIRST_FRAME_TIMEOUT_MS = 20_000L
        private const val LATE_FRAME_WAIT_MS = 1_500L
        private const val RETRY_AFTER_MS = 30_000L

        /** Önizleme genişliği (piksel); yükseklik videonun en-boy oranından, çift sayıya yuvarlanarak. */
        const val WIDTH = 288

        fun heightFor(videoWidth: Int, videoHeight: Int, width: Int = WIDTH): Int {
            if (videoWidth <= 0 || videoHeight <= 0) return width * 9 / 16 / 2 * 2
            return (width.toDouble() * videoHeight / videoWidth / 2).toInt().coerceAtLeast(1) * 2
        }
    }
}
