package seyir.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Dosyayı yarım bırakmadan değiştirir: içerik önce aynı klasörde benzersiz bir geçici dosyaya
 * yazılır, sonra hedefin üzerine taşınır. Hedefi silip yeniden adlandırmak Windows'ta iki adım
 * arasında çökme olursa dosyayı kaybettirirdi; `ATOMIC_MOVE` hedefi tek adımda değiştirir.
 */
object AtomicFile {
    private val locks = ConcurrentHashMap<String, Any>()

    /** Aynı dosyaya yazan iş parçacıkları sıraya girsin diye dosya başına kilit. */
    fun lockFor(file: File): Any = locks.computeIfAbsent(file.absoluteFile.normalize().path) { Any() }

    /**
     * [target] dosyasını [write]'ın geçici dosyaya yazdığı içerikle değiştirir. Hata olursa
     * hedef olduğu gibi kalır ve geçici dosya silinir; hata çağırana fırlatılır.
     */
    fun replace(target: File, write: (File) -> Unit) {
        synchronized(lockFor(target)) {
            val dir = target.absoluteFile.parentFile
            dir.mkdirs()
            val tmp = File.createTempFile(target.name + ".", ".tmp", dir)
            try {
                write(tmp)
                move(tmp, target)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun move(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Dosya sistemi atomik taşımayı desteklemiyorsa ya da hedef geçici olarak kilitliyse
            // (Windows'ta tarama yapan antivirüs) atomik olmayan taşımaya düşülür
            if (e !is AtomicMoveNotSupportedException) SeyirLog.d("AtomicFile", "${target.name} atomik taşınamadı: ${e.message}")
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
