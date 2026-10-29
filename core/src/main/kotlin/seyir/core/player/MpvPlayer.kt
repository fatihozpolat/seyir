package seyir.core.player

import seyir.core.AppDirs
import seyir.core.SeyirLog
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * mpv'yi ayrı süreç (kendi penceresi) olarak başlatır; IPC ile konum izler, altyazı ekler ve pencere
 * durumunu (boyut, konum, büyütülmüş, tam ekran) izler.
 *
 * mpv 0.38 ve üstünde süreç ve pencere bölümden bölüme geçilirken korunur: ilk [play] dosyasız bir mpv açar ve
 * dosyayı IPC ile yükler, sonraki [play] çağrıları (kullanıcının seçimi, sonraki/önceki bölüm, yedek kaynak) süreç
 * hâlâ açıksa yeni dosyayı AYNI pencereye `loadfile` ile yükler. Önceki oturumun dinleyicisine "değiştirildi"
 * ([PlaybackSummary.replaced]) bitişi gider. Süreç kapanmışsa (kullanıcı pencereyi kapattı), arayüz ayarı değiştiyse
 * ya da mpv eskiyse yeni süreç açılır.
 *
 * Gömülü kipte ([PlayRequest.embed]) mpv uygulamanın penceresine çizer; uygulama durumu [controls] ile izler ve
 * komutları onunla gönderir.
 */
class MpvPlayer internal constructor(
    val command: MpvCommand,
    probe: () -> WindowProbe,
    private val runtime: MpvRuntime,
    reuseOverride: Boolean?,
) {
    constructor(command: MpvCommand) : this(command, { WindowProbe.platform() }, RealMpvRuntime(command), null)

    private val probe by lazy(probe)
    private val lock = Any()
    private var host: MpvHost? = null

    /** Gömülü oynatmanın durumu ve komutları; bütün süreçler boyunca aynı nesnedir. */
    val controls = PlayerControls()

    /** Bu mpv uygulama penceresine gömülebilir mi (Flatpak kum havuzu dış pencereye çizemez). */
    val canEmbed: Boolean get() = command.sandboxIpcDir == null

    /** mpv dosyasız açılıp dosyalar `loadfile` ile yüklenebilir mi (0.38+); yavaş olabilir (süreç başlatır), bir kez bakılır. */
    private val reuse: Boolean by lazy {
        reuseOverride ?: MpvLoad.supportsReuse(runtime.versionOutput()?.let(MpvUi::parseVersion)).also {
            if (!it) SeyirLog.i(TAG, "mpv sürümü pencere yeniden kullanımı için eski ya da okunamadı; her oynatma yeni pencere açacak")
        }
    }

    fun play(request: PlayRequest, listener: PlaybackListener): PlaybackSession {
        val reusable = reuse
        val session = MpvSession(request, listener)
        synchronized(lock) { attach(session, reusable) }
        return session
    }

    /** Oturum host'a bağlanır: uygun açık pencere varsa dosya oraya yüklenir, yoksa yeni süreç açılır. */
    private fun attach(session: MpvSession, reusable: Boolean) {
        val old = host
        val key = HostKey.of(session.request)
        if (old != null && old.reusable && reusable && old.key == key && old.enqueue(session)) return
        old?.shutdown(replaced = true)
        host = MpvHost(command, reusable, key, { probe }, runtime, session, controls) { orphan ->
            // Süreç, dosya yüklenmeden kapandı (kullanıcı pencereyi kapattı); istek kaybolmasın
            synchronized(lock) { attach(orphan, reuse) }
        }.also { it.start() }
    }

    /** Pencere dosya bitmiş, boşta açıksa kapatır; oynatılan bir dosya varsa hiçbir şey yapmaz. */
    fun closeIdle() {
        synchronized(lock) { host?.closeIfIdle() }
    }

    /** Açık mpv penceresini (varsa) oynatma sürerken bile kapatır; uygulama kapanırken kullanılır. */
    fun close() {
        synchronized(lock) {
            host?.shutdown(replaced = false)
            host = null
        }
    }

    companion object {
        private const val TAG = "MpvPlayer"

        /** [MpvLocator.find] ile mpv'yi bulup oynatıcı kurar; bulunamazsa null. */
        fun create(configuredPath: String? = null): MpvPlayer? =
            MpvLocator.find(configuredPath)?.let { MpvPlayer(it) }
    }
}

/**
 * Süreç düzeyinde ayarlar: değişirlerse açık pencereye dosya yüklenemez, yeni süreç gerekir
 * (arayüz paketi ve dosya başına olmayan ek seçenekler süreç açılırken verilir).
 */
