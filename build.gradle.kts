plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}

// org.json, core içindeki Android uygulamasından gelir (core/src/main/java/org/json); NiceHttp'nin
// getirdiği org.json:json aynı sınıfları farklı davranışla verdiği için sınıf yoluna girmemeli
allprojects {
    configurations.configureEach { exclude(group = "org.json", module = "json") }
}

/**
 * Eklentilerin derlendiği Seyir API'sini (core jar'ı), sürüm kataloğunu ve Gradle sarmalayıcısını
 * şablona (plugin-template/) ve istenirse bir eklenti deposuna kopyalar: `-PpluginRepoDir=<klasör>`.
 * Çekirdekte eklentilere görünen bir değişiklik yapıldığında çalıştırılır.
 */
tasks.register("syncPluginTemplate") {
    group = "seyir"
    description = "plugin-template/ klasörüne güncel Seyir API'sini, sürüm kataloğunu ve sarmalayıcıyı kopyalar."
    dependsOn(":core:jar")
    // Görev gövdesi project/copy kullanıyor; yapılandırma önbelleğiyle uyumlu değil (nadir, elle çalışan görev)
    notCompatibleWithConfigurationCache("project ve copy{} doğrudan kullanılıyor")
    doLast {
        val coreJar = project(":core").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val targets = listOfNotNull(file("plugin-template"), providers.gradleProperty("pluginRepoDir").orNull?.let(::file))
            .filter { it.isDirectory }
        for (template in targets) {
        copy {
            from(coreJar)
            into(template.resolve("libs"))
            rename { "seyir-api.jar" }
        }
        copy {
            from("gradle/libs.versions.toml")
            into(template.resolve("gradle"))
        }
        copy {
            from("gradlew", "gradlew.bat")
            into(template)
        }
        copy {
            from("gradle/wrapper")
            into(template.resolve("gradle/wrapper"))
        }
        logger.lifecycle("Eşitlendi: ${template.absolutePath}")
        }
    }
}
