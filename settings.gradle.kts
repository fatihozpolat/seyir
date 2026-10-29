rootProject.name = "seyir"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Daemon JVM'i (JDK 21) gerekirse otomatik bulup indirir
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // JitPack yalnız GitHub'dan derlenen kütüphaneler (com.github.*) için sorulur; başka grupların
        // adı JitPack'e gitmez (hem hız hem tedarik zinciri: yanlışlıkla oradan paket çözülmez)
        maven("https://jitpack.io") {
            content { includeGroupByRegex("""com\.github\..*""") }
        }
    }
}

include(":core", ":cli", ":app")
