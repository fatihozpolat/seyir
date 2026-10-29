package android.util

/**
 * android.util.Base64 taklidi. Android'in davranışı birebir korunur:
 * - DEFAULT kodlamada 76 karakterde bir satır sonu ve sonda satır sonu eklenir,
 * - çözerken boşluk/satır sonu atlanır, eksik '=' dolgusu kabul edilir.
 */
@Suppress("unused")
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    private const val LINE_LENGTH = 76

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray = decode(str.toByteArray(Charsets.US_ASCII), flags)

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray = decode(input, 0, input.size, flags)

    @JvmStatic
    fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        // Android çözücüsü iki alfabeyi de tanır; '-' ve '_' karakterlerini standarda çevir
        val cleaned = StringBuilder(len)
        for (i in offset until offset + len) {
            when (val c = input[i].toInt().toChar()) {
                ' ', '\n', '\r', '\t' -> Unit
                '-' -> cleaned.append('+')
                '_' -> cleaned.append('/')
                else -> cleaned.append(c)
            }
        }
        var body = cleaned.toString().trimEnd('=')
        if (body.length % 4 == 1) throw IllegalArgumentException("bad base-64")
        body += "=".repeat((4 - body.length % 4) % 4)
        return java.util.Base64.getDecoder().decode(body)
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray = encode(input, 0, input.size, flags)

    @JvmStatic
    fun encode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray =
        encodeToString(input, offset, len, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String = encodeToString(input, 0, input.size, flags)

    @JvmStatic
    fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val raw = encoder.encodeToString(input.copyOfRange(offset, offset + len))
        if (flags and NO_WRAP != 0 || raw.isEmpty()) return raw

        val newline = if (flags and CRLF != 0) "\r\n" else "\n"
        return raw.chunked(LINE_LENGTH).joinToString(separator = newline, postfix = newline)
    }
}
