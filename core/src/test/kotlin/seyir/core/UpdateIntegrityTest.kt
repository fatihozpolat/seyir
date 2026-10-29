package seyir.core

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateIntegrityTest {
    private val pair = ReleaseSigning.generateKeyPair()
    private val installer = "Seyir-1.1.0.msi"
    private val installerBytes = ByteArray(5000) { (it * 31).toByte() }
    private val installerHash = sha256(installerBytes)
    private val sums = "$installerHash  $installer\n${"0".repeat(64)}  Seyir-1.1.0.exe\n".toByteArray()
    private val signature = ReleaseSigning.sign(sums, pair.private).toByteArray()

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun verify(
        sums: ByteArray? = this.sums,
        signature: ByteArray? = this.signature,
        name: String = installer,
        hash: String = installerHash,
    ) = UpdateIntegrity.verify(sums, signature, name, hash, pair.public)

    @Test
    fun acceptsValidSignatureAndMatchingHash() {
        assertEquals(UpdateVerification.Ok, verify())
        // Büyük harfli özet ve imzadaki satır sonu da kabul edilir
        assertEquals(UpdateVerification.Ok, verify(hash = installerHash.uppercase(), signature = signature + "\n".toByteArray()))
    }

    @Test
    fun failsClosedWhenAssetsAreMissing() {
        assertEquals(UpdateVerification.MissingAsset("SHA256SUMS"), verify(sums = null))
        assertEquals(UpdateVerification.MissingAsset("SHA256SUMS.sig"), verify(signature = null))
    }

    @Test
    fun rejectsTamperedSums() {
        // Saldırgan listedeki özeti kendi dosyasınınkiyle değiştirir; imza artık uymaz
        val evil = sha256("kötü".toByteArray())
        val tampered = "$evil  $installer\n".toByteArray()
        assertEquals(UpdateVerification.BadSignature, verify(sums = tampered, hash = evil))
        // Tek bayt fark bile yeter
        assertEquals(UpdateVerification.BadSignature, verify(sums = sums + "\n".toByteArray()))
    }

    @Test
    fun rejectsSignatureFromAnotherKey() {
        val other = ReleaseSigning.generateKeyPair()
        val forged = ReleaseSigning.sign(sums, other.private).toByteArray()
        assertEquals(UpdateVerification.BadSignature, verify(signature = forged))
    }

    @Test
    fun rejectsMalformedSignatures() {
        assertEquals(UpdateVerification.BadSignature, verify(signature = "bu base64 degil!!".toByteArray()))
        assertEquals(UpdateVerification.BadSignature, verify(signature = ByteArray(0)))
        // Doğru uzunlukta ama yanlış baytlar
        assertEquals(UpdateVerification.BadSignature, verify(signature = java.util.Base64.getEncoder().encode(ByteArray(64))))
        // Ham (Base64'süz) imza biçimi kabul edilmez
        val raw = java.util.Base64.getDecoder().decode(String(signature))
        assertEquals(UpdateVerification.BadSignature, verify(signature = raw))
    }

    @Test
    fun rejectsHashMismatchAndUnlistedInstaller() {
        val altered = sha256(installerBytes + 1.toByte())
        assertEquals(UpdateVerification.HashMismatch(installer), verify(hash = altered))
        assertEquals(UpdateVerification.NotListed("Seyir-9.9.9.msi"), verify(name = "Seyir-9.9.9.msi"))
    }

    @Test
    fun rejectsAmbiguousOrMalformedSums() {
        // İmzalı olsa bile çift satırlı ya da bozuk liste kabul edilmez
        val duplicate = "$installerHash  $installer\n${"1".repeat(64)}  $installer\n".toByteArray()
        val signedDuplicate = ReleaseSigning.sign(duplicate, pair.private).toByteArray()
        assertEquals(UpdateVerification.NotListed(installer), verify(sums = duplicate, signature = signedDuplicate))
        val garbage = "bu bir özet listesi değil\n".toByteArray()
        val signedGarbage = ReleaseSigning.sign(garbage, pair.private).toByteArray()
        assertEquals(UpdateVerification.NotListed(installer), verify(sums = garbage, signature = signedGarbage))
    }

    @Test
    fun parsesSumsFormats() {
        val hash = "a".repeat(64)
        val parsed = UpdateIntegrity.parseSums("$hash  a b.msi\r\n${hash.uppercase()} *c.exe\n\n")!!
        assertEquals(mapOf("a b.msi" to hash, "c.exe" to hash), parsed)
        assertNull(UpdateIntegrity.parseSums("kısa  x.msi\n"))
        assertTrue(UpdateIntegrity.parseSums("")!!.isEmpty())
    }

    @Test
    fun hashesFilesInChunks() {
        val file = Files.createTempFile("seyir-sha", ".bin").toFile()
        try {
            val data = ByteArray(300_000) { (it % 255).toByte() }
            file.writeBytes(data)
            assertEquals(sha256(data), UpdateIntegrity.sha256Hex(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun keyFormatsRoundTrip() {
        val pem = ReleaseSigning.privateKeyToPem(pair.private)
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----\n") && pem.trimEnd().endsWith("-----END PRIVATE KEY-----"))
        val restored = ReleaseSigning.privateKeyFromPem(pem)
        val signature = ReleaseSigning.sign("x".toByteArray(), restored)
        assertTrue(ReleaseSigning.verify("x".toByteArray(), signature, ReleaseSigning.publicKeyFromBase64(ReleaseSigning.publicKeyToBase64(pair.public))))
        assertFalse(ReleaseSigning.verify("y".toByteArray(), signature, pair.public))
        assertFailsWith<IllegalArgumentException> { ReleaseSigning.privateKeyFromPem("yanlış") }
    }

    @Test
    fun embeddedPublicKeyIsValid() {
        // Gömülü anahtar Ed25519 olarak okunabilmeli (yanlış yapıştırma derlemede değil testte yakalanır)
        assertEquals(44, ReleaseSigning.publicKeyFromBase64(UpdateIntegrity.RELEASE_PUBLIC_KEY).encoded.size)
    }
}
