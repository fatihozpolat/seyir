package seyir.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Sürüm imzalama için Ed25519 yardımcıları (yalnız JDK'nın kendi sağlayıcısı; ek araç gerekmez).
 *
 * Biçimler:
 * - Özel anahtar: PKCS#8, PEM (`-----BEGIN PRIVATE KEY-----`); depoya asla girmez.
 * - Açık anahtar: X.509 SubjectPublicKeyInfo, tek satır Base64 (uygulamaya gömülen biçim).
 * - İmza: Ed25519, 64 baytlık ham imzanın tek satır Base64'ü (`SHA256SUMS.sig`).
 */
object ReleaseSigning {
    private const val ALGORITHM = "Ed25519"
    private const val PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PRIVATE_END = "-----END PRIVATE KEY-----"
    private const val SIGNATURE_BYTES = 64

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()

    fun privateKeyToPem(key: PrivateKey): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        return "$PRIVATE_BEGIN\n$body\n$PRIVATE_END\n"
    }

    /** PKCS#8 PEM metninden özel anahtarı okur; biçim bozuksa [IllegalArgumentException] fırlatır. */
    fun privateKeyFromPem(pem: String): PrivateKey {
        val body = pem.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-----") }.joinToString("")
        require(PRIVATE_BEGIN in pem && body.isNotEmpty()) { "Özel anahtar PKCS#8 PEM biçiminde değil" }
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    fun publicKeyToBase64(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

    fun publicKeyFromBase64(text: String): PublicKey =
        KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(text.trim())))

    /** [data] baytlarını imzalar ve imzanın Base64 metnini (sonunda satır sonu olmadan) döndürür. */
    fun sign(data: ByteArray, key: PrivateKey): String {
        val signature = Signature.getInstance(ALGORITHM).apply {
            initSign(key)
            update(data)
        }.sign()
        return Base64.getEncoder().encodeToString(signature)
    }

    /**
     * [signatureText] (Base64) imzasının [data] üzerinde [key] ile geçerli olup olmadığına bakar.
     * Bozuk Base64, yanlış uzunluk veya geçersiz imza hep `false` döndürür; hata fırlatmaz.
     */
    fun verify(data: ByteArray, signatureText: String, key: PublicKey): Boolean = try {
        val raw = Base64.getMimeDecoder().decode(signatureText.trim())
        raw.size == SIGNATURE_BYTES && Signature.getInstance(ALGORITHM).run {
            initVerify(key)
            update(data)
            verify(raw)
        }
    } catch (e: Exception) {
        false
    }
}