internal data class HostKey(val ui: PlayerUi?, val processArgs: List<String>, val embed: Long? = null) {
    companion object {
        fun of(request: PlayRequest) = HostKey(
            if (request.embed != null) null else request.ui,
            MpvLoad.splitExtraArgs(request.extraArgs).second,
            request.embed,
        )
    }
}

/** mpv sürecinin başlatılması ve IPC kanalının açılması; testlerde sahtesi verilir. */
internal interface MpvRuntime {
    /** IPC için benzersiz uç nokta. */
    fun newEndpoint(): String

    fun start(argv: List<String>): Process

    /** Kanal henüz oluşmadıysa [IOException] fırlatır; çağıran yeniden dener. */
    fun open(endpoint: String): IpcTransport

    /** `mpv --version` çıktısı; alınamazsa null. */
    fun versionOutput(): String?

    /** Süreç bittikten sonra uç noktanın artığını siler. */
    fun cleanup(endpoint: String)
}

internal class RealMpvRuntime(private val command: MpvCommand) : MpvRuntime {
    override fun newEndpoint(): String = MpvIpcTransports.newEndpoint(AppDirs.isWindows, command.sandboxIpcDir)

    override fun start(argv: List<String>): Process = ProcessBuilder(argv).redirectErrorStream(true).start()

    override fun open(endpoint: String): IpcTransport = MpvIpcTransports.open(endpoint, AppDirs.isWindows)

    override fun versionOutput(): String? = try {
        val process = ProcessBuilder(command.argv + "--version").redirectErrorStream(true).start()
        runCatching { process.outputStream.close() }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (process.waitFor(5, TimeUnit.SECONDS)) output else null.also { process.destroyForcibly() }
    } catch (e: Exception) {
        SeyirLog.w("MpvPlayer", "mpv sürümü okunamadı: ${e.message}")
        null
    }

    override fun cleanup(endpoint: String) {
        if (!AppDirs.isWindows) runCatching { File(endpoint).delete() }
    }
}

/**
 * Tek bir dosyanın oynatma oturumu. Durumuna yalnızca sahibi [MpvHost]'un iş parçacığı dokunur; [stop] ve
 * sonraki/önceki bölüm ayarları her iş parçacığından çağrılabilir.
 */
internal class MpvSession(
    val request: PlayRequest,
    val listener: PlaybackListener,
) : PlaybackSession {
    @Volatile
    var host: MpvHost? = null

    val progress = PlaybackProgress()
    val finished = CountDownLatch(1)
    private val finishedFlag = AtomicBoolean(false)

    /** Bu oturumun dosyasının `start-file` olayı görüldü; ondan önceki olaylar önceki dosyaya aittir. */
    var armed = false

    /** `loadfile` yanıtındaki çalma listesi kimliği; olayları dosyayla eşlemek için. */
    var entryId: Long? = null
    var fileLoaded = false
    var started = false
    var subtitlesAdded = false
    var noticeShown = false

    /** `end-file` (eof, error, stop) görüldü; oturum bitmeli ama mpv süreci açık kalabilir. */
    var endedByEvent = false
    var lastProgressAt = 0L

    /** Arayüzdeki "sonraki bölüm" düğmesi için bildirilen başlık; her değişiklikte [nextVersion] artar. */
    @Volatile
    var nextTitle: String? = null
    val nextVersion = AtomicInteger(0)
    var nextSentVersion = 0

    /** Arayüzdeki "önceki bölüm" düğmesi için bildirilen başlık; her değişiklikte [previousVersion] artar. */
    @Volatile
    var previousTitle: String? = null
    val previousVersion = AtomicInteger(0)
    var previousSentVersion = 0

    override val isRunning: Boolean get() = finished.count > 0L

    /** Bölüm düğmeleri arayüz betiğinde mi (gömülü kipte betik yüklenmez, düğmeleri uygulama çizer). */
    val scriptUi: Boolean get() = request.ui != null && request.embed == null

    /** mpv'nin IPC kanalı (yalnızca testler/tanılama için). */
    internal val ipcEndpoint: String? get() = host?.ipcEndpoint

    /** Bu oturumun oynatıldığı mpv sürecinin kimliği (yalnızca testler/tanılama için). */
    internal val processId: Long? get() = host?.pid

    /** true döner: oturum şimdi bitirilebilir (ilk çağrı); sonrakiler false. */
    fun markFinished(): Boolean = finishedFlag.compareAndSet(false, true)

    override fun setNextEpisode(title: String?) {
        if (!scriptUi) return
        nextTitle = title
        nextVersion.incrementAndGet()
        host?.wakeUp()
    }

    override fun setPreviousEpisode(title: String?) {
        if (!scriptUi) return
        previousTitle = title
        previousVersion.incrementAndGet()
        host?.wakeUp()
    }

    override fun stop() {
        host?.stopSession(this)
    }
}

