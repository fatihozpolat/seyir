package seyir.core

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Günlük dosyasına yazan, boyutu [maxBytes]'ı aşınca dosyayı `<ad>.1.<uzantı>` olarak kenara alıp
 * yenisini açan akış (yalnız bir eski dosya tutulur). `seyir.log` çalışırken sınırsız büyümesin diye.
 */
class RotatingLogStream(private val file: File, private val maxBytes: Long = 5L * 1024 * 1024) : OutputStream() {
    private var out: FileOutputStream
    private var written: Long

    init {
        file.absoluteFile.parentFile?.mkdirs()
        // Önceki oturumdan büyük kalmışsa açılışta döndürülür
        if (file.length() > maxBytes) rotateFile()
        out = FileOutputStream(file, true)
        written = file.length()
    }

    /** Dönen eski günlüğün adı: seyir.log → seyir.1.log */
    val rotatedFile: File
        get() {
            val name = file.name
            val dot = name.lastIndexOf('.')
            val rotated = if (dot > 0) name.substring(0, dot) + ".1" + name.substring(dot) else "$name.1"
            return File(file.absoluteFile.parentFile, rotated)
        }

    @Synchronized
    override fun write(b: Int) {
        rotateIfNeeded(1)
        out.write(b)
        written += 1
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        rotateIfNeeded(len)
        out.write(b, off, len)
        written += len
    }

    @Synchronized
    override fun flush() = out.flush()

    @Synchronized
    override fun close() = out.close()

    private fun rotateIfNeeded(incoming: Int) {
        if (written == 0L || written + incoming <= maxBytes) return
        out.close()
        rotateFile()
        out = FileOutputStream(file, false)
        written = 0
    }

    private fun rotateFile() {
        runCatching { Files.move(file.toPath(), rotatedFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            // Taşınamazsa (başka işlem tutuyor olabilir) sınırsız büyümeyi önlemek için sıfırlanır
            .onFailure { runCatching { file.delete() } }
    }
}
