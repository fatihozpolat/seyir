package seyir.core

import com.googlecode.d2j.dex.Dex2jar
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * CloudStream'in Android eklentisini (`.cs3`: içinde `classes.dex` ve `manifest.json` bulunan zip)
 * Seyir'in yükleyebileceği bir jar'a çevirir: dex bayt kodu dex2jar ile JVM sınıflarına dönüştürülür,
 * manifest olduğu gibi kopyalanır. Android'e özgü kaynaklar (`resources.arsc`, `res/`) taşınmaz.
 */
object Cs3Converter {
    fun convert(cs3: File, target: File) {
        ZipFile(cs3).use { zip ->
            val dex = zip.getEntry("classes.dex") ?: throw IllegalArgumentException("${cs3.name} içinde classes.dex yok")
            val manifest = zip.getEntry("manifest.json") ?: throw IllegalArgumentException("${cs3.name} içinde manifest.json yok")
            val classes = Files.createTempFile("seyir-d2j-", ".jar")
            try {
                Files.delete(classes)
                Dex2jar.from(zip.getInputStream(dex).readBytes())
                    .skipDebug(false)
                    .topoLogicalSort()
                    .computeFrames(true)
                    .to(classes)
                ZipOutputStream(target.outputStream()).use { out ->
                    out.putNextEntry(ZipEntry("manifest.json"))
                    zip.getInputStream(manifest).use { it.copyTo(out) }
                    out.closeEntry()
                    ZipFile(classes.toFile()).use { converted ->
                        for (entry in converted.entries()) {
                            if (entry.isDirectory) continue
                            out.putNextEntry(ZipEntry(entry.name))
                            converted.getInputStream(entry).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(classes)
            }
        }
    }
}