/**
 * Bir mpv sürecinin sahibi: süreci başlatır, IPC'ye dokunan TEK iş parçacığını (izleme döngüsü) çalıştırır ve
 * sırayla oturumları ([MpvSession]) bu süreçte oynatır. Süreç boyunca pencere durumunu izler.
 *
 * [reusable] kipte mpv dosyasız açılır ve her dosya `loadfile` ile yüklenir; dosya bitince (eof, hata) süreç
 * ve pencere açık kalır. Eski kipte ([reusable] false) dosya komut satırında verilir ve süreç dosya bitince kapanır.
 */
internal class MpvHost(
    private val command: MpvCommand,
    val reusable: Boolean,
    val key: HostKey,
    private val probe: () -> WindowProbe,
    private val runtime: MpvRuntime,
    first: MpvSession,
    private val controls: PlayerControls,
    private val onOrphan: (MpvSession) -> Unit,
) {
    /** mpv uygulama penceresine gömülü; pencere durumu izlenmez, denetimi [controls] yapar. */
    private val embedded = key.embed != null

    private val lock = Any()
    private val wake = Semaphore(0)
    private val finished = CountDownLatch(1)
    private val outputTail = OutputTail(20)
    private val dropped = ConcurrentLinkedQueue<MpvSession>()
    private val watchdogStarted = AtomicBoolean(false)
    private val thread = Thread(::run, "seyir-mpv-session").apply { isDaemon = true }
    private val firstRequest = first.request

    // `lock` ile korunur
    private var pending: MpvSession? = null
    private var latest: MpvSession? = null
    private var closed = false

    /** Şu an oynatılan oturum; yalnız izleme iş parçacığı yazar. */
    @Volatile
    private var current: MpvSession? = null

    @Volatile
    private var quitRequested = false

    /** Kapanış yeni bir oturumun yerini açmak içindi (oturumlara "değiştirildi" bildirilir). */
    @Volatile
    private var quitReplaced = false

    /** Bekleyen oturum kapanışta yeniden başlatılmaz (uygulama kendisi kapattı). */
    @Volatile
    private var noAdopt = false

    @Volatile
    private var process: Process? = null

    @Volatile
    var ipcEndpoint: String? = null
        private set

    /** mpv sürecinin kimliği (yalnızca testler/tanılama için); alınamazsa null. */
    val pid: Long? get() = runCatching { process?.pid() }.getOrNull()

    @Volatile
    private var processStarted = false

    @Volatile
    private var windowListener: PlaybackListener = first.listener

    private var idleSince = System.nanoTime()
    private val tracker = WindowTracker(first.request.window)
    private var windowId: Long? = null
    private var lastWindowSampleAt = System.nanoTime() - WINDOW_SAMPLE_NS
    private var autoResizeFrozen = false

    /** Kalite değişiminden sonra eski `start` değerine dönülecek; `playback-restart` beklenir. */
    private var qualityRestore: QualityRestore? = null

    @Volatile
    private var sawRestart = false

    init {
        first.host = this
        latest = first
        if (reusable) {
            pending = first
        } else {
            current = first
            first.armed = true
        }
    }

    fun start() = thread.start()

    fun wakeUp() = wake.release()

    /** Bu süreçte oynatılmak üzere yeni oturum alır; süreç kapanıyorsa ya da kapandıysa false. */
    fun enqueue(session: MpvSession): Boolean {
        synchronized(lock) {
            if (closed || quitRequested || !reusable) return false
            pending?.let { dropped.add(it) }
            pending = session
            latest = session
            windowListener = session.listener
            session.host = this
        }
        wake.release()
        return true
    }

    /** [PlaybackSession.stop]: yalnız en son oturum için mpv'yi kapatır; değiştirilmiş eski oturumlar etkisizdir. */
    fun stopSession(session: MpvSession) {
        synchronized(lock) {
            if (closed || latest !== session) return
            quitRequested = true
            noAdopt = true
        }
        wake.release()
        startWatchdog()
    }

    /** Süreci kapatır; [replaced] ise oturumlara "değiştirildi" bildirilir. */
    fun shutdown(replaced: Boolean) {
        synchronized(lock) {
            if (closed) return
            quitRequested = true
            quitReplaced = replaced
            noAdopt = true
        }
        wake.release()
        startWatchdog()
    }

    /** Oynatılan ya da bekleyen oturum yoksa pencereyi kapatır. */
    fun closeIfIdle() {
        synchronized(lock) {
            if (closed || pending != null || current != null) return
            quitRequested = true
            noAdopt = true
        }
        wake.release()
        startWatchdog()
    }

    /** İzleme döngüsü takılırsa (mpv yanıt vermiyorsa) süreci zorla bitirir. */
    private fun startWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        Thread({
            if (!finished.await(WATCHDOG_SECONDS, TimeUnit.SECONDS)) {
                SeyirLog.w(TAG, "mpv $WATCHDOG_SECONDS sn içinde kapanmadı, sonlandırılıyor")
                process?.destroyForcibly()
            }
        }, "seyir-mpv-watchdog").apply { isDaemon = true }.start()
    }

    private fun run() {
        var hostError: String? = null
        var exitCode: Int? = null
        val messages = firstRequest.messages
        var endpoint: String? = null
        try {
            endpoint = runtime.newEndpoint()
            ipcEndpoint = endpoint
            val windowArgs = if (embedded) emptyList() else WindowGeometry.mpvArgs(firstRequest.window, probe().screens())
            val argv = command.argv + if (reusable) {
                MpvLoad.hostArgs(endpoint, key.ui, key.processArgs, windowArgs, key.embed)
            } else {
                MpvArgs.build(firstRequest, endpoint, windowArgs)
            }
            SeyirLog.d(TAG, "mpv başlatılıyor: ${argv.joinToString(" ")}")
            val proc = try {
                runtime.start(argv)
            } catch (e: IOException) {
                hostError = messages.startFailed(e.message)
                return
            }
            process = proc
            processStarted = true
            runCatching { proc.outputStream.close() }
            drainOutput(proc)

            if (quitRequested) proc.destroyForcibly()
            val client = connect(proc, endpoint)
            if (client != null) {
                client.use { monitor(it, proc) }
            } else if (proc.isAlive && !quitRequested) {
                if (reusable) {
                    // Dosyayı yükleyecek kanal yok; pencere boş kalırdı
                    hostError = messages.startFailed("IPC")
                    proc.destroyForcibly()
                } else {
                    SeyirLog.w(TAG, "mpv IPC kanalına bağlanılamadı; konum izlenemeyecek, altyazı eklenemeyecek")
                }
            }
            awaitExit(proc)
            exitCode = proc.exitValue()
        } catch (e: Throwable) {
            SeyirLog.e(TAG, "oynatma oturumu beklenmedik şekilde bitti", e)
            hostError = messages.sessionFailed(e.message ?: e::class.simpleName)
            process?.destroyForcibly()
        } finally {
            endpoint?.let { runtime.cleanup(it) }
            finish(hostError, exitCode)
        }
    }

    /** Süreç bitti: açık oturumları bitirir, bekleyen oturumu yeniden başlatır ya da bitirir. */
    private fun finish(hostError: String?, exitCode: Int?) {
        val orphan = synchronized(lock) {
            closed = true
            pending.also { pending = null }
        }
        releaseControls()
        val messages = firstRequest.messages
        // Son pencere durumu hemen saklanmalı
        tracker.current.takeIf { it.isMeaningful }?.let { state ->
            safely("onWindowState") { windowListener.onWindowState(state, final = true) }
        }
        val processError = when {
            hostError != null -> hostError
            quitRequested || exitCode == null || exitCode == 0 -> null
            else -> messages.exitedWithCode(exitCode, outputTail.last(6).joinToString(" | "))
        }
        current?.let { session ->
            current = null
            finishSession(session, processError, userStopped = quitRequested, replaced = quitRequested && quitReplaced)
        }
        drainDropped()
        if (orphan != null) {
            when {
                hostError != null -> finishSession(orphan, hostError, userStopped = false, replaced = false)
                quitRequested && noAdopt -> finishSession(orphan, null, userStopped = true, replaced = quitReplaced)
                else -> onOrphan(orphan)
            }
        }
        finished.countDown()
    }

    /** Çıktıyı tüketir (boru dolup mpv'yi kilitlemesin), son satırları saklar. */
    private fun drainOutput(proc: Process) {
        Thread({
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    outputTail.add(line)
                    SeyirLog.d(TAG, "mpv: $line")
                }
            } catch (_: IOException) {
            }
        }, "seyir-mpv-output").apply { isDaemon = true }.start()
    }

    /** mpv kanalı açana kadar yeniden dener; süreç ölürse ya da durdurulursa vazgeçer. */
    private fun connect(proc: Process, endpoint: String): MpvIpcClient? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECT_TIMEOUT_SECONDS)
        while (proc.isAlive && !quitRequested && System.nanoTime() < deadline) {
            try {
                return MpvIpcClient(runtime.open(endpoint), ::onMpvEvent)
            } catch (_: IOException) {
                Thread.sleep(50)
            }
        }
        return null
    }

    private fun onMpvEvent(event: MpvEvent) {
        if (embedded && controls.owner === this) trackState(event)
        current?.let { handleEvent(it, event) }
    }

    /** Gömülü kipte mpv olaylarını denetim durumuna işler. */
    private fun trackState(event: MpvEvent) {
        when (event.name) {
            "property-change" -> {
                val name = event.fields.get("name")?.asText() ?: return
                controls.update { PlayerStateMapping.apply(it, name, event.fields.get("data")) }
            }
            "start-file" -> controls.update { it.copy(loaded = false, position = null, cachedUntil = null) }
            "file-loaded" -> controls.update { it.copy(loaded = true) }
            "end-file" -> if (event.reason != "redirect") controls.update { it.copy(loaded = false) }
            "playback-restart" -> sawRestart = true
        }
    }

    /** Gömülü kipte denetimi bu sürece bağlar: özellikler izlenir, komutlar bu döngüye gelir. */
    private fun claimControls(client: MpvIpcClient) {
        if (!embedded) return
        controls.owner = this
        controls.queue.clear()
        PlayerStateMapping.OBSERVED.forEachIndexed { index, name ->
            val response = client.request(listOf("observe_property", index + 1, name))
            if (!response.isSuccess) SeyirLog.w(TAG, "özellik izlenemiyor ($name): ${response.error}")
        }
        // Kullanıcının ses ayarı ve altyazı payı yeni süreçte de geçerli olsun (yeni mpv varsayılanla açılır)
        val previous = controls.state.value
        client.request(listOf("set_property", "volume", previous.volume))
        client.request(listOf("set_property", "mute", previous.muted))
        controls.subtitleMargin?.let { client.request(listOf("set_property", "sub-margin-y", it)) }
        controls.wake = ::wakeUp
    }

    private fun releaseControls() {
        qualityRestore = null
        if (!embedded || controls.owner !== this) return
        controls.wake = null
        controls.owner = null
        controls.reset()
    }

    /** Uygulamanın gönderdiği komutları sırayla uygular. */
    private fun drainControls(client: MpvIpcClient) {
        if (!embedded || controls.owner !== this) return
        while (true) {
            val command = controls.queue.poll() ?: break
            val response = when (command) {
                is ControlCommand.SetPaused -> client.request(listOf("set_property", "pause", command.paused))
                ControlCommand.TogglePause -> client.request(listOf("cycle", "pause"))
                is ControlCommand.SeekTo -> client.request(listOf("seek", command.seconds.coerceAtLeast(0.0), "absolute"))
                is ControlCommand.SeekBy -> client.request(listOf("seek", command.seconds, "relative"))
                is ControlCommand.SetVolume -> {
                    client.request(listOf("set_property", "mute", false))
                    client.request(listOf("set_property", "volume", command.volume))
                }
                is ControlCommand.SetMuted -> client.request(listOf("set_property", "mute", command.muted))
                is ControlCommand.SetSpeed -> client.request(listOf("set_property", "speed", command.speed))
                is ControlCommand.SelectSubtitle -> client.request(listOf("set_property", "sid", command.id ?: "no"))
                is ControlCommand.SelectAudio -> client.request(listOf("set_property", "aid", command.id))
                is ControlCommand.SelectQuality -> switchQuality(client, command.editionId)
                is ControlCommand.SetSubtitleMargin -> client.request(listOf("set_property", "sub-margin-y", command.margin))
                is ControlCommand.SetSubtitleScale -> client.request(listOf("set_property", "sub-scale", command.scale))
            }
            if (!response.isSuccess) SeyirLog.w(TAG, "oynatıcı komutu uygulanamadı ($command): ${response.error}")
        }
        finishQualityRestore(client)
    }

    /**
     * Kalite katmanı (sürüm) değişimi oynatmayı yeniden başlatır ve dosyanın `start` konumuna döner; konum korunsun
     * diye `start` geçici olarak şimdiki konuma çekilir, yeniden başlayınca eski değerine döndürülür.
     */
    private fun switchQuality(client: MpvIpcClient, editionId: Int): MpvResponse {
        // Arabelleğe alırken konum anlık okunamayabilir; son bilinen konum kullanılır
        val position = client.getDouble("time-pos") ?: controls.state.value.position
        val previousStart = client.getProperty("start")?.asText()
        if (position != null) client.request(listOf("set_property", "start", String.format(Locale.ROOT, "%.3f", position)))
        sawRestart = false
        qualityRestore = QualityRestore(previousStart, position, System.nanoTime() + TimeUnit.SECONDS.toNanos(15))
        return client.request(listOf("set_property", "edition", editionId))
    }

    private fun finishQualityRestore(client: MpvIpcClient) {
        val restore = qualityRestore ?: return
        if (!sawRestart && System.nanoTime() < restore.deadline) return
        qualityRestore = null
        restore.previousStart?.let { client.request(listOf("set_property", "start", it)) }
        // `start` uygulanmadıysa elle geri sar
        val now = client.getDouble("time-pos")
        if (restore.position != null && now != null && abs(now - restore.position) > 3) {
            client.request(listOf("seek", restore.position, "absolute+exact"))
        }
    }

    private class QualityRestore(val previousStart: String?, val position: Double?, val deadline: Long)

    /** Olay şu anki oturumun dosyasına aitse işler; önceki dosyadan kalan olaylar atılır. */
    private fun handleEvent(session: MpvSession, event: MpvEvent) {
        if (!session.armed) {
            // Bu dosyanın başladığı bildirilene kadar gelenler önceki dosyaya (ya da boşta durumuna) aittir
            if (event.name == "start-file") session.armed = true
            return
        }
        if (event.name == "end-file") {
            val id = event.entryId
            val own = session.entryId
            if (id != null && own != null && id != own) return
        }
        session.progress.onEvent(event)
        if (event.name == "file-loaded") session.fileLoaded = true
        if (reusable && event.name == "end-file" && event.reason in ENDING_REASONS) session.endedByEvent = true
        when (SeyirScript.parse(event)) {
            SeyirScript.Message.NextRequested -> safely("onNextRequested") { session.listener.onNextRequested() }
            SeyirScript.Message.PreviousRequested -> safely("onPreviousRequested") { session.listener.onPreviousRequested() }
            null -> Unit
        }
    }

    /** İzleme döngüsü; kanal koparsa (mpv kapandı) sessizce biter. */
    private fun monitor(client: MpvIpcClient, proc: Process) {
        try {
            claimControls(client)
            while (!quitRequested && proc.isAlive) {
                drainDropped()
                takePending()?.let { swapTo(client, it) }
                drainControls(client)
                current?.let { session ->
                    poll(client, session)
                    if (session.endedByEvent) {
                        finishEnded(session)
                    } else if (session.armed && session.fileLoaded) {
                        if (!session.noticeShown) showNotice(client, session)
                        if (!session.subtitlesAdded) addSubtitles(client, session)
                        sendEpisodeInfo(client, session)
                        if (!embedded) keepWindowSize(client)
                    }
                }
                if (!embedded) sampleWindow(client)
                closeWhenIdleTooLong()
                // Arayüz açıkken düğme iletileri de bu döngüde okunur; tepki gecikmesi kısa tutulur. Gömülü kipte
                // konum ve önbellek uygulamanın çubuğunu yürüttüğü için daha sık okunur
                val interval = when {
                    embedded -> EMBED_POLL_INTERVAL_MS
                    key.ui != null -> UI_POLL_INTERVAL_MS
                    else -> POLL_INTERVAL_MS
                }
                wake.tryAcquire(interval, TimeUnit.MILLISECONDS)
            }
            if (quitRequested && proc.isAlive) {
                // Kesin son konum ve pencere durumu için bir kez daha sor, sonra nazikçe kapat
                current?.let { runCatching { poll(client, it) } }
                if (!embedded) runCatching { sampleWindow(client, force = true) }
                client.request(listOf("quit"))
            }
        } catch (e: IOException) {
            SeyirLog.d(TAG, "mpv IPC kapandı: ${e.message}")
        }
    }

    private fun takePending(): MpvSession? = synchronized(lock) { pending.also { pending = null } }

    /** Önceden sıraya girip hiç yüklenmeden başkasıyla değiştirilen oturumları bitirir. */
    private fun drainDropped() {
        while (true) {
            val session = dropped.poll() ?: return
            finishSession(session, error = null, userStopped = true, replaced = true)
        }
    }

    /**
     * Açık pencereye yeni dosya yükler: önceki oturum "değiştirildi" olarak bitirilir (son konumu bildirilir),
     * sonra `loadfile ... replace` gönderilir. Yeni dosya açılamazsa oturum hata ile biter, pencere açık kalır.
     */
    private fun swapTo(client: MpvIpcClient, session: MpvSession) {
        current?.let { old ->
            runCatching { poll(client, old) }
            current = null
            finishSession(old, error = null, userStopped = true, replaced = true)
        }
        outputTail.clear()
        // Önceki dosyanın kalite değişimi beklemesi yeni dosyayı eski konuma sarmasın
        qualityRestore = null
        session.armed = false
        current = session
        val response = client.request(MpvLoad.command(session.request))
        if (!response.isSuccess) {
            SeyirLog.w(TAG, "loadfile başarısız: ${response.error}")
            current = null
            idleSince = System.nanoTime()
            finishSession(session, error = "loadfile: ${response.error}", userStopped = false, replaced = false)
            return
        }
        session.entryId = response.data?.get("playlist_entry_id")?.takeIf { it.isIntegralNumber }?.asLong()
        SeyirLog.d(TAG, "dosya yüklendi (kimlik ${session.entryId}): ${session.request.title}")
    }

    /** Dosya kendiliğinden bitti (eof), açılamadı (error) ya da durduruldu (stop); süreç ve pencere açık kalır. */
    private fun finishEnded(session: MpvSession) {
        val reason = session.progress.endReason
        val error = if (reason == "error") outputTail.last(6).joinToString(" | ").ifBlank { null } else null
        current = null
        idleSince = System.nanoTime()
        finishSession(session, error, userStopped = false, replaced = false)
    }

    /** Oturumu bir kez bitirir: önce [PlaybackListener.onExit], sonra [PlaybackListener.onFinished]. */
    private fun finishSession(session: MpvSession, error: String?, userStopped: Boolean, replaced: Boolean) {
        if (!session.markFinished()) return
        val progress = session.progress
        try {
            session.listener.onExit(progress.finalPosition(), progress.duration, progress.reachedEnd(), error)
        } catch (e: Throwable) {
            SeyirLog.w(TAG, "onExit dinleyicisi hata verdi", e)
        }
        safely("onFinished") {
            session.listener.onFinished(
                PlaybackSummary(
                    position = progress.position,
                    duration = progress.duration,
                    reachedEnd = progress.reachedEnd(),
                    error = error,
                    started = session.started,
                    processStarted = processStarted,
                    userStopped = userStopped,
                    endReason = progress.endReason,
                    fileError = progress.fileError,
                    startSeconds = session.request.startSeconds,
                    replaced = replaced,
                )
            )
        }
        session.finished.countDown()
    }

    private fun poll(client: MpvIpcClient, session: MpvSession) {
        val armedBefore = session.armed
        val position = client.getDouble("time-pos")
        val duration = client.getDouble("duration")
        val cachedUntil = if (embedded) client.getDouble("demuxer-cache-time") else null
        // Dosya başlamadan okunan değerler önceki dosyadan kalma olabilir
        if (!armedBefore || !session.armed) return
        if (embedded && controls.owner === this) {
            controls.update { it.copy(position = position ?: it.position, cachedUntil = cachedUntil) }
        }
        session.progress.update(position, duration)
        if (position != null) session.fileLoaded = true
        if (session.fileLoaded && !session.started) {
            session.started = true
            safely("onStarted") { session.listener.onStarted() }
        }
        val pos = session.progress.position
        val now = System.nanoTime()
        if (session.started && pos != null && now - session.lastProgressAt >= TimeUnit.MILLISECONDS.toNanos(PROGRESS_INTERVAL_MS)) {
            session.lastProgressAt = now
            safely("onProgress") { session.listener.onProgress(pos, session.progress.duration) }
        }
    }

    /** Açılışta bir kez, pencerede kısa süre görünen ileti (sıradaki bölüm, kaynak değişimi). */
    private fun showNotice(client: MpvIpcClient, session: MpvSession) {
        session.noticeShown = true
        val text = session.request.osdMessage?.takeIf { it.isNotBlank() } ?: return
        val response = client.request(listOf("show-text", text, NOTICE_MS))
        if (!response.isSuccess) SeyirLog.w(TAG, "ekran iletisi gösterilemedi: ${response.error}")
    }

    /** Sonraki/önceki bölüm bilgisi değiştiyse arayüz betiğine bildirir. */
    private fun sendEpisodeInfo(client: MpvIpcClient, session: MpvSession) {
        if (!session.scriptUi) return
        val version = session.nextVersion.get()
        if (version != session.nextSentVersion) {
            session.nextSentVersion = version
            val response = client.request(SeyirScript.nextInfoCommand(session.nextTitle))
            if (!response.isSuccess) SeyirLog.w(TAG, "sonraki bölüm bilgisi iletilemedi: ${response.error}")
        }
        val previous = session.previousVersion.get()
        if (previous != session.previousSentVersion) {
            session.previousSentVersion = previous
            val response = client.request(SeyirScript.previousInfoCommand(session.previousTitle))
            if (!response.isSuccess) SeyirLog.w(TAG, "önceki bölüm bilgisi iletilemedi: ${response.error}")
        }
    }

    private fun addSubtitles(client: MpvIpcClient, session: MpvSession) {
        session.subtitlesAdded = true
        for (item in SubtitlePlanner.plan(session.request.subtitles, session.request.preferredSubtitleLang)) {
            val response = client.request(SubtitlePlanner.toCommand(item, session.request.messages.subtitleTitle))
            if (!response.isSuccess) {
                SeyirLog.w(TAG, "altyazı eklenemedi (${item.track.title}): ${response.error}")
            }
        }
    }

    /**
     * İlk dosya yüklenince pencerenin video boyutuna göre yeniden boyutlanması kapatılır: sonraki bölümler
     * farklı çözünürlükte olsa da kullanıcının pencere boyutu değişmez. İlk dosya pencereyi videoya göre açar.
     */
    private fun keepWindowSize(client: MpvIpcClient) {
        if (!reusable || autoResizeFrozen) return
        autoResizeFrozen = true
        // Eski sürümde seçenek yoksa hata yanıtı gelir; önemli değil
        client.request(listOf("set_property", "auto-window-resize", false))
    }

    /** Pencere durumunu en çok saniyede bir okur; değiştiyse dinleyiciye bildirir. */
    private fun sampleWindow(client: MpvIpcClient, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastWindowSampleAt < WINDOW_SAMPLE_NS) return
        lastWindowSampleAt = now
        val probe = probe()
        val fullscreen = client.getBoolean("fullscreen") ?: return
        val maximized = client.getBoolean("window-maximized") ?: false
        var minimized = client.getBoolean("window-minimized") ?: false
        var width = 0
        var height = 0
        var x: Int? = null
        var y: Int? = null
        if (!fullscreen && !maximized) {
            if (probe.canReadFrame) {
                val id = windowId ?: client.getLong("window-id")?.also { windowId = it } ?: return
                val frame = probe.frame(id) ?: return
                if (frame.minimized) minimized = true
                width = frame.clientWidth
                height = frame.clientHeight
                x = frame.x
                y = frame.y
            } else {
                width = client.getLong("osd-width")?.toInt() ?: 0
                height = client.getLong("osd-height")?.toInt() ?: 0
            }
            // Ölçüm sırasında büyütme ya da tam ekran geçişi olduysa boyut geçiş anındandır; at
            if (client.getBoolean("fullscreen") != false || client.getBoolean("window-maximized") == true) return
        }
        val state = tracker.update(WindowSample(fullscreen, maximized, minimized, width, height, x, y)) ?: return
        safely("onWindowState") { windowListener.onWindowState(state) }
    }

    /** Hiçbir dosya yokken pencere sonsuza dek açık kalmasın (sıradaki bölüm bulunamadıysa vb.). */
    private fun closeWhenIdleTooLong() {
        if (!reusable || current != null) return
        if (System.nanoTime() - idleSince < TimeUnit.SECONDS.toNanos(IDLE_CLOSE_SECONDS)) return
        synchronized(lock) {
            if (pending != null) return
            SeyirLog.i(TAG, "mpv penceresi $IDLE_CLOSE_SECONDS sn boşta kaldı, kapatılıyor")
            quitRequested = true
            noAdopt = true
        }
    }

    /** Süreç bitene kadar bekler; durdurma istenmişse önce nazik, sonra zorla sonlandırır. */
    private fun awaitExit(proc: Process) {
        while (true) {
            if (proc.waitFor(200, TimeUnit.MILLISECONDS)) return
            if (quitRequested) {
                if (!proc.waitFor(QUIT_GRACE_SECONDS, TimeUnit.SECONDS)) proc.destroyForcibly().waitFor()
                return
            }
            // IPC kopmuşsa ama süreç sürüyorsa (nadir) döngü kullanıcı pencereyi kapatana kadar bekler
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            SeyirLog.w(TAG, "$what dinleyicisi hata verdi", e)
        }
    }

    companion object {
        private const val TAG = "MpvPlayer"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val POLL_INTERVAL_MS = 500L
        private const val UI_POLL_INTERVAL_MS = 250L
        private const val EMBED_POLL_INTERVAL_MS = 100L
        private const val PROGRESS_INTERVAL_MS = 2000L
        private const val QUIT_GRACE_SECONDS = 3L
        private const val WATCHDOG_SECONDS = 8L
        private const val NOTICE_MS = 4000
        private val WINDOW_SAMPLE_NS = TimeUnit.MILLISECONDS.toNanos(1000)

        /** Dosyasız kalan pencerenin kendiliğinden kapanma süresi. */
        internal const val IDLE_CLOSE_SECONDS = 120L

        /** Oturumu bitiren `end-file` nedenleri; "quit" süreç çıkışıyla, "redirect" hiç bitirmez. */
        private val ENDING_REASONS = setOf("eof", "error", "stop")
    }
}
