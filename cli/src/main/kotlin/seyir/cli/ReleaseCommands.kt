package seyir.cli

import seyir.core.ReleaseSigning
import seyir.core.UpdateIntegrity
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Sürüm imzalama alt komutları. Ağa ve eklentilere dokunmaz; `scripts/release.ps1` bunları kullanır.
 *
 *   keygen <klasör>                      yeni Ed25519 anahtar çifti üretir, açık anahtarı yazdırır
 *   sign   <dosya> --key <özel.pem>      <dosya>.sig üretir (Base64 Ed25519 imzası)
 *   verify <dosya> [--pub <base64>]      <dosya>.sig dosyasını açık anahtarla doğrular (varsayılan: gömülü anahtar)
 */
internal object ReleaseCommands {
    val NAMES = setOf("keygen", "sign", "verify")

    fun run(command: String, args: List<String>, out: PrintStream) {
        when (command) {
            "keygen" -> keygen(File(args.getOrNull(1) ?: error("Kullanım: keygen <klasör>")), out)
            "sign" -> sign(args, out)
            "verify" -> verify(args, out)
        }
    }

    private fun option(args: List<String>, name: String): String? =
        args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: error("$name için değer gerekli") }

    private fun keygen(dir: File, out: PrintStream) {
        val privateFile = File(dir, "ed25519-private.pem")
        val publicFile = File(dir, "ed25519-public.txt")
        // Var olan anahtarı yanlışlıkla ezip eski sürümleri imzasız bırakmamak için
        check(!privateFile.exists()) { "Özel anahtar zaten var, üzerine yazılmaz: $privateFile" }
        dir.mkdirs()
        val pair = ReleaseSigning.generateKeyPair()
        privateFile.writeText(ReleaseSigning.privateKeyToPem(pair.private))
        // Yalnız sahibi okuyabilsin (POSIX'te); Windows'ta kullanıcı klasörünün izinleri geçerlidir
        runCatching { Files.setPosixFilePermissions(privateFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
        val publicKey = ReleaseSigning.publicKeyToBase64(pair.public)
        publicFile.writeText(publicKey + "\n")
        System.err.println("Özel anahtar yazıldı: $privateFile (depoya ASLA eklemeyin)")
        out.println(publicKey)
    }

    private fun sign(args: List<String>, out: PrintStream) {
        val file = File(args.getOrNull(1) ?: error("Kullanım: sign <dosya> --key <özel.pem>"))
        val keyFile = File(option(args, "--key") ?: error("--key <özel.pem> gerekli"))
        val key = ReleaseSigning.privateKeyFromPem(keyFile.readText())
        val signature = File(file.path + ".sig")
        signature.writeText(ReleaseSigning.sign(file.readBytes(), key) + "\n")
        out.println("İmzalandı: $signature")
    }

    private fun verify(args: List<String>, out: PrintStream) {
        val file = File(args.getOrNull(1) ?: error("Kullanım: verify <dosya> [--pub <base64>]"))
        val signature = File(file.path + ".sig").readText()
        val key = ReleaseSigning.publicKeyFromBase64(option(args, "--pub") ?: UpdateIntegrity.RELEASE_PUBLIC_KEY)
        check(ReleaseSigning.verify(file.readBytes(), signature, key)) { "İmza GEÇERSİZ: $file" }
        out.println("İmza geçerli: $file")
    }
}
