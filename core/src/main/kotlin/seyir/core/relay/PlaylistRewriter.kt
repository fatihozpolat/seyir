package seyir.core.relay

import okhttp3.HttpUrl

/** Yeniden yazılan bir bağlantının oynatıcıdaki rolü; yerel URL'nin uzantısını belirler. */
internal enum class LinkRole { PLAYLIST, SEGMENT, KEY, INIT }

/**
 * HLS (`#EXTM3U`) oynatma listelerindeki bütün bağlantıları upstream adresine göre mutlak çözüp
 * [mapper] ile yerel aktarıcı adresine çevirir. Çözülemeyen veya http(s) olmayan bağlantılara
 * (`skd://`, `data:` vb.) dokunulmaz.
 *
 * @param mapper (mutlak upstream URL, rol, uzantı) -> yerel URL
 */
internal class PlaylistRewriter(private val mapper: (HttpUrl, LinkRole, String) -> String) {

    fun rewrite(text: String, base: HttpUrl): String {
        val body = text.removePrefix("﻿")
        // MAP varsa segmentler büyük olasılıkla fMP4'tür
        val hasInit = body.contains("#EXT-X-MAP")
        var variantNext = false
        return body.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) variantNext = true
                    rewriteTag(line, base, hasInit)
                }
                else -> {
                    val role = if (variantNext) LinkRole.PLAYLIST else LinkRole.SEGMENT
                    variantNext = false
                    map(line.trim(), base, role, hasInit) ?: line
                }
            }
        }
    }

    private fun rewriteTag(line: String, base: HttpUrl, hasInit: Boolean): String {
        val colon = line.indexOf(':')
        if (colon < 0) return line
        val role = when (line.substring(1, colon)) {
            "EXT-X-KEY", "EXT-X-SESSION-KEY" -> LinkRole.KEY
            "EXT-X-MAP" -> LinkRole.INIT
            "EXT-X-MEDIA", "EXT-X-I-FRAME-STREAM-INF", "EXT-X-RENDITION-REPORT" -> LinkRole.PLAYLIST
            "EXT-X-PART", "EXT-X-PRELOAD-HINT" -> LinkRole.SEGMENT
            else -> return line
        }
        return URI_ATTRIBUTE.replace(line) { match ->
            val mapped = map(match.groupValues[1], base, role, hasInit)
            if (mapped == null) match.value else "URI=\"$mapped\""
        }
    }

    private fun map(reference: String, base: HttpUrl, role: LinkRole, hasInit: Boolean): String? {
        if (reference.isEmpty()) return null
        val absolute = base.resolve(reference) ?: return null
        val extension = when (role) {
            LinkRole.PLAYLIST -> "m3u8"
            LinkRole.KEY -> "key"
            LinkRole.INIT -> "mp4"
            LinkRole.SEGMENT -> segmentExtension(absolute, hasInit)
        }
        return mapper(absolute, role, extension)
    }

    private fun segmentExtension(url: HttpUrl, hasInit: Boolean): String {
        val ext = urlExtension(url)
        return when {
            ext in KEPT_SEGMENT_EXTENSIONS -> ext
            hasInit -> "m4s"
            else -> "ts"
        }
    }

    companion object {
        private val URI_ATTRIBUTE = Regex("""(?<![A-Za-z0-9-])URI="([^"]*)"""")

        /** Uzantısı ffmpeg'in tanıdığı ve içeriğiyle uyuşan segmentler olduğu gibi bırakılır. */
        private val KEPT_SEGMENT_EXTENSIONS =
            setOf("ts", "m4s", "mp4", "m4a", "m4v", "aac", "ac3", "eac3", "mp3", "vtt", "webvtt", "cmfv", "cmfa")

        fun urlExtension(url: HttpUrl): String =
            url.encodedPath.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    }
}
