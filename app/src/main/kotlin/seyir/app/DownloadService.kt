package seyir.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.OkHttpClient
import okhttp3.Request
import seyir.app.i18n.I18n
import seyir.core.AppDirs
import seyir.core.SeyirLog
import seyir.core.net.Network
import java.io.File
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

class DownloadItem(val title: String, val target: File) {
    var status by mutableStateOf(DownloadStatus.QUEUED)

    /** 0..1; süre/boyut bilinmiyorsa null. */
    var progress by mutableStateOf<Float?>(null)
    var detail by mutableStateOf(I18n.current.downloadQueued)

    /** İndirme sürerken veriler bu dosyaya yazılır; yalnız başarıda [target] adına çevrilir. */
    internal val part: File = File(target.parentFile, target.name + ".part")

    @Volatile internal var cancelled = false
    @Volatile internal var process: Process? = null
}

/**
 * Arayüz güncellemelerini seyreltir: [ready] en az `intervalMs` geçmeden `true` dönmez (ilk çağrı
 * hariç). Her 256 KB'lık parçada Compose durumu yazmak yerine saniyede birkaç güncelleme yapılır.
 */
internal class UpdateThrottle(
    private val intervalMs: Long = DownloadService.PROGRESS_INTERVAL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var last = 0L
    private var first = true

    fun ready(): Boolean {
        val now = clock()
        if (first || now - last >= intervalMs) {
            first = false
            last = now
            return true
        }
        return false
    }
}

/** İndirme hatası; iletisi zaten kullanıcıya gösterilecek metindir. */
private class DownloadFailure(message: String) : Exception(message)

/**
 * İndirmeler. Bağlantı aktarıcıdan geçirilir; HLS/DASH ffmpeg ile tek dosyaya kopyalanır
 * (yeniden kodlama yok), düz video dosyaları doğrudan indirilir.
 *
 * En çok [MAX_CONCURRENT] indirme aynı anda sürer, fazlası sırada bekler. Veri `<ad>.part`
 * dosyasına yazılır ve ancak bitince asıl ada çevrilir; hata ya da iptalde artık dosya silinir.
 */
class DownloadService(private val controller: AppController) {
    val items = mutableStateListOf<DownloadItem>()
    var lastError by mutableStateOf<String?>(null)

