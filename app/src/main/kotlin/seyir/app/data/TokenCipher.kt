package seyir.app.data

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import seyir.core.AppDirs
import seyir.core.SeyirLog
import java.util.Base64

/**
 * Ayarlarda saklanan erişim anahtarını diskte korur. Korunmuş değer ayar dosyasına yazılır, bellekte
 * düz metin kalır. Anahtarı koruyamayan platformda ([platform] null döner) değer düz metin saklanır.
 */
interface TokenCipher {
    /** Düz metni korunmuş metne çevirir; yapamazsa null. */
    fun protect(plain: String): String?

    /** [protect]'in çıktısını açar; açamazsa (başka kullanıcı, bozuk veri) null. */
    fun unprotect(stored: String): String?

    companion object {
        /** Bu platformun şifreleyicisi: Windows'ta DPAPI, öbür sistemlerde yok. */
        fun platform(): TokenCipher? = if (AppDirs.isWindows) WindowsDpapi else null
    }
}

/**
 * Windows DPAPI (`CryptProtectData`): veri yalnız bu Windows kullanıcısının oturumunda açılabilir.
 * Seyir'e özgü bir "entropy" eklenir; başka bir uygulama aynı kullanıcıyla da olsa kolayca açamaz.
 */
object WindowsDpapi : TokenCipher {
    private const val PREFIX = "dpapi:"
    private const val CRYPTPROTECT_UI_FORBIDDEN = 1
    private val ENTROPY = "Seyir.githubToken.v1".toByteArray(Charsets.UTF_8)

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob() : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
    }

    private interface Crypt32 : Library {
        fun CryptProtectData(
            dataIn: DataBlob, description: WString?, entropy: DataBlob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, dataOut: DataBlob,
        ): Boolean

        fun CryptUnprotectData(
            dataIn: DataBlob, description: Pointer?, entropy: DataBlob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, dataOut: DataBlob,
        ): Boolean
    }

    private interface Kernel32 : Library {
        fun LocalFree(memory: Pointer?): Pointer?
    }

    private val crypt32 by lazy { Native.load("Crypt32", Crypt32::class.java) }
    private val kernel32 by lazy { Native.load("Kernel32", Kernel32::class.java) }

    private fun blobOf(bytes: ByteArray): DataBlob = DataBlob().apply {
        val memory = Memory(bytes.size.toLong().coerceAtLeast(1)).also { it.write(0, bytes, 0, bytes.size) }
        cbData = bytes.size
        pbData = memory
    }

    /** Sistemin ayırdığı çıktı belleğini okuyup serbest bırakır. */
    private fun takeOutput(blob: DataBlob): ByteArray {
        val bytes = blob.pbData!!.getByteArray(0, blob.cbData)
        kernel32.LocalFree(blob.pbData)
        return bytes
    }

    override fun protect(plain: String): String? = runCatching {
        val input = blobOf(plain.toByteArray(Charsets.UTF_8))
        val entropy = blobOf(ENTROPY)
        val output = DataBlob()
        check(crypt32.CryptProtectData(input, null, entropy, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)) { "CryptProtectData başarısız" }
        PREFIX + Base64.getEncoder().encodeToString(takeOutput(output))
    }.onFailure { SeyirLog.w("TokenCipher", "Erişim anahtarı DPAPI ile korunamadı", it) }.getOrNull()

    override fun unprotect(stored: String): String? = runCatching {
        require(stored.startsWith(PREFIX)) { "Bilinmeyen biçim" }
        val input = blobOf(Base64.getDecoder().decode(stored.removePrefix(PREFIX)))
        val entropy = blobOf(ENTROPY)
        val output = DataBlob()
        check(crypt32.CryptUnprotectData(input, null, entropy, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)) { "CryptUnprotectData başarısız" }
        String(takeOutput(output), Charsets.UTF_8)
    }.onFailure { SeyirLog.w("TokenCipher", "Korunmuş erişim anahtarı açılamadı", it) }.getOrNull()
}
