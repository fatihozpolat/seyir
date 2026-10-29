import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coroutines.swing)
    // Windows başlık çubuğunu koyu temaya almak için (DwmSetWindowAttribute)
    implementation(libs.jna)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Sürüm gradle.properties içindeki seyirVersion değerinden gelir (tek kaynak)
val appVersion = providers.gradleProperty("seyirVersion").get()

// Uygulama kendi sürümünü bu kaynaktan okur (güncelleme denetimi için). Sürüm betiği ön sürümde tam etiketi
// verir (-PseyirReleaseVersion=1.0.0-beta.5); paket sürümü (MSI) yalnız sayısal kısım olarak kalır.
val releaseVersion = providers.gradleProperty("seyirReleaseVersion").orElse(appVersion).get()
val generateVersion = tasks.register("generateVersion") {
    // Yapılandırma önbelleği için görev gövdesi betik nesnesine değil yalnız yerel değerlere başvurur
    val versionText = releaseVersion
    val outDir = layout.buildDirectory.dir("generated/version")
    inputs.property("version", versionText)
    outputs.dir(outDir)
    doLast { outDir.get().file("seyir/version.txt").asFile.apply { parentFile.mkdirs(); writeText(versionText) } }
}
sourceSets.named("main") { resources.srcDir(generateVersion) }

compose.desktop {
    application {
        mainClass = "seyir.app.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Xmx1g")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "Seyir"
            packageVersion = appVersion
            // MSI (WiX) yalnız 1252 kod sayfasını kabul ediyor; açıklamada ş, ı, ğ gibi harfler kullanılamaz
            description = "Film ve dizi izleme"
            vendor = "Seyir"
            // GPL-3.0; kurulum sihirbazında ve pakette gösterilir
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
            // Çalışma zamanı imajı yalnız gereken JDK modüllerini taşır (hepsi ~155 MB, bu liste çok daha küçük).
            // Liste: uygulama sınıf yolu için `./gradlew :app:suggestRuntimeModules` + eklenti jar'ları için
            // jdeps çıktısı (java.scripting, java.sql, java.naming, jdk.dynalink ...) + eklentilerin yansıma/
            // ServiceLoader ile bulduğu, jdeps'in göremediği modüller: TLS için jdk.crypto.ec (JDK 21'de
            // Ed25519 de burada), jdk.charsets, jdk.localedata (Türkçe yerel ayar), jdk.zipfs, jdk.unsupported.
            // Bir eklenti yeni bir modüle ihtiyaç duyarsa -PseyirAllModules=true ile tam imaj derlenip denenebilir.
            if (providers.gradleProperty("seyirAllModules").orNull == "true") {
                includeAllModules = true
            } else {
                modules(
                    "java.desktop", "java.instrument", "java.logging", "java.management", "java.naming",
                    "java.net.http", "java.prefs", "java.scripting", "java.sql", "java.xml",
                    "jdk.accessibility", "jdk.charsets", "jdk.crypto.cryptoki", "jdk.crypto.ec", "jdk.dynalink",
                    "jdk.localedata", "jdk.management", "jdk.naming.dns", "jdk.net", "jdk.unsupported", "jdk.zipfs",
                )
            }

            windows {
                menuGroup = "Seyir"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6c1f3c5e-2d0a-4f4e-9a52-6b1d3f7e8a10"
                iconFile.set(layout.projectDirectory.file("icons/seyir.ico"))
            }
            linux {
                packageName = "seyir"
                appCategory = "AudioVideo"
                menuGroup = "AudioVideo"
                shortcut = true
                debMaintainer = "seyir@localhost"
                iconFile.set(layout.projectDirectory.file("icons/seyir.png"))
            }
        }
    }
}

// Linux paketleri mpv'siz çalışmaz (uygulama sistem mpv'sini kullanır); jpackage paket bağımlılığı olarak bildirilir.
// (Compose DSL'inde bağımlılık alanı yok; jpackage'in --linux-package-deps seçeneği freeArgs ile geçirilir.)
tasks.withType<AbstractJPackageTask>().matching { it.name == "packageDeb" || it.name == "packageRpm" }.configureEach {
    freeArgs.addAll("--linux-package-deps", "mpv")
}

// Compose'un paketleme görevleri gömülü kaynakları (appResourcesRootDir: Windows'ta mpv) değişiklik denetimine
// katmıyor: mpv yenilenince görev "UP-TO-DATE" sayılıp paket eski içerikle kalabiliyor. Klasör girdi yapılır.
tasks.matching { it.name == "createDistributable" || it.name.startsWith("package") }.configureEach {
    inputs.dir(layout.projectDirectory.dir("resources")).withPropertyName("seyirAppResources")
}