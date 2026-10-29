package seyir.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.plugins.BasePlugin
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Bir eklentinin Seyir'de çalışıp çalışmayacağına dair statik denetimin sonucu.
 *
 * @property missingClasses jar'da, JDK'da ve Seyir'in kendi sınıf yolunda bulunmayan sınıflar (nokta biçiminde, sıralı)
 * @property requiresResources manifest Android arayüz kaynaklarını (`requiresResources`) istiyorsa true
 */
data class CompatibilityReport(
    val missingClasses: List<String>,
    val requiresResources: Boolean = false,
) {
    /** Eksik sınıf yok ve Android kaynağı istenmiyorsa eklenti yüklenebilir. */
    val compatible: Boolean get() = missingClasses.isEmpty() && !requiresResources

    /** Eksik sınıflar paketlerine göre gruplanmış (paket → sınıf adları). */
    val missingByPackage: Map<String, List<String>>
        get() = missingClasses.groupBy { it.substringBeforeLast('.', "") }.toSortedMap()

    /** İletide göstermek için ilk [limit] eksik sınıf; fazlası varsa sonuna "(+N)" eklenir. */
    fun describeMissing(limit: Int = 3): String {
        val shown = missingClasses.take(limit).joinToString(", ")
        val more = missingClasses.size - limit
        return if (more > 0) "$shown (+$more)" else shown
    }
}

/** Bir sınıfın (iç ad: `java/lang/String`) çalışma ortamında bulunup bulunmadığını söyler. */
fun interface ClassResolver {
    fun exists(internalName: String): Boolean
}

/**
 * Eklenti jar'ının başvurduğu sınıfları sabit havuzdan (constant pool) okuyup eksik olanları bulan
 * statik denetim. Eklentinin hiçbir kodu çalıştırılmaz; yalnızca sınıf dosyaları okunur.
 *
 * Bir sınıf şu üç yerden birinde varsa karşılanmış sayılır: jar'ın kendisi, JDK (platform sınıf
 * yükleyicisi) ya da eklentilerin üst yükleyicisi olan Seyir'in kendi sınıf yolu
 * ([PluginLoader.parentClassLoader]). Yöntem ve alan düzeyinde denetim yapılmaz; yalnız sınıflara bakılır.
 */
object PluginCompatibility {
    private val mapper = jacksonObjectMapper()

    /** Gerçek çalışma ortamını (JDK + Seyir sınıf yolu) kullanan çözümleyici. */
    val runtimeResolver: ClassResolver by lazy {
        val platform = ClassLoader.getPlatformClassLoader()
        val parent = PluginLoader.parentClassLoader
        ClassResolver { name -> existsIn(platform, name) || existsIn(parent, name) }
    }

    fun check(jar: File, resolver: ClassResolver = runtimeResolver): CompatibilityReport {
        val own = HashSet<String>()
        val referenced = HashSet<String>()
        var requiresResources = false
        ZipFile(jar).use { zip ->
            val classEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .toList()
            classEntries.forEach { own += it.name.removeSuffix(".class") }
            for (entry in classEntries) {
                // Bozuk bir sınıf dosyası denetimin tamamını düşürmesin; o sınıf atlanır
                runCatching { referenced += referencedClasses(zip.getInputStream(entry).use { it.readBytes() }) }
            }
            requiresResources = zip.getEntry("manifest.json")?.let { entry ->
                runCatching { zip.getInputStream(entry).use { mapper.readValue<BasePlugin.Manifest>(it).requiresResources == true } }
                    .getOrDefault(false)
            } ?: false
        }
        val missing = referenced.asSequence()
            .filter { it !in own }
            .filter { !resolver.exists(it) }
            .filter { !isDex2jarArtifact(it) }
            .map { it.replace('/', '.') }
            .toSortedSet()
            .toList()
        return CompatibilityReport(missing, requiresResources)
    }

    private val platformLoader: ClassLoader by lazy { ClassLoader.getPlatformClassLoader() }

