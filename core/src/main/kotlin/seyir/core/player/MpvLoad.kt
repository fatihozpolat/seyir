package seyir.core.player

import java.util.Locale

/**
 * Var olan mpv penceresine IPC ile `loadfile` komutuyla dosya yüklemek için komut ve seçenek kurulumu.
 *
 * Bu kipte mpv, dosyasız (`--idle=yes`) açılır; her dosya (ilki dahil) `loadfile <adres> replace -1 <seçenekler>`
 * ile yüklenir. `-1` dizin bağımsız değişkeni mpv 0.38'den beri vardır; daha eski sürümde bu kip kullanılmaz
 * ([supportsReuse]) ve her oynatma yeni bir süreç açar.
 *
 * Dosya başına seçenekler yalnız o dosya için geçerlidir; dosya bitince mpv önceki değerlerine döner.
 */
object MpvLoad {
    /** `loadfile` seçenek dizinini (-1) ilk kez getiren mpv sürümü. */
    private const val MIN_MAJOR = 0
    private const val MIN_MINOR = 38

    /**
     * Dosya başına verilen [PlayRequest.extraArgs] seçenekleri; bunlar sonraki dosyaya sızmasın diye süreç
     * açılırken değil, `loadfile` ile verilir. Diğer ek seçenekler süreç düzeyindedir ve değişirse yeni süreç açılır.
     */
    internal val PER_FILE_OPTIONS = setOf("hls-bitrate", "sub-scale")

    /** Window başlığı: dosya yokken (açılış, bölümler arası boşta) görünen ad. */
    internal const val IDLE_TITLE = "Seyir"

    /** mpv sürümü yeniden kullanıma uygun mu (0.38 ve üstü). */
    fun supportsReuse(version: Pair<Int, Int>?): Boolean =
        version != null && (version.first > MIN_MAJOR || (version.first == MIN_MAJOR && version.second >= MIN_MINOR))

    /** `--ad=değer` / `--bayrak` / `--no-bayrak` biçimli seçeneği (ad, değer) çiftine çevirir; seçenek değilse null. */
    internal fun parseArg(arg: String): Pair<String, String>? {
        if (!arg.startsWith("--") || arg.length <= 2) return null
        val body = arg.substring(2)
        val eq = body.indexOf('=')
        if (eq > 0) return body.substring(0, eq) to body.substring(eq + 1)
        if (body.startsWith("no-") && body.length > 3) return body.substring(3) to "no"
        return body to "yes"
    }

    /** Ek seçenekleri dosya başına olanlar ve süreç düzeyinde olanlar diye ayırır. */
    internal fun splitExtraArgs(args: List<String>): Pair<List<Pair<String, String>>, List<String>> {
        val perFile = mutableListOf<Pair<String, String>>()
        val process = mutableListOf<String>()
        for (arg in args) {
            val parsed = parseArg(arg)
            if (parsed != null && parsed.first in PER_FILE_OPTIONS) perFile += parsed else process += arg
        }
        return perFile to process
    }

    private val plainValue = Regex("[A-Za-z0-9._:/+-]+")

    /**
     * Seçenek değerini mpv'nin anahtar-değer listesine güvenle koyar. Sade değerler olduğu gibi gider; virgül,
     * eşittir, boşluk, tırnak, `%`, `\`, köşeli parantez, boş ya da ASCII dışı karakter içerenler mpv'nin
     * uzunluk önekli biçimine (`%n%değer`) çevrilir. `n` BAYT sayısıdır (UTF-8), karakter sayısı değil.
     */
    fun encodeValue(value: String): String =
        if (plainValue.matches(value)) value else "%${value.toByteArray(Charsets.UTF_8).size}%$value"

    /** Bir isteğin dosya başına seçenekleri, mpv'ye verilecek sırayla. */
    fun options(request: PlayRequest): List<Pair<String, String>> {
        val options = mutableListOf<Pair<String, String>>()
        options += "start" to String.format(Locale.ROOT, "%.3f", request.startSeconds.coerceAtLeast(0.0))
        options += "title" to MpvArgs.windowTitle(request.title)
        options += "force-media-title" to MpvArgs.mediaTitle(request.title)
        val wanted = SubtitlePlanner.normalizeLang(request.preferredSubtitleLang)
        if (wanted != null) options += "slang" to SubtitlePlanner.codesFor(wanted).joinToString(",")
        MpvArgs.codepageFor(wanted)?.let { options += "sub-codepage" to it }
        // Sürüm (kalite katmanı) seçimi çalışma anında değiştirilince seçenek dosyadan dosyaya taşınır; her dosya
        // varsayılan sürümle başlasın
        options += "edition" to "auto"
        for (audio in request.audioFiles) options += "audio-files-append" to audio
        options += splitExtraArgs(request.extraArgs).first
        return options
    }

    /** `anahtar=değer,anahtar=değer` metni. */
    fun optionString(options: List<Pair<String, String>>): String =
        options.joinToString(",") { (key, value) -> "$key=${encodeValue(value)}" }

    /** `loadfile <adres> replace -1 <seçenekler>` komutu (IPC JSON bağımsız değişkenleri). */
    fun command(request: PlayRequest): List<Any> =
        listOf("loadfile", request.url, "replace", -1, optionString(options(request)))

    /**
     * Dosyasız açılan, sonradan dosya yüklenecek mpv sürecinin komut satırı. [processArgs] süreç düzeyindeki ek
     * seçeneklerdir; [windowArgs] kayıtlı pencere durumudur ([WindowGeometry.mpvArgs]). [embed] verilirse mpv o
     * pencereye gömülür; pencere durumu ve arayüz paketi kullanılmaz.
     */
    fun hostArgs(
        ipcEndpoint: String,
        ui: PlayerUi?,
        processArgs: List<String>,
        windowArgs: List<String>,
        embed: Long? = null,
    ): List<String> {
        val args = mutableListOf(
            "--force-window=immediate",
            "--idle=yes",
            "--title=$IDLE_TITLE",
            "--input-ipc-server=$ipcEndpoint",
            "--keep-open=no",
        )
        args += MpvArgs.SAFE_DEFAULTS
        if (embed != null) {
            args += MpvArgs.embedArgs(embed)
        } else {
            args += windowArgs
            ui?.let { args += MpvArgs.uiArgs(it) }
        }
        args += processArgs
        return args
    }
}
