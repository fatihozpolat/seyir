import groovy.json.JsonOutput
import java.security.MessageDigest
import java.text.Normalizer
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Seyir eklenti şablonu. Her klasör CloudStream biçiminde bir eklentidir; bu dosya onları Seyir'in
// masaüstü yükleyicisi için JVM jar'ı olarak derler ve depo dosyalarını üretir:
//   ./gradlew makeRepo     → build/repo/ (<Ad>.jar, plugins.json, repo.json)
//   ./gradlew publishRepo  → build/repo içeriğini "builds" dalına iter (Seyir'in okuduğu yer)

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

/** Depo bilgisi (Seyir → Eklentiler → Depolar ekranında görünür). */
val repoName = "Örnek Seyir eklentileri"
val repoDescription = "Seyir eklenti şablonu ile yazılmış eklentiler"

/** Eklenti klasöründeki build.gradle.kts dosyasından ve koddan okunan bilgiler. */
data class PluginMeta(
    val internalName: String,
    val displayName: String?,
    val version: Int,
    val description: String?,
    val status: Int,
    val language: String?,
    val iconUrl: String?,
    val tvTypes: List<String>,
    val authors: List<String>,
    val pluginClassName: String?,
)

// Satır başındaki // yorumları ve /* */ bloklarını atar (satır içindeki "https://" bozulmasın)
fun stripComments(source: String): String =
    source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