    /**
     * dex2jar'ın bazı çıktılarında sabit havuzda `java/util/Object` gibi bir sınıf girdisi bulunur: yalnız
     * StackMapTable çerçevelerinde geçen, hiçbir komutun kullanmadığı bir artıktır (tür çıkarımında `Object`
     * yanlış pakete yazılmış). Çerçeve girdilerini ayırt eden bir okuyucu gerekmeden şu kural yeter: `java.*`
     * ya da `javax.*` altında olup basit adı bir `java.lang` sınıfıyla (`Object`, `String`, ...) aynı olan
     * sınıf gerçek bir eksiklik sayılmaz. Gerçek bir JDK'da böyle bir sınıf yoktur; eklenti derlenirken de
     * başvurulamaz, yani kasıtlı bir kullanımı olamaz.
     */
    internal fun isDex2jarArtifact(internalName: String): Boolean {
        if (!internalName.startsWith("java/") && !internalName.startsWith("javax/")) return false
        val pkg = internalName.substringBeforeLast('/', "")
        if (pkg == "java/lang") return false
        val simple = internalName.substringAfterLast('/')
        return existsIn(platformLoader, "java/lang/$simple")
    }

    /**
     * Tek bir sınıf dosyasının sabit havuzundan başvurulan sınıfları (iç adlarıyla) çıkarır: sınıf
     * girdileri ile alan/yöntem tanımlayıcılarında (descriptor) geçen sınıf adları. Dizi türleri
     * açılır, ilkel türler elenir.
     */
    fun referencedClasses(classBytes: ByteArray): Set<String> {
        val input = DataInputStream(ByteArrayInputStream(classBytes))
        require(input.readInt() == 0xCAFEBABE.toInt()) { "Sınıf dosyası değil" }
        input.skipBytes(4) // küçük ve büyük sürüm numaraları
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classNames = ArrayList<Int>()
        val descriptors = ArrayList<Int>() // tanımlayıcı içeren Utf8 girdilerinin dizinleri
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                3, 4 -> input.skipBytes(4)
                5, 6 -> { input.skipBytes(8); i++ } // long ve double iki yer kaplar
                7 -> classNames += input.readUnsignedShort()
                8, 19, 20 -> input.skipBytes(2)
                16 -> descriptors += input.readUnsignedShort() // MethodType: tanımlayıcı
                9, 10, 11, 17, 18 -> input.skipBytes(4)
                12 -> { input.skipBytes(2); descriptors += input.readUnsignedShort() } // NameAndType: ad, tanımlayıcı
                15 -> input.skipBytes(3)
                else -> throw IllegalArgumentException("Bilinmeyen sabit havuzu etiketi: $tag")
            }
            i++
        }
        val result = LinkedHashSet<String>()
        for (index in classNames) {
            val name = utf8.getOrNull(index) ?: continue
            if (name.startsWith("[")) addDescriptorClasses(name, result) else result += name
        }
        for (index in descriptors) utf8.getOrNull(index)?.let { addDescriptorClasses(it, result) }
        return result
    }

    /** Tanımlayıcıdaki `Lpaket/Sınıf;` parçalarını toplar (`(ILjava/lang/String;)V` → java/lang/String). */
    private fun addDescriptorClasses(descriptor: String, into: MutableSet<String>) {
        var i = 0
        while (i < descriptor.length) {
            if (descriptor[i] == 'L') {
                val end = descriptor.indexOf(';', i)
                if (end < 0) return
                into += descriptor.substring(i + 1, end)
                i = end + 1
            } else i++
        }
    }

    private fun existsIn(loader: ClassLoader?, internalName: String): Boolean {
        if (loader == null) return false
        // Sınıf dosyası kaynağı her zaman görünür; sınıfı yüklemeden ve ilklendirmeden bakmak yeter
        if (loader.getResource("$internalName.class") != null) return true
        return runCatching { Class.forName(internalName.replace('/', '.'), false, loader) }.isSuccess
    }
}
