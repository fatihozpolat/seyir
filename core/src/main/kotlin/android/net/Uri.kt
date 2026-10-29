package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Android `Uri` sınıfının eklentilerin kullandığı kısmı: adres ayrıştırma ve parçalarını okuma.
 * Ayrıştırma `java.net.URI` ile yapılır; geçersiz adreste parçalar null döner (Android'deki gibi istisna fırlamaz).
 */
open class Uri private constructor(private val raw: String) {
    private val parsed: URI? = runCatching { URI(raw) }.getOrNull()

    open fun getScheme(): String? = parsed?.scheme
    open fun getHost(): String? = parsed?.host
    open fun getPort(): Int = parsed?.port ?: -1
    open fun getAuthority(): String? = parsed?.authority
    open fun getPath(): String? = parsed?.path
    open fun getEncodedPath(): String? = parsed?.rawPath
    open fun getQuery(): String? = parsed?.query
    open fun getEncodedQuery(): String? = parsed?.rawQuery
    open fun getFragment(): String? = parsed?.fragment

    open fun getPathSegments(): List<String> = getPath()?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()
    open fun getLastPathSegment(): String? = getPathSegments().lastOrNull()

    open fun getQueryParameterNames(): Set<String> = queryPairs().map { it.first }.toCollection(LinkedHashSet())
    open fun getQueryParameter(key: String): String? = queryPairs().firstOrNull { it.first == key }?.second
    open fun getQueryParameters(key: String): List<String> = queryPairs().filter { it.first == key }.map { it.second }

    private fun queryPairs(): List<Pair<String, String>> =
        getEncodedQuery()?.split('&')?.filter { it.isNotEmpty() }?.map { part ->
            val name = part.substringBefore('=')
            val value = if ('=' in part) part.substringAfter('=') else ""
            decode(name)!! to decode(value)!!
        } ?: emptyList()

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()

    companion object {
        @JvmField
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic
        fun encode(s: String?): String? = s?.let { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

        @JvmStatic
        fun decode(s: String?): String? = s?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
    }
}