    private val workers: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT) { task ->
        Thread(task, "download-worker").apply { isDaemon = true }
    }

    /**
     * İndirme klasörü (yalnız yol; her indirme başlarken [prepareDirectory] ile oluşturulur).
     * Yerel aktarıcıya giden istek vekilsiz kurulur: DoH, vekil ve parçalama ayarları sitenin
     * kendisine aktarıcı üzerinden uygulanır; bu istemci yalnız bu bilgisayardaki adrese bağlanır.
     */
    val directory: File = File(System.getProperty("user.home"), "Downloads/Seyir")

    private fun client(): OkHttpClient =
        Network.client.newBuilder().proxy(Proxy.NO_PROXY).readTimeout(60, TimeUnit.SECONDS).build()

    fun start(context: PlayContext, link: ExtractorLink) {
        lastError = null
        val name = buildString {
            append(context.showName)
            if (context.item.title != context.showName) append(" - ").append(context.item.title)
        }
        val item = DownloadItem(name, uniqueFile(sanitize(name), "mp4"))
        items.add(0, item)
        workers.execute { run(item, context, link) }
    }

    fun cancel(item: DownloadItem) {
        // Durum geçişleri öğe üzerinde kilitlidir: işçi "iptal edildi mi" bakıp RUNNING yazarken iptal araya girmesin
        synchronized(item) {
            if (item.status != DownloadStatus.QUEUED && item.status != DownloadStatus.RUNNING) return
            item.cancelled = true
            item.status = DownloadStatus.CANCELLED
            item.detail = I18n.current.downloadCancelled
        }
        item.process?.destroy()
    }

    /** Sıradan çıkan bir indirmeyi baştan sona yürütür; her sonda `.part` dosyası temizlenir. */
    private fun run(item: DownloadItem, context: PlayContext, link: ExtractorLink) {
        // Kontrol ve RUNNING yazımı tek kilit altında; iptal edilmiş öğe asla RUNNING olmaz
        synchronized(item) {
            if (item.cancelled) return
            item.status = DownloadStatus.RUNNING
            item.detail = I18n.current.downloadStarting
        }
        try {
            prepareDirectory()
            // Aktarıcı oturumları birkaç dakika kullanılmazsa kapanır; bu yüzden adres sıra gelince alınır
            val url = controller.playback.localUrl(link, context.provider)
            if (link.type == ExtractorLinkType.VIDEO) downloadFile(item, url) else downloadStream(item, url)
            synchronized(item) {
                if (!item.cancelled) {
                    Files.move(item.part.toPath(), item.target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    finish(item)
                }
            }
            if (item.cancelled) discard(item)
        } catch (e: Throwable) {
            discard(item)
            synchronized(item) {
                if (!item.cancelled) {
                    item.status = DownloadStatus.FAILED
                    item.detail = if (e is DownloadFailure) e.message.orEmpty() else userMessage(e)
                    SeyirLog.w(TAG, "İndirme başarısız: ${item.title}", e)
                }
            }
        }
    }

    private fun prepareDirectory() {
        directory.mkdirs()
    }

    private fun discard(item: DownloadItem) {
        runCatching { item.part.delete() }
    }

    private fun downloadFile(item: DownloadItem, url: String) {
        client().newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            val throttle = UpdateThrottle()
            item.part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (!item.cancelled) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (throttle.ready()) {
                            item.progress = total?.let { done.toFloat() / it }
                            item.detail = "%.1f MB".format(done / 1_048_576.0) + (total?.let { " / %.1f MB".format(it / 1_048_576.0) } ?: "")
                        }
                    }
                }
            }
        }
    }

    private fun downloadStream(item: DownloadItem, url: String) {
        val ffmpeg = findFfmpeg() ?: throw DownloadFailure(I18n.current.ffmpegMissing)
        val process = ProcessBuilder(
            ffmpeg, "-hide_banner", "-nostdin", "-y",
            "-i", url,
            "-map", "0:v?", "-map", "0:a?", "-c", "copy", "-bsf:a", "aac_adtstoasc",
            "-progress", "pipe:1", "-nostats",
            // Çıktı adı ".part" ile bittiği için kapsayıcı türü uzantıdan sezilemez
            "-f", "mp4",
            item.part.absolutePath,
        ).start()
        item.process = process
        // İptal, süreç başlamadan önce gelmişse beklemeden kapat
        if (item.cancelled) process.destroy()

        var duration: Double? = null
        val stderrTail = ArrayDeque<String>()
        val stderr = thread(isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine { line ->
                if (duration == null) {
                    Regex("""Duration: (\d+):(\d+):(\d+(?:\.\d+)?)""").find(line)?.destructured?.let { (h, m, s) ->
                        duration = h.toInt() * 3600 + m.toInt() * 60 + s.toDouble()
                    }
                }
                synchronized(stderrTail) {
                    stderrTail.addLast(line)
                    if (stderrTail.size > 20) stderrTail.removeFirst()
                }
            }
        }
        val throttle = UpdateThrottle()
        process.inputStream.bufferedReader().forEachLine { line ->
            val (key, value) = line.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (key == "out_time_us" || key == "out_time_ms") {
                val seconds = (value.toLongOrNull() ?: return@forEachLine) / 1_000_000.0
                if (!throttle.ready()) return@forEachLine
                duration?.let { item.progress = (seconds / it).toFloat().coerceIn(0f, 1f) }
                item.detail = seyir.app.ui.formatTime(seconds) + (duration?.let { " / ${seyir.app.ui.formatTime(it)}" } ?: "")
            }
        }
        val code = process.waitFor()
        stderr.join(2000)
        if (item.cancelled) return
        if (code != 0) {
            val tail = synchronized(stderrTail) { stderrTail.lastOrNull().orEmpty() }
            throw DownloadFailure(knownMessage(tail) ?: I18n.current.ffmpegFailed(tail))
        }
    }

    private fun finish(item: DownloadItem) {
        item.progress = 1f
        item.status = DownloadStatus.DONE
        item.detail = I18n.current.downloadDone(item.target.length() / 1_048_576.0)
    }

    private fun findFfmpeg(): String? {
        val exe = if (AppDirs.isWindows) "ffmpeg.exe" else "ffmpeg"
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it, "ffmpeg/$exe") }
        if (bundled?.canExecute() == true) return bundled.absolutePath
        File(AppDirs.data, "ffmpeg/$exe").takeIf { it.canExecute() }?.let { return it.absolutePath }
        return System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, exe) }
            .firstOrNull { it.canExecute() }?.absolutePath
    }

    private fun sanitize(name: String) =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("""\s+"""), " ").trim().take(120)

    /** Var olan dosyayla ve sıradaki başka indirmelerle çakışmayan ad (dosya henüz yazılmamış olabilir). */
    @Synchronized
    private fun uniqueFile(base: String, ext: String): File {
        fun taken(file: File) = file.exists() || items.any { it.target == file }
        var file = File(directory, "$base.$ext")
        var n = 2
        while (taken(file)) file = File(directory, "$base ($n).$ext").also { n++ }
        return file
    }

    internal companion object {
        const val TAG = "DownloadService"
        const val MAX_CONCURRENT = 2

        /** Arayüze ilerleme yazma aralığı (yaklaşık saniyede 4). */
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
