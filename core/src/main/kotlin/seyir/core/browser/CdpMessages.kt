package seyir.core.browser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** CDP'den gelen bir mesaj: komut yanıtı ya da olay. */
sealed interface CdpMessage {
    /** [id] ile gönderilen komutun yanıtı; [error] doluysa komut başarısızdır. */
    data class Response(val id: Int, val result: JsonNode?, val error: CdpError?, val sessionId: String?) : CdpMessage

    /** Tarayıcının kendiliğinden gönderdiği olay (ör. `Target.targetCreated`). */
    data class Event(val method: String, val params: JsonNode?, val sessionId: String?) : CdpMessage
}

/** CDP hata nesnesi (`{"code":-32000,"message":"..."}`). */
data class CdpError(val code: Int, val message: String)

/** `DevToolsActivePort` dosyasının içeriği: ilk satır port, ikinci satır tarayıcı WebSocket yolu. */
data class DevToolsActivePort(val port: Int, val browserPath: String?)

/** CDP mesajlarını oluşturan ve ayrıştıran saf (ağ kullanmayan) yardımcılar. */
object CdpMessages {
    private val mapper = jacksonObjectMapper()

    /** Bir komut isteğini JSON metni olarak üretir. [sessionId] verilirse komut o sayfa oturumuna gider. */
    fun request(id: Int, method: String, params: Map<String, Any?>? = null, sessionId: String? = null): String {
        val node = mapper.createObjectNode()
        node.put("id", id)
        node.put("method", method)
        if (params != null) node.set<JsonNode>("params", mapper.valueToTree(params))
        if (sessionId != null) node.put("sessionId", sessionId)
        return mapper.writeValueAsString(node)
    }

    /** Gelen metni ayrıştırır; geçersiz JSON ya da tanınmayan biçimde null döner. */
    fun parse(text: String): CdpMessage? {
        val node = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        if (!node.isObject) return null
        val sessionId = node.get("sessionId")?.takeIf { it.isTextual }?.asText()
        val id = node.get("id")
        if (id != null && id.isInt) {
            val error = node.get("error")?.takeIf { it.isObject }?.let {
                CdpError(it.get("code")?.asInt() ?: 0, it.get("message")?.asText() ?: "")
            }
            return CdpMessage.Response(id.asInt(), node.get("result"), error, sessionId)
        }
        val method = node.get("method")?.takeIf { it.isTextual }?.asText() ?: return null
        return CdpMessage.Event(method, node.get("params"), sessionId)
    }

    /**
     * `Network.getCookies` sonucundaki (`{"cookies":[...]}`) çerezleri ad → değer haritasına çevirir.
     * Aynı adlı çerezlerde yolu en uzun olan (en özgül) kazanır.
     */
    fun cookiesToMap(result: JsonNode?): Map<String, String> {
        val list = result?.get("cookies")?.takeIf { it.isArray } ?: return emptyMap()
        return list
            .filter { it.get("name")?.isTextual == true && it.get("value")?.isTextual == true }
            .sortedBy { it.get("path")?.asText()?.length ?: 0 }
            .associate { it.get("name").asText() to it.get("value").asText() }
    }

    /** `DevToolsActivePort` dosyasını ayrıştırır; port henüz yazılmadıysa null. */
    fun parseActivePort(content: String): DevToolsActivePort? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val port = lines.firstOrNull()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return DevToolsActivePort(port, lines.getOrNull(1))
    }

    private val challengeTitleMarkers = listOf(
        "just a moment", "attention required", "checking your browser", "bir dakika", "un instant",
        "einen moment", "un momento", "um momento", "один момент", "access denied",
    )

    /** Sayfa başlığı Cloudflare sınama/engel sayfasına benziyorsa true; boş başlık da "henüz hazır değil" sayılır. */
    fun isChallengeTitle(title: String?): Boolean {
        val t = title?.trim()?.lowercase() ?: return true
        if (t.isEmpty()) return true
        return challengeTitleMarkers.any { t.contains(it) }
    }

    /** Tarayıcının bildirdiği user-agent içindeki `HeadlessChrome` ibaresini `Chrome` yapar. */
    fun fixUserAgent(userAgent: String?): String? = userAgent?.replace("HeadlessChrome", "Chrome")
}
