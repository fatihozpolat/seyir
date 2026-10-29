rootProject.name = "seyir-plugins"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Gerekirse JDK 21'i otomatik indirir
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Kök klasördeki her alt klasör (build.gradle.kts içeren) bir eklentidir. Klasördeki build.gradle.kts
// CloudStream biçimindedir ve ÇALIŞTIRILMAZ: Android eklentisi istediği için burada derlenemez;
// meta verileri kök build.gradle.kts okur, derleme ayarları da oradan gelir.
// excludedPlugins (gradle.properties): derlenmeyen eklentiler, virgülle ayrılmış klasör adları.
val excluded = setOf("gradle", "build", "libs", "docs", ".github", ".gradle") +
    providers.gradleProperty("excludedPlugins").getOrElse("").split(',').map { it.trim() }.filter { it.isNotEmpty() }

rootDir.listFiles()
    ?.filter { it.isDirectory && it.name !in excluded && File(it, "build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { dir ->
        include(":${dir.name}")
        project(":${dir.name}").projectDir = dir
        project(":${dir.name}").buildFileName = "seyir-does-not-exist.gradle.kts"
    }