fun readPluginMeta(dir: File): PluginMeta {
    val build = stripComments(File(dir, "build.gradle.kts").readText())
    fun string(key: String) = Regex("""\b$key\s*=\s*"((?:[^"\\]|\\.)*)"""").find(build)?.groupValues?.get(1)
    fun int(key: String) = Regex("""\b$key\s*=\s*(\d+)""").find(build)?.groupValues?.get(1)?.toInt()
    fun list(key: String) =
        Regex("""\b$key\s*=\s*listOf\(([^)]*)\)""").find(build)?.groupValues?.get(1)
            ?.let { body -> Regex(""""([^"]*)"""").findAll(body).map { it.groupValues[1] }.toList() }
            ?: emptyList()

    // Giriş noktası: @CloudstreamPlugin ile işaretli sınıf (adı rakamla başlıyorsa ters tırnaklı)
    val pluginClassName = dir.resolve("src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .firstNotNullOfOrNull { file ->
            val code = file.readText()
            val className = Regex("""@CloudstreamPlugin\s+(?:open\s+|public\s+)*class\s+`?(\w+)`?""")
                .find(code)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
            val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(code)?.groupValues?.get(1)
            if (pkg == null) className else "$pkg.$className"
        }

    return PluginMeta(
        internalName = dir.name,
        displayName = string("displayName"),
        version = int("version") ?: 1,
        description = string("description"),
        status = int("status") ?: 3,
        language = string("language"),
        iconUrl = string("iconUrl"),
        tvTypes = list("tvTypes"),
        authors = list("authors"),
        pluginClassName = pluginClassName,
    )
}

/** Jar adı için ASCII karşılık (Filmİzlesene → FilmIzlesene); depolar ve Windows Türkçe harfte sorun çıkarır. */
fun asciiFileName(name: String): String =
    Normalizer.normalize(name.replace('ı', 'i'), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^A-Za-z0-9._-]"""), "_")

/** Dosyanın sha256 özeti (küçük harfli onaltılık); depo kaydına yazılır, Seyir kurulumda doğrular. */
fun sha256Hex(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

// org.json, Seyir API'sindeki Android uyarlamasından gelir; NiceHttp'nin getirdiği org.json:json
// aynı sınıfları farklı davranışla verdiği için sınıf yoluna girmemeli
allprojects {
    configurations.configureEach { exclude(group = "org.json", module = "json") }
}

val jarsDir = layout.buildDirectory.dir("jars")
val pluginProjects = subprojects.toList()

configure(pluginProjects) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    // Çıktı eklenti klasörünü kirletmesin
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("plugin-builds/$name"))

    val meta = readPluginMeta(projectDir)
    extra["pluginMeta"] = meta

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            // Android için yazılmış eklentiler bu uyarılarla derlenir; hata yerine uyarı kalsın
            suppressWarnings.set(true)
            freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions")
        }
    }

    val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        // Seyir çalışırken bu sınıfları zaten sağlar; eklenti jar'ına girmezler
        "compileOnly"(files(rootProject.file("libs/seyir-api.jar")))
        for (lib in listOf("cloudstream-library", "nicehttp", "jsoup", "jackson-kotlin", "coroutines-core", "jspecify", "bouncycastle")) {
            "compileOnly"(catalog.findLibrary(lib).get())
        }
    }

    val generateManifest = tasks.register("generateManifest") {
        val outDir = layout.buildDirectory.dir("generated/manifest")
        inputs.property("meta", meta.toString())
        outputs.dir(outDir)
        doLast {
            val manifest = mapOf(
                "name" to meta.internalName,
                "pluginClassName" to (meta.pluginClassName ?: throw GradleException("${meta.internalName}: @CloudstreamPlugin sınıfı bulunamadı")),
                "version" to meta.version,
                "requiresResources" to false,
            )
            outDir.get().file("manifest.json").asFile.apply {
                parentFile.mkdirs()
                writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))
            }
        }
    }

    extensions.configure<SourceSetContainer> {
        named("main") { resources.srcDir(generateManifest) }
    }

    tasks.named<Jar>("jar") {
        archiveFileName.set("${asciiFileName(project.name)}.jar")
        destinationDirectory.set(jarsDir)
    }
}

val repoDir = layout.buildDirectory.dir("repo")

/**
 * Eklentileri derler ve build/repo'ya depoyu yazar. Derlenemeyen eklenti listeye girmez; bütün depoyu
 * durdurmasın diye `./gradlew makeRepo --continue` ile çalıştırılabilir.
 */
val makeRepo = tasks.register("makeRepo") {
    group = "seyir"
    description = "Eklentileri derler; build/repo altına jar'ları, plugins.json ve repo.json yazar."
    pluginProjects.forEach { dependsOn("${it.path}:jar") }
    doLast {
        val out = repoDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val entries = pluginProjects.sortedBy { it.name }.mapNotNull { p ->
            val meta = p.extra["pluginMeta"] as PluginMeta
            val jar = jarsDir.get().file("${asciiFileName(p.name)}.jar").asFile
            if (!jar.exists()) return@mapNotNull null
            jar.copyTo(File(out, jar.name), overwrite = true)
            mapOf(
                "url" to jar.name,
                "status" to meta.status,
                "version" to meta.version,
                "apiVersion" to 1,
                "name" to (meta.displayName ?: meta.internalName),
                "internalName" to meta.internalName,
                "authors" to meta.authors,
                "description" to meta.description,
                "language" to meta.language,
                "iconUrl" to meta.iconUrl,
                "tvTypes" to meta.tvTypes,
                "fileSize" to jar.length(),
                // Seyir indirilen dosyayı bu özetle doğrular; uyuşmazsa kurulumu reddeder
                "sha256" to sha256Hex(jar),
            )
        }
        File(out, "plugins.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(entries)))
        File(out, "repo.json").writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf(
                        "name" to repoName,
                        "description" to repoDescription,
                        "manifestVersion" to 1,
                        "pluginLists" to listOf("plugins.json"),
                    )
                )
            )
        )
        logger.lifecycle("Depo hazır: $out (${entries.size}/${pluginProjects.size} eklenti)")
    }
}

/**
 * build/repo içeriğini bu deponun "builds" dalına iter (geçmişsiz, her seferinde tek commit).
 * Seyir bu dalı https://raw.githubusercontent.com/<sahip>/<depo>/builds/repo.json olarak okur.
 * GitHub Actions kullanılamadığında yerelde çalıştırılır; Actions'ta iş akışı aynı işi yapar.
 */
tasks.register("publishRepo") {
    group = "seyir"
    description = "build/repo içeriğini origin'deki builds dalına iter."
    dependsOn(makeRepo)
    doLast {
        val dir = repoDir.get().asFile
        fun git(vararg args: String, workDir: File = dir): String {
            val process = ProcessBuilder("git", *args).directory(workDir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "git ${args.joinToString(" ")} başarısız oldu:\n$output" }
            return output.trim()
        }
        val remote = git("remote", "get-url", "origin", workDir = rootDir)
        val source = git("rev-parse", "--short", "HEAD", workDir = rootDir)
        git("init", "-q", "-b", "builds")
        git("add", ".")
        git("-c", "user.name=seyir-builds", "-c", "user.email=seyir-builds@users.noreply.github.com", "commit", "-q", "-m", "Derleme $source")
        git("push", "-q", "-f", remote, "builds")
        File(dir, ".git").deleteRecursively()
        logger.lifecycle("builds dalına itildi ($source)")
    }
}
