package seyir.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.app
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Sağlayıcıların alan adı değişikliklerini izler. Bu siteler sık sık yeni alan adına taşınıp
 * eskisinden yönlendirme bırakır; eklentideki `mainUrl` eskide kalınca POST istekleri ve
 * göreli adresler bozulur. Burada yönlendirme takip edilip `mainUrl` çalışırken güncellenir,
 * bulunan adres diske yazılır (CloudStream deposundaki KONTROL.py'nin canlı karşılığı).
 */
class DomainTracker(
    private val store: File = File(AppDirs.data, "domains.json"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Bulunan adres ve bulunduğu sırada eklentinin kendi `mainUrl`'si. */
    data class SavedDomain(val url: String, val pluginUrl: String)

    private val mapper = jacksonObjectMapper()
    private val known: MutableMap<String, SavedDomain> = ConcurrentHashMap()

    /** Sağlayıcının adresinin en son başarıyla denetlendiği an (ms); açılışta her siteyi yeniden taramamak için. */
    private val checkedAt: MutableMap<String, Long> = ConcurrentHashMap()
    private val originals = ConcurrentHashMap<String, String>()

    init {
        load()
    }

    /**
     * Kayıtlı adresi uygular; ağ isteği yapmaz. Eklenti yüklendikten hemen sonra çağrılır.
     * Eklenti güncellenip kendi adresi değiştiyse kayıt eskimiş sayılır ve silinir; yoksa eski
     * kayıt yeni sürümün doğru adresini ezerdi.
     */
    fun applySaved(api: MainAPI) {
        originals.putIfAbsent(api.name, api.mainUrl)
        val saved = known[api.name] ?: return
        if (saved.pluginUrl.trimEnd('/') == originals.getValue(api.name).trimEnd('/')) {
            api.mainUrl = saved.url
        } else {
            known.remove(api.name)
            checkedAt.remove(api.name)
            save()
        }
    }

    /**
     * Sitenin şu anki adresini yönlendirmeyi izleyerek bulur; değiştiyse günceller ve döndürür.
     * Son [RECHECK_INTERVAL_MS] içinde denetlenmiş site, [force] verilmedikçe atlanır.
     */
    suspend fun refresh(api: MainAPI, force: Boolean = false): String? {
        originals.putIfAbsent(api.name, api.mainUrl)
        val current = api.mainUrl
        if (!current.startsWith("http")) return null
        if (!force && now() - (checkedAt[api.name] ?: 0L) < RECHECK_INTERVAL_MS) return null

        // Yanıt gövdesi kapatılmazsa bağlantı havuza dönmez ("connection leaked"); yalnız son adres gerekir.
        // Başlık, Cloudflare yakalayıcısının bu istek için tarayıcı sınama çözücüsünü açmasını engeller.
        val finalUrl = runCatching {
            val response = app.get(current, headers = mapOf(NO_SOLVE_HEADER to "1"), timeout = 15)
            response.okhttpResponse.use { if (it.isSuccessful) response.url else null }
        }
            .onFailure { SeyirLog.d(TAG, "${api.name}: adres denetlenemedi: ${it.message}") }
            .getOrNull() ?: return null
        checkedAt[api.name] = now()
        return resolveMove(api, current, finalUrl).also { save() }
    }

    /** Yönlendirmenin vardığı adres gerçek bir taşınmaysa [api]'yi günceller ve yeni kökü döndürür. */
    private fun resolveMove(api: MainAPI, current: String, finalUrl: String): String? {
        val newOrigin = origin(finalUrl) ?: return null
        if (newOrigin == origin(current)) return null
        if (!looksLikeSameSite(URI(current).host ?: "", URI(newOrigin).host ?: "")) {
            SeyirLog.w(TAG, "${api.name}: $current -> $newOrigin yönlendirmesi reddedildi (park/başka site)")
            return null
        }

        SeyirLog.i(TAG, "${api.name}: $current -> $newOrigin")
        api.mainUrl = newOrigin
        known[api.name] = SavedDomain(newOrigin, originals.getValue(api.name))
        return newOrigin
    }

    /**
     * Ana sayfa istekleri eklenti oluşturulurken eski adresle hazırlanır; adres değiştiyse
     * istek verisindeki eski kökü yenisiyle değiştirir.
     */
    fun rewrite(api: MainAPI, request: MainPageRequest): MainPageRequest {
        val original = originals[api.name]?.trimEnd('/') ?: return request
        val current = api.mainUrl.trimEnd('/')
        if (original == current || !request.data.startsWith(original)) return request
        return request.copy(data = current + request.data.removePrefix(original))
    }

    /**
     * Süresi dolan alan adları park sayfalarına (ww38.site.com) ya da alakasız sitelere
     * yönlenir. Yeni adresin "marka" kısmı (alt alan ve rakamlar atılmış ikinci düzey ad)
     * eskisiyle örtüşmüyorsa taşınma sayılmaz: ornekfilm.de -> ornekfilm.to kabul,
     * ornekfilm.my -> virtualsouthport.co.uk ret.
     */
    internal fun looksLikeSameSite(oldHost: String, newHost: String): Boolean {
        if (Regex("""^ww\d+\.""").containsMatchIn(newHost)) return false
        val oldBrand = brand(oldHost)
        val newBrand = brand(newHost)
        if (oldBrand.length < 3 || newBrand.length < 3) return false
        return oldBrand == newBrand || oldBrand in newBrand || newBrand in oldBrand
    }

    private fun brand(host: String): String {
        val labels = host.lowercase().removePrefix("www.").split('.')
        // "site.co.uk" gibi iki parçalı uzantılarda marka sondan üçüncü etikettir
        val secondLevel = if (labels.size >= 3 && labels[labels.size - 2].length <= 3) {
            labels[labels.size - 3]
        } else {
            labels.getOrElse(labels.size - 2) { labels.first() }
        }
        return secondLevel.replace(Regex("""\d+"""), "").replace("-", "")
    }

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host ?: return null
        // Engelleme sayfaları çoğunlukla düz IP adresine yönlendirir; onları kabul etme
        if (uri.scheme != "https" || host.matches(Regex("""[\d.]+"""))) return null
        "https://$host" + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
    }.getOrNull()

    /**
     * Kayıt: `{ "Ad": { "url", "pluginUrl", "checkedAt" } }`. `url`/`pluginUrl` yalnız taşınmış
     * siteler için vardır, `checkedAt` her denetlenen site için. Eski biçimdeki (yalnız adres
     * içeren) kayıtlar hangi eklenti sürümüne ait olduğu bilinmediği için atlanır.
     */
    private fun load() {
        runCatching {
            if (!store.exists()) return
            val root: JsonNode = mapper.readTree(store)
            for ((name, node) in root.fields()) {
                if (!node.isObject) continue
                val url = node.path("url").asText("")
                val pluginUrl = node.path("pluginUrl").asText("")
                if (url.isNotBlank() && pluginUrl.isNotBlank()) known[name] = SavedDomain(url, pluginUrl)
                val checked = node.path("checkedAt").asLong(0L)
                if (checked > 0) checkedAt[name] = checked
            }
        }.onFailure { SeyirLog.w(TAG, "domains.json okunamadı", it) }
    }

    @Synchronized
    private fun save() {
        runCatching {
            val out = sortedMapOf<String, Map<String, Any>>()
            for (name in known.keys + checkedAt.keys) {
                val entry = linkedMapOf<String, Any>()
                known[name]?.let { entry["url"] = it.url; entry["pluginUrl"] = it.pluginUrl }
                checkedAt[name]?.let { entry["checkedAt"] = it }
                out[name] = entry
            }
            AtomicFile.replace(store) { tmp -> mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, out) }
        }.onFailure { SeyirLog.w(TAG, "domains.json yazılamadı", it) }
    }

    private companion object {
        const val TAG = "DomainTracker"

        /** Aynı sitenin adresi bu süre dolmadan yeniden denetlenmez. */
        const val RECHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
