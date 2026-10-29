package seyir.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory

/** Android ortamında hazır gelen ama masaüstü JDK'da olmayan parçaları kurar. */
object Compat {
    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        // Ara sertifikasını göndermeyen sunucular için eksik sertifikayı AIA adresinden indir
        // (tarayıcılar ve Android bunu tolere ediyor; JDK varsayılan olarak PKIX hatası veriyor).
        // TLS ilk kez kullanılmadan önce ayarlanmalı.
        System.setProperty("com.sun.security.enableAIAcaIssuers", "true")
        installExtraRoots()
        // Android'in şifre sağlayıcısı BouncyCastle tabanlıdır; eklentiler "PKCS7Padding" gibi
        // yalnız orada bulunan adları kullanır. JDK sağlayıcılarından sonra eklenir ki JDK'nın
        // desteklediği adlarda davranış değişmesin.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** JDK'nın güven listesinde henüz olmayan, pakete gömülü kök sertifikalar. */
    private val EXTRA_ROOTS = listOf("isrg-root-ye.pem", "isrg-root-yr.pem")

    /**
     * Let's Encrypt'in 2025'te devreye aldığı yeni kökler (ISRG Root YE/YR) JDK 21'de yok; bu
     * köklerle imzalı sitelerde (ör. vidmoxy.com) PKIX hatası çıkıyor. Windows da bu kökleri
     * ancak bir tarayıcı isteyince indirdiği için sistem deposuna güvenilemez. JDK listesi ile
     * gömülü kökler birleştirilip dosyaya yazılır ve varsayılan güven deposu yapılır; OkHttp,
     * HttpURLConnection ve eklentilerin kendi istemcileri hep bunu kullanır.
     * Kullanıcı kendi deposunu (`javax.net.ssl.trustStore`) verdiyse dokunulmaz.
     */
    private fun installExtraRoots() {
        if (System.getProperty("javax.net.ssl.trustStore") != null) return
        runCatching {
            val cacerts = File(System.getProperty("java.home"), "lib/security/cacerts")
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            cacerts.inputStream().use { store.load(it, null) }
            val factory = CertificateFactory.getInstance("X.509")
            EXTRA_ROOTS.forEach { name ->
                val stream = Compat::class.java.getResourceAsStream("/seyir/certs/$name") ?: return@forEach
                stream.use { store.setCertificateEntry("seyir-$name", factory.generateCertificate(it)) }
            }
            val password = "seyir".toCharArray()
            val target = File(AppDirs.data, "cache/truststore.p12").apply { parentFile.mkdirs() }
            val pkcs12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            store.aliases().asSequence().forEach { alias ->
                store.getCertificate(alias)?.let { pkcs12.setCertificateEntry(alias, it) }
            }
            // Aynı anda açılan başka bir süreç (ikinci pencere, CLI) yarım dosyayı okumasın:
            // önce geçici dosyaya yazılır, sonra tek hamlede yerine taşınır
            val temp = File.createTempFile("truststore", ".tmp", target.parentFile)
            try {
                temp.outputStream().use { pkcs12.store(it, password) }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.io.IOException) {
                // Windows'ta başka süreç dosyayı okurken değiştirilemez; o dosya zaten eksiksizdir
                if (!target.exists()) throw e
            } finally {
                temp.delete()
            }
            System.setProperty("javax.net.ssl.trustStore", target.absolutePath)
            System.setProperty("javax.net.ssl.trustStorePassword", String(password))
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
        }.onFailure { SeyirLog.w("Compat", "Ek kök sertifikalar kurulamadı", it) }
    }
}
