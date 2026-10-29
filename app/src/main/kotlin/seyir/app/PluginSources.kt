package seyir.app

import seyir.app.data.JsonStore
import seyir.core.AppDirs
import java.io.File

/**
 * Kurulu her eklentinin hangi depodan geldiğini (`plugin-sources.json`) hatırlar. Güncelleme yalnız bu
 * depodan yapılır; aynı adı listeleyen başka bir depo kurulu eklentinin yerine geçemez.
 *
 * Değer ya depo adresidir (kullanıcının girdiği hâliyle) ya da [LOCAL]: dosyadan ya da doğrudan
 * adresten kurulan eklenti hiçbir depoya bağlı değildir ve kendiliğinden güncellenmez.
 */
class PluginSources(private val file: File = File(AppDirs.data, "plugin-sources.json")) {
    private val map: MutableMap<String, String> =
        JsonStore.read<Map<String, String>>(file) { emptyMap() }.toMutableMap()

    @Synchronized
    fun get(internalName: String): String? = map[internalName]

    @Synchronized
    fun set(internalName: String, source: String) {
        if (map[internalName] == source) return
        map[internalName] = source
        JsonStore.write(file, map.toMap())
    }

    @Synchronized
    fun remove(internalName: String) {
        if (map.remove(internalName) != null) JsonStore.write(file, map.toMap())
    }

    companion object {
        /** Bir depoya bağlı olmayan (dosyadan ya da doğrudan adresten kurulmuş) eklentilerin kaynağı. */
        const val LOCAL = "local"

        /** Depo adreslerini karşılaştırırken sondaki eğik çizgi ve boşluklar yok sayılır. */
        fun normalize(url: String): String = url.trim().trimEnd('/')

        fun sameSource(a: String?, b: String?): Boolean = a != null && b != null && normalize(a) == normalize(b)
    }
}
