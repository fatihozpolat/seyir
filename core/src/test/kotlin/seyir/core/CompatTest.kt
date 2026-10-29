package seyir.core

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class CompatTest {
    @Test
    fun androidCipherNamesAreAvailable() {
        Compat.install()
        val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        val iv = IvParameterSpec(ByteArray(16))
        val enc = Cipher.getInstance("AES/CBC/PKCS7Padding").apply { init(Cipher.ENCRYPT_MODE, key, iv) }.doFinal("merhaba".toByteArray())
        val dec = Cipher.getInstance("AES/CBC/PKCS7Padding").apply { init(Cipher.DECRYPT_MODE, key, iv) }.doFinal(enc)
        assertEquals("merhaba", String(dec))
    }

    @Test
    fun letsEncryptGenerationYRootsAreTrusted() {
        Compat.install()
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        val issuers = factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }.map { it.subjectX500Principal.name }
        assert(issuers.any { "CN=Root YE" in it }) { "Root YE güven listesinde yok" }
        assert(issuers.any { "CN=ISRG Root X1" in it }) { "JDK kökleri kaybolmuş" }
    }
}
