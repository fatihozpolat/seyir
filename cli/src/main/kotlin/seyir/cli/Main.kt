package seyir.cli

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.lagradost.cloudstream3.network.CloudflareKiller
import seyir.core.DomainTracker
import seyir.core.browser.BrowserChallengeSolver
import seyir.core.browser.BrowserLocator
import seyir.core.player.MpvLocator
import seyir.core.relay.StreamKind
import seyir.core.relay.StreamRelay
import seyir.core.PluginCompatibility
import seyir.core.PluginLoader
import seyir.core.ProbeStepKind
import seyir.core.ProviderProbe
import seyir.core.net.DohProvider
import seyir.core.net.Network
import seyir.core.net.NetworkSettings
import seyir.core.playables
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Eklentileri arayüzsüz denemek için komut satırı aracı.
 *
 *   list
 *   home   <sağlayıcı>
 *   search <sağlayıcı> <sorgu...>
 *   load   <sağlayıcı> <url>
 *   links  <sağlayıcı> <data>
 *   probe  <sağlayıcı> <sorgu...>   (arama → ilk sonuç → ilk bölüm → bağlantılar → bağlantı denetimi)
 *   compat <dosya>                  (.jar/.cs3 eklentinin Seyir'de çalışıp çalışmayacağı; kod çalıştırmaz)
 *   fetch  <url> [referer]          (ham sayfa; site değişikliklerini ayıklamak için)
 *   keygen <klasör> | sign <dosya> --key <pem> | verify <dosya>   (sürüm imzalama; bkz. ReleaseCommands)
 */
fun main(args: Array<String>) {
    // CloudStream kütüphanesi günlüklerini println ile basar; komut çıktısı karışmasın diye
    // standart çıktı stderr'e yönlendirilir, sonuçlar [out] ile yazılır.
    out = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
    System.setOut(System.err)

    // Sürüm imzalama komutları ağa ve eklentilere dokunmadan çalışır
    args.getOrNull(0)?.takeIf { it in ReleaseCommands.NAMES }?.let { command ->
        val code = runCatching { ReleaseCommands.run(command, args.toList(), out) }.fold({ 0 }) {
            System.err.println("HATA: ${it.message}")
            1
        }
        exitProcess(code)
    }

    Network.install(
        NetworkSettings(
            fragmentTls = System.getProperty("seyir.fragment") != "false",
            dnsOverHttps = if (System.getProperty("seyir.doh") == "false") null else DohProvider.CLOUDFLARE,
            proxy = System.getProperty("seyir.proxy") ?: System.getenv("SEYIR_PROXY"),
        )
    )

    // -Dseyir.cloudflare=true: Cloudflare sınamalarını sistem tarayıcısıyla çöz (tarayıcı da
    // aktarıcının parçalamalı CONNECT tünelinden geçer)
    if (System.getProperty("seyir.cloudflare") == "true") {
        CloudflareKiller.solver = BrowserChallengeSolver({ BrowserLocator.find(null) }, { relay.proxyAddress })
    }

    // Varsayılan: uygulamanın kurduğu eklentiler; eklenti deposu derlemesi için -Dseyir.plugins=<eklenti deposu>/build/repo
    val pluginDir = System.getProperty("seyir.plugins")?.let(::File) ?: seyir.core.AppDirs.plugins
    val loader = PluginLoader()
    loader.loadAll(pluginDir).flatMap { it.apis }.forEach(domains::applySaved)

    val command = args.getOrNull(0) ?: "list"
    val exitCode = runCatching {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                when (command) {
                    "list" -> listProviders()
                    "home" -> home(provider(args))
                    "search" -> printResults(provider(args).search(rest(args, 2)).orEmpty())
                    "load" -> load(provider(args), rest(args, 2))
                    "links" -> links(provider(args), rest(args, 2))
                    "probe" -> probe(provider(args), args.drop(2).joinToString(" ").ifBlank { null })
                    "fetch" -> fetch(args.getOrNull(1) ?: error("URL gerekli"), args.getOrNull(2))
                    "cast-scan" -> castScan()
                    "compat" -> compat(args.getOrNull(1) ?: error("Dosya yolu gerekli"))
                    "cs3" -> seyir.core.Cs3Converter.convert(File(args[1]), File(args[2])).also { out.println("Çevrildi: ${args[2]}") }
                    else -> error("Bilinmeyen komut: $command")
                }
            }
        }
        0
    }.getOrElse {
        System.err.println("HATA: ${it::class.simpleName}: ${it.message}")
        if (System.getProperty("seyir.log") != null) it.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

private const val TIMEOUT_MS = 180_000L

private lateinit var out: PrintStream
private val domains = DomainTracker()

/** Sağlayıcıyı adıyla bulur ve alan adı taşındıysa güncel adrese geçirir. */
private suspend fun provider(args: Array<String>): MainAPI {
    val name = args.getOrNull(1) ?: error("Sağlayıcı adı gerekli")
    val api = APIHolder.apis.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: error("Sağlayıcı bulunamadı: $name (list komutuyla bakın)")
    domains.refresh(api)?.let { System.err.println("Alan adı güncellendi: $it") }
    return api
}

private fun rest(args: Array<String>, from: Int): String =
    args.drop(from).joinToString(" ").ifBlank { error("Eksik argüman") }

private fun listProviders() {
    APIHolder.apis.sortedBy { it.name }.forEach { api ->
        out.println("${api.name.padEnd(24)} ${api.mainUrl}  [${api.supportedTypes.joinToString()}]")
    }
    out.println("Toplam: ${APIHolder.apis.size} sağlayıcı")
}

private suspend fun home(api: MainAPI) {
    val first = api.mainPage.firstOrNull() ?: error("Ana sayfa tanımı yok")
    val request = domains.rewrite(api, MainPageRequest(first.name, first.data, first.horizontalImages))
    val response = api.getMainPage(1, request)
        ?: error("Ana sayfa boş döndü")
    response.items.forEach { list ->
        out.println("== ${list.name} (${list.list.size})")
        printResults(list.list.take(10))
    }
}

private fun printResults(results: List<SearchResponse>) {
    results.forEachIndexed { i, r -> out.println("${i + 1}. ${r.name}  [${r.type}]  ${r.url}") }
    if (results.isEmpty()) out.println("(sonuç yok)")
}

private suspend fun load(api: MainAPI, url: String) {
    val response = api.load(url) ?: error("load() boş döndü")
    out.println("Başlık : ${response.name}")
    out.println("Tür    : ${response.type}  Yıl: ${response.year}")
    out.println("Poster : ${response.posterUrl}")
    out.println("Özet   : ${response.plot?.take(200)}")
    val items = response.playables()
    out.println("Öğeler : ${items.size}")
    items.take(20).forEach { out.println("  - ${it.title} -> ${it.data}") }
}

private suspend fun links(api: MainAPI, data: String): List<ExtractorLink> {
    val found = mutableListOf<ExtractorLink>()
    val ok = api.loadLinks(
        data,
        false,
        { sub -> out.println("ALTYAZI ${sub.lang}: ${sub.url}") },
        { link ->
            synchronized(found) { found += link }
            out.println("BAĞLANTI [${link.type}] ${link.name} q=${link.quality} -> ${link.url}")
            if (link.referer.isNotBlank()) out.println("         referer: ${link.referer}")
            if (link.headers.isNotEmpty()) out.println("         headers: ${link.headers}")
        },
    )
    out.println("loadLinks sonucu: $ok, ${found.size} bağlantı")
    return found
}

private suspend fun fetch(url: String, referer: String?) {
    val response = app.get(url, referer = referer)
    System.err.println("HTTP ${response.code} ${response.url}")
    response.headers.forEach { (k, v) -> System.err.println("$k: $v") }
    out.println(response.text)
}

private suspend fun probe(api: MainAPI, query: String?) {
    // Oynatma dışındaki adımlar uygulamadaki "Sına" ile aynı kodu kullanır
    val result = ProviderProbe(domains = domains).run(
        api,
        query = query,
        onStep = { step ->
            val detail = step.failure?.let { " (${it.kind}${it.detail?.let { d -> ": $d" } ?: ""})" } ?: step.info?.let { " ($it)" } ?: ""
            out.println("${step.step.name.padEnd(10)} ${step.status}$detail  ${step.millis} ms")
        },
        onSubtitle = { sub -> out.println("ALTYAZI ${sub.lang}: ${sub.url}") },
        onLink = { link ->
            out.println("BAĞLANTI [${link.type}] ${link.name} q=${link.quality} -> ${link.url}")
            if (link.referer.isNotBlank()) out.println("         referer: ${link.referer}")
            if (link.headers.isNotEmpty()) out.println("         headers: ${link.headers}")
        },
    )
    out.println("Durum: ${result.status}")
    // Bağlantı denetimi bilgi amaçlıdır; arama, ayrıntı ve bağlantı bulma başarısızsa komut hata verir
    result.firstFailure?.takeIf { it.first != ProbeStepKind.LINK_CHECK }
        ?.let { (step, failure) -> error("$step başarısız: ${failure.kind} ${failure.detail.orEmpty()}") }
    if (result.links.isEmpty()) error("Bağlantı bulunamadı")
    System.getProperty("seyir.play")?.toIntOrNull()?.let { seconds -> playCheck(api, result.links, seconds) }
}

/** Bir eklenti dosyasının (.jar, .plugin ya da .cs3) Seyir'de çalışıp çalışmayacağını statik olarak denetler. */
private fun compat(path: String) {
    val file = File(path)
    val isAndroid = runCatching { java.util.zip.ZipFile(file).use { it.getEntry("classes.dex") != null } }.getOrDefault(false)
    val converted = if (isAndroid) File.createTempFile("seyir-cs3-", ".jar").also { seyir.core.Cs3Converter.convert(file, it) } else null
    try {
        val report = PluginCompatibility.check(converted ?: file)
        out.println(if (report.compatible) "UYUMLU" else "UYUMSUZ")
        if (report.requiresResources) out.println("Android arayüz kaynakları istiyor")
        report.missingByPackage.forEach { (pkg, classes) -> out.println("  $pkg: ${classes.joinToString { it.substringAfterLast('.') }}") }
    } finally {
        converted?.delete()
    }
}

/** Yerel aktarıcı (oynatma denemesi ve Cloudflare tarayıcısının tüneli için). */
private val relay by lazy {
    StreamRelay({ Network.client }, { host, port -> Network.openTunnel(host, port) }).apply { start() }
}

/**
 * -Dseyir.play=<sn>: bulunan bağlantıları uygulamadaki gibi aktarıcıdan geçirip görüntüsüz mpv
 * ile oynatmayı dener (en yüksek kaliteden başlayarak en çok 3 bağlantı). Bağlantı bulunsa da
 * sunucu oynatmaya izin vermiyor olabilir; bu deneme onu ayırır.
 */
private fun playCheck(api: MainAPI, found: List<ExtractorLink>, seconds: Int) {
    val mpv = MpvLocator.find(System.getProperty("seyir.mpv")) ?: error("mpv bulunamadı (-Dseyir.mpv=<yol>)")
    for (link in found.sortedByDescending { it.quality }.take(3)) {
        val kind = when (link.type) {
            ExtractorLinkType.M3U8 -> StreamKind.HLS
            ExtractorLinkType.DASH -> StreamKind.DASH
            ExtractorLinkType.VIDEO -> StreamKind.FILE
            else -> continue
        }
        // -Dseyir.lan=<bu bilgisayarın ağ adresi>: televizyonun kullandığı yerel ağ kapısından dener
        val url = relay.register(
            link.url, kind, link.headers, link.referer.ifBlank { null },
            runCatching { api.getVideoInterceptor(link) }.getOrNull(),
            lanHost = System.getProperty("seyir.lan"),
        )
        // Yalnız görüntü içeren bağlantıların ayrı ses izleri (uygulamadaki gibi aktarıcıdan)
        val audio = link.audioTracks.map { relay.register(it.url, StreamKind.FILE, it.headers ?: link.headers, link.referer.ifBlank { null }) }
        // Ses biçimi sabitlenir: --ao=null, akış ortasında biçim değişince (ör. DMax'teki RTÜK
        // uyarı klibinden sonra) zaman damgası sıfırlanıp oynatma takılıyor; gerçek ses çıkışında olmuyor
        val process = ProcessBuilder(
            mpv.argv + listOf(
                "--no-config", "--vo=null", "--ao=null", "--audio-samplerate=48000", "--audio-channels=stereo",
                // Canlı akışların zaman damgası 0'dan başlamaz; --start=0 olmadan --length hemen biter
                "--start=0", "--length=$seconds", "--ytdl=no",
                "--network-timeout=30", "--demuxer-lavf-o=extension_picky=0", "--msg-level=all=error,cplayer=info",
            ) + audio.map { "--audio-file=$it" } + listOf("--", url)
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { start() }
        val finished = process.waitFor(seconds + 90L, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(2_000)
        val ok = finished && process.exitValue() == 0
        val detail = if (ok) "" else " (${if (finished) "çıkış ${process.exitValue()}" else "zaman aşımı"}: ${output.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: ""})"
        // Ses izi var mı: mpv parça listesinde "Audio" satırı
        val sound = if (output.lines().any { it.contains("Audio") && it.contains("--aid") }) "ses var" else "SES YOK"
        out.println("OYNATMA ${if (ok) "OK" else "HATA"} ${link.name} q=${link.quality} ($sound${if (audio.isNotEmpty()) ", ${audio.size} ayrı ses izi" else ""})$detail")
        if (ok) return
    }
}

/** Ağdaki Cast alıcılarını listeler (yalnız arar, alıcıda bir şey açmaz). */
private fun castScan() {
    val devices = seyir.core.cast.CastDiscovery.scan()
    if (devices.isEmpty()) out.println("Cast alıcısı bulunamadı")
    devices.forEach { out.println("${it.name.padEnd(28)} ${it.host}:${it.port}  ${it.model.orEmpty()}") }
}
