package seyir.core.relay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Hatalı veya aşırı büyük istek; bağlantıya 400 yazılıp kapatılır. */
internal class BadRequestException(message: String) : IOException(message)

/** Ayrıştırılmış HTTP isteği (gövdesiz; gövde gerekirse ayrıca okunur). */
internal class HttpRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: List<Pair<String, String>>,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val isHttp11: Boolean get() = version.equals("HTTP/1.1", ignoreCase = true)

    /** İstemci bağlantıyı açık tutmak istiyor mu? */
    val keepAlive: Boolean
        get() {
            val connection = header("Connection")?.lowercase().orEmpty()
            return if (isHttp11) "close" !in connection else "keep-alive" in connection
        }

    val contentLength: Long get() = header("Content-Length")?.trim()?.toLongOrNull() ?: 0L
}

internal object HttpWire {
    private const val MAX_LINE = 16 * 1024
    private const val MAX_HEADERS = 200

    /** Akış sonunda (yeni istek gelmeden kapanma) null döner. */
    fun readRequest(input: InputStream): HttpRequest? {
        var line = readLine(input) ?: return null
        // İstekler arasındaki fazladan boş satırları atla
        while (line.isEmpty()) line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3) throw BadRequestException("Geçersiz istek satırı")
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val header = readLine(input) ?: throw BadRequestException("Başlıklar yarım kaldı")
            if (header.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw BadRequestException("Çok fazla başlık")
            val colon = header.indexOf(':')
            if (colon <= 0) throw BadRequestException("Geçersiz başlık")
            headers += header.substring(0, colon).trim() to header.substring(colon + 1).trim()
        }
        return HttpRequest(parts[0].uppercase(), parts[1], parts[2], headers)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else throw BadRequestException("Satır yarım kaldı")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > MAX_LINE) throw BadRequestException("Satır çok uzun")
        }
        return buffer.toString(Charsets.ISO_8859_1)
    }

    fun writeHead(out: OutputStream, status: Int, reason: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        for ((name, value) in headers) sb.append(name).append(": ").append(value).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * [source] içeriğini [out]'a kopyalar. [length] >= 0 ise tam o kadar bayt (eksik kalırsa hata),
     * değilse akış bitene kadar; [chunked] ise parça (chunk) kodlamasıyla yazar.
     */
    fun copyBody(source: InputStream, out: OutputStream, length: Long, chunked: Boolean) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (true) {
            val want = if (length >= 0) minOf(buffer.size.toLong(), remaining).toInt() else buffer.size
            if (want == 0) break
            val n = source.read(buffer, 0, want)
            if (n < 0) {
                if (length >= 0) throw IOException("Kaynak yanıtı beklenenden erken bitti")
                break
            }
            if (chunked) {
                out.write((Integer.toHexString(n) + "\r\n").toByteArray(Charsets.ISO_8859_1))
                out.write(buffer, 0, n)
                out.write(CRLF)
            } else {
                out.write(buffer, 0, n)
            }
            // Canlı akışta gecikme olmaması için her okumadan sonra gönder
            out.flush()
            if (length >= 0) remaining -= n
        }
        if (chunked) out.write(LAST_CHUNK)
        out.flush()
    }

    private val CRLF = "\r\n".toByteArray()
    private val LAST_CHUNK = "0\r\n\r\n".toByteArray()

    fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status"
    }
}
