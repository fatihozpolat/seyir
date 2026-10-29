plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Eklentilerin derlenirken ve çalışırken gördüğü bütün API'ler buradan gelir
    api(libs.cloudstream.library)
    api(libs.nicehttp)
    api(libs.jsoup)
    api(libs.jackson.kotlin)
    api(libs.coroutines.core)
    // jsoup 1.22 tip ek açıklamaları için; olmazsa bazı eklentiler derlenmez
    api(libs.jspecify)
    // Android'deki şifre adları (ör. AES/CBC/PKCS7Padding) masaüstü JDK'da yok; BouncyCastle sağlar
    api(libs.bouncycastle)
    // CloudStream kütüphanesinin YouTube çıkarıcısının kullandığı sürümle aynı; indiriciyi vermek için
    implementation(libs.newpipe)
    // CloudStream'in Android eklentilerini (.cs3, içinde classes.dex) JVM sınıflarına çevirmek için
    implementation(libs.dex2jar.translator)
    // Windows'ta mpv penceresinin konumunu okumak için (Win32WindowProbe)
    implementation(libs.jna)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    // Ağ, pencere veya tarayıcı açan testler yalnız elle çalıştırılır: -PmanualTests
    useJUnitPlatform { if (!project.hasProperty("manualTests")) excludeTags("manual") }
}
