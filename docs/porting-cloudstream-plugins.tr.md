# CloudStream eklentilerini Seyir'e taşımak

[English](porting-cloudstream-plugins.md) · **Türkçe**

Bu rehber, bir [CloudStream](https://github.com/recloudstream/cloudstream) eklentisinin Seyir eklentisine nasıl
çevrileceğini, sıfırdan yeni eklenti yazmayı ve yayımlamayı anlatır. CloudStream dünyasındaki Türk sitesi sağlayıcılarının
çoğu hiç ya da birkaç satır değişiklikle çalışır.

İçindekiler

1. [Taşıma neden çalışır](#1-taşıma-neden-çalışır)
2. [Eklenti yapısı](#2-eklenti-yapısı)
3. [Adım adım taşıma](#3-adım-adım-taşıma)
4. [Derleme hataları ve çözümleri](#4-derleme-hataları-ve-çözümleri)
5. [Çalışma anında karşılaşılanlar](#5-çalışma-anında-karşılaşılanlar)
6. [Sıfırdan eklenti yazmak (KanalD örneği)](#6-sıfırdan-eklenti-yazmak-kanald-örneği)
7. [Yayımlama](#7-yayımlama)
8. [Deneysel: `.cs3` eklentileri doğrudan kurmak](#8-deneysel-cs3-eklentileri-doğrudan-kurmak)
9. [Emeği geçenler ve lisans](#9-emeği-geçenler-ve-lisans)

## 1. Taşıma neden çalışır

CloudStream eklentileri, CloudStream kütüphanesine (`MainAPI`, `newMovieLoadResponse`, `loadExtractor`, `app` HTTP
istemcisi vb.) karşı yazılmış Kotlin kodudur. Bu kütüphanenin JVM sürümü `library-jvm` vardır ve Seyir'in `core`
modülü onu kullanır. Bir eklentinin `com.lagradost.cloudstream3.*` altından içe aldığı her şey Seyir'de de aynı imzalarla
bulunur.

Eklentilerin Android'den içe aldıkları ise ayrı konudur. Seyir'in `core` modülü, eklentilerin dokunduğu az sayıda Android ve
CloudStream uygulaması sınıfının taklidini içerir:

| Seyir'in sağladığı | Not |
|---|---|
| `android.content.Context` | İçi boş bir taklit. Eklenti onu yalnızca `load(context: Context)` imzasında görür. Üzerinde hiçbir şey çağırmayın. |
| `android.util.Log` | Seyir günlüğüne yazar (`seyir.log`; CLI'da `-Dseyir.log=debug`). |
| `android.util.Base64` | Android'dekiyle aynı sabitler ve metotlar. |
| `org.json` | Android'in (AOSP) gerçeklemesi; yani sayıya `getString()` demek Android'deki gibi davranır. |
| `com.lagradost.cloudstream3.plugins.Plugin` | Eklenti giriş sınıfının tabanı. |
| `com.lagradost.cloudstream3.network.CloudflareKiller` | Cloudflare sınamasını bulur ve Seyir'in çözücüsüne verir. |
| BouncyCastle şifre sağlayıcısı | Açılışta kaydedilir; bu yüzden `AES/CBC/PKCS7Padding` gibi yalnız Android'de bulunan adlar çalışır. |

İmzalar özgün sınıflarla aynı olduğu için aynı kaynak kodu JVM için derlenir. Eklentiler `libs/seyir-api.jar` (`core`
jar'ı), CloudStream kütüphanesi, NiceHttp, jsoup, Jackson, coroutines, jspecify ve BouncyCastle'a karşı derlenir. Bunların
hepsini Seyir çalışırken kendisi sağlar; bu yüzden eklenti jar'ının içine girmezler.

### Desteklenmeyenler

- **Her türlü Android arayüzü.** Fragment, activity, iletişim kutusu, ayar ekranı, `R` kaynakları, layout, drawable. Çıkarın.
- **Genel olarak Android kaynakları.** Derleme, manifest'e `requiresResources: false` yazar. Gerçekten kaynak isteyen
  eklenti kurulumda reddedilir.
- **`WebViewResolver` gibi WebView tabanlı çıkarıcılar.** Masaüstünde WebView yoktur. Bunun yerine gerçek API isteğini
  bulun ([bölüm 5](#5-çalışma-anında-karşılaşılanlar)).
- **`context` bir taklittir.** Paylaşılan tercihler, dosya klasörü, kaynaklar ve sistem hizmetleri yoktur.
- **CloudStream eklentilerinin normalde kullanmadığı ve Seyir'in içermediği kütüphaneler.** Yukarıdaki listede yoksa
  derlenmez.

## 2. Eklenti yapısı

Eklenti bir klasördür. Seyir CloudStream düzenini kullanır; bu yüzden CloudStream eklenti klasörü olduğu gibi yerleştirilebilir:

```
BenimSite/
  build.gradle.kts
  src/main/kotlin/com/ornek/benimsite/
    BenimSitePlugin.kt     # @CloudstreamPlugin giriş sınıfı
    BenimSite.kt           # MainAPI türevi
    BenimCikarici.kt       # isteğe bağlı çıkarıcılar
```

Klasör adı eklentinin iç adıdır ve üretilen jar'ın adını belirler (Türkçe harfler ASCII'ye çevrilir: `Filmİzlesene`,
`FilmIzlesene.jar` olur).

### `build.gradle.kts` okunur, çalıştırılmaz

Eklenti deposunun kök derlemesi, klasördeki `build.gradle.kts` dosyasını düzenli ifadelerle okur. İçindeki hiçbir şey
çalıştırılmaz. Android Gradle eklentisi için yazılmış bir dosya bu yüzden olduğu gibi kalabilir.

```kotlin
version = 2

cloudstream {
    displayName = "Benim Site"                 // yalnız Seyir, isteğe bağlı
    authors     = listOf("github-kullanici-adiniz")
    language    = "tr"
    description = "Sitenin kısa tanımı."
    status      = 1                            // 0: çalışmıyor, 1: çalışıyor, 2: yavaş, 3: deneme (varsayılan 3)
    tvTypes     = listOf("Movie", "TvSeries")  // Movie, TvSeries, Anime, Documentary, Live ...
    iconUrl     = "https://ornek.com/favicon.ico"
}
```

Düzenli ifadelerden çıkan kurallar:

- Değerler yalın değişmez değer olmalıdır: `version = 2`, `language = "tr"`, `authors = listOf("a", "b")`. Değişken,
  `setOf(...)` ya da hesaplanmış metin anlaşılmaz ve varsayılana düşülür (`version` 1, `status` 3, boş listeler).
- `//` ile başlayan satırlar ve `/* ... */` blokları yok sayılır; yorum satırına alınmış değerler sayılmaz. Değerin
  sonundaki `//` yorumu sorun değildir.
- `listOf(...)` ile yazılan listenin metinleri içinde `)` bulunmamalıdır.
- `displayName` Seyir'e özgü bir eklentidir. Eklentiler ekranında görünen addır; yoksa klasör adı gösterilir. Boşluk ve
  Türkçe harf içerebilir.
- Android'e özgü bloklar (`android { }`, `dependencies { }`) kalabilir; çalıştırılmazlar.
- Otomatik güncelleme `version` değerine bakar. Her yayında artırın.

### Giriş sınıfı

`@CloudstreamPlugin` ile işaretli sınıf giriş noktasıdır. Derleme, `src/main/kotlin` altında bu notasyonu tarayarak sınıfı
bulur ve jar'ın `manifest.json` dosyasına yazar. Sınıf, her site sağlayıcısı için `registerMainAPI`, eklentinin getirdiği her
çıkarıcı için `registerExtractorAPI` çağırır:

```kotlin
package com.ornek.benimsite

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BenimSitePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BenimSite())
        // registerExtractorAPI(BenimCikarici())
    }
}
```

Bir eklenti birden çok sağlayıcı kaydedebilir. Örneğin her TV kanalı için bir sağlayıcı kaydedilebilir.

Paket adı serbesttir. nik-cloudstream'den taşınan sağlayıcılar `com.nikyokki` ya da `com.keyiflerolsun` kullanır; şablondaki
yeni eklentiler `com.seyir.<ad>` kullanır. İki eklentide aynı adlı sınıf olabilir, çünkü her eklenti kendi sınıf yükleyicisini
alır.

## 3. Adım adım taşıma

### Gerekenler

- JDK 21 (Gradle indirebilir).
- Kendi eklenti deponuz ([`plugin-template/`](../plugin-template/README.tr.md) kopyası). İçinde kök derleme, Gradle
  sarmalayıcısı ve `libs/seyir-api.jar` vardır.
- Deneme CLI'sını derlemek için Seyir'in kendi kaynağı (`./gradlew :cli:installDist`) ve oynatma denemeleri için mpv.

### Adımlar

1. **Eklenti klasörünü kopyalayın.** Bir CloudStream eklenti deposundan (ör.
   [nikyokki/nik-cloudstream](https://github.com/nikyokki/nik-cloudstream) ya da
   [keyiflerolsun/Kekik-cloudstream](https://github.com/keyiflerolsun/Kekik-cloudstream)) eklenti deponuzun köküne, öteki
   eklenti klasörlerinin yanına koyun. `AndroidManifest.xml`, `res/` ve ayar fragment kodunu silin; hiçbiri kullanılmaz.

2. **`build.gradle.kts` dosyasını gözden geçirin.** `version`, `language`, `description`, `authors`, `status`, `tvTypes` ve
   `iconUrl` değerlerinin [bölüm 2](#2-eklenti-yapısı)'deki gibi yalın değişmez değer olduğundan emin olun. Denedikten sonra
   `status = 1` yapın.

3. **Giriş sınıfına bakın.** `@CloudstreamPlugin` taşımalı ve `Plugin`'den türemelidir. Ayar ekranı ya da kaynak nesnesi atayan
   satırları çıkarın.

4. **Yalnız Android'e özgü kodu çıkarın** ([bölüm 4](#4-derleme-hataları-ve-çözümleri)). Pratikte bu, birkaç içe alma satırı
   ve `Plugin` sınıfının gövdesidir.

5. **Kullanılan bütün çıkarıcıların bulunduğundan emin olun.** Eklentinin kullandığı çıkarıcı ya eklenti klasöründe durup
   `registerExtractorAPI` ile kaydedilmeli ya da CloudStream kütüphanesinde olmalıdır (`loadExtractor(...)` adrese göre bulur).
   Çıkarıcı CloudStream deposunda kütüphanede olmayan ortak bir modüldeyse kaynağını eklenti klasörünüze kopyalayın.

6. **Derleyin.**

   ```bash
   ./gradlew makeRepo --continue
   ```

   `--continue`, biri derlenmese bile bütün eklentileri derler; derlenmeyen eklenti `build/repo` içine girmez. Yalnız
   kendinizinkini derlemek için `./gradlew :BenimSite:jar` çalıştırın. Çıktı `build/repo/` altında `BenimSite.jar`,
   `plugins.json` ve `repo.json` dosyalarıdır.

7. **Seyir CLI'sı ile deneyin.** Verdiğiniz sağlayıcı adı klasör adı değil `MainAPI.name` değeridir; `cli list` onu yazar.

   ```bash
   # Seyir klasöründe, bir kez
   ./gradlew :cli:installDist

   # derlemenizdeki sağlayıcıları listele
   JAVA_OPTS="-Dseyir.plugins=/yol/eklentileriniz/build/repo" cli/build/install/cli/bin/cli list

   # arama -> ayrıntı -> bağlantı -> mpv'de 8 sn sessiz oynatma
   JAVA_OPTS="-Dseyir.plugins=/yol/eklentileriniz/build/repo -Dseyir.play=8 -Dseyir.mpv=/usr/bin/mpv" \
     cli/build/install/cli/bin/cli probe BenimSite "aranacak sözcükler"
   ```

   Windows'ta `cli.bat` kullanın, `JAVA_OPTS`'u ortam değişkeni olarak verin. Çalışan bir eklentinin çıktısı şöyle biter:

   ```
   OYNATMA OK <bağlantı adı> q=<kalite> (ses var)
   ```

   (`ses var` akışta ses olduğunu, `SES YOK` olmadığını gösterir.) Adımlar tek tek de çalıştırılabilir: `cli home`,
   `cli search`, `cli load <sağlayıcı> <adres>`, `cli links <sağlayıcı> <bölüm-verisi>`. Eklentinin `Log` çıktısını görmek
   için `-Dseyir.log=debug`, site Cloudflare arkasındaysa `-Dseyir.cloudflare=true` ekleyin.

   Bir şey başarısız olursa önce `cli fetch <adres>` ile sitenin bugün ne döndürdüğüne bakın. Hataların çoğu taşımadan değil,
   sitenin değişmesinden gelir.

8. **Uygulamada deneyin.** Seyir'de Eklentiler -> **Dosyadan kur** ile `build/repo/BenimSite.jar` dosyasını seçin. Sağlayıcı
   sağlayıcı menüsünde görünür ve ötekiler gibi çalışır.

9. **Değişikliklerinizi işaretleyin.** Özgün kodun tanınır kalması için her değişikliği `// Seyir:`
   yorumuyla işaretleyin. Sonradan özgün depodaki düzeltmeleri birleştirecek kişiye yardımcı olur.

## 4. Derleme hataları ve çözümleri

| Hata | Neden | Çözüm |
|---|---|---|
| `Unresolved reference: R` / `BuildConfig` | Android kaynakları ve üretilen sabitler | Kullanımı çıkarın. API anahtarlarını ya da sabitleri bir Kotlin `object` içine alın. |
| `Unresolved reference: androidx`, `android.app`, `android.widget`, `FragmentActivity`, `BottomSheetDialogFragment` | Android arayüz kodu, çoğunlukla ayar ekranı | Sınıfı ve onu açan satırları (`openSettings = ...`) silin. |
| `Unresolved reference: AcraApplication`, `getKey`, `setKey`, `CommonActivity`, `showToast` | CloudStream uygulaması kodu, kütüphanede yok | Çıkarın ya da özel bir alanla değiştirin. Kalıcı eklenti ayarları desteklenmez. |
| `Unresolved reference: WebViewResolver` | Masaüstünde WebView yok | Çıkarıcıyı gerçek API'yi `app.get` ile çağıracak biçimde yeniden yazın ([bölüm 5](#5-çalışma-anında-karşılaşılanlar)). |
| `Unresolved reference: <BirÇıkarıcı>` | Çıkarıcı CloudStream deposunun başka bir modülündeydi | Kaynağını eklenti klasörüne kopyalayın ve `registerExtractorAPI` ile kaydedin. |
| CloudStream dışı bir bağımlılık için `Unresolved reference` | Kütüphane Seyir'in sınıf yolunda yok | JDK'nın, jsoup'un, Jackson'ın ya da CloudStream kütüphanesinin sunduklarıyla değiştirin. |
| `ExtractorLink(...)` veya `Episode(...)` gibi çağrılarda kullanımdan kaldırma hatası | Sabitlenen CloudStream kütüphanesi sürümü eklentinin yazıldığından farklı | Güncel kurucuları kullanın: `newExtractorLink(...)`, `newEpisode(...)`, `newMovieLoadResponse(...)`, `newTvSeriesLoadResponse(...)`, `newHomePageResponse(...)`. |
| `GradleException: ... @CloudstreamPlugin sınıfı bulunamadı` | Notasyon yok ya da dosya `src/main/kotlin` altında değil | Giriş sınıfını oraya taşıyıp notasyonu ekleyin. |

Notlar:

- `Log`, `android.util.Log` sınıfıdır. İçe alma olduğu gibi derlenir; çıktı Seyir günlüğüne gider.
- `app.get(...)` / `app.post(...)` NiceHttp'dir ve CloudStream'dekiyle aynı API'ye sahiptir. Seyir arkasına kendi istemcisini
  kurar: TLS el sıkışmasını böler, DoH kullanır, vekil alır. Eklenti kodunun bunun için bir şey yapması gerekmez.
- Kotlin uyarıları bilerek bastırılır; Android için yazılmış kod çok sayıda zararsız uyarı üretir.

## 5. Çalışma anında karşılaşılanlar

Bunlar CloudStream eklentileri taşınırken yaşanan sorunlardır.

**Bölüm `data` metni.** `Episode.data`, kullanıcı bölümü oynattığında `loadLinks`'e geri verilir. Mümkünse gerçek, mutlak
bir adres kullanın. `data` mutlak adres değilse (çıplak bir kimlik ya da slug) `newEpisode` onu sitenin `mainUrl`
değeriyle adrese çevirir (`"90511"`, `loadLinks`'e `"https://www.startv.com.tr/90511"` olarak gelir). Eklenti `data` içinde bir kimlik tutuyorsa onu savunmalı okuyun, örneğin
`data.substringAfterLast('/')`; böylece önek olsa da olmasa da çalışır.

**Yalnız görüntülü bağlantılar.** Bir bağlantı ayrı ses izleri taşıyabilir (`ExtractorLink.audioTracks`); YouTube benzeri
kaynaklar bunu gerektirir. Seyir bu izleri mpv'ye dış ses dosyası olarak verir. Oynatma desteklenir; indirme ve televizyona
yayın ayrı ses izini henüz birleştirmez.

**Alan adı değiştiren siteler.** Birçok site alan adı değiştirir ve eskisinde yönlendirme bırakır. Seyir'in
`DomainTracker` sınıfı yönlendirmeyi izler; yeni adresi ancak aynı siteye benziyorsa (aynı "marka", HTTPS, IP adresi değil,
park sayfası değil) kabul eder, `mainUrl` değerini çalışırken günceller ve hatırlar. Bundan yararlanmak için:

- `override var mainUrl` tanımlayın (`val` değil);
- adresleri mümkün olduğunca çağrı anında `mainUrl`'den kurun. Nesne oluşturulurken `mainPageOf("$mainUrl/...")` ile
  hazırlanan ana sayfa istekleri, alan adı değiştiyse Seyir tarafından yeniden yazılır;
- site kalıcı olarak taşındıysa kaynaktaki `mainUrl` değerini değiştirip `version` artırın.

**Hızlı sayfalamada HTTP/2 sıfırlamaları.** Bazı siteler art arda çok sayfa istenince bağlantıyı sıfırlar. Sayfalama
döngülerini küçük bir yeniden denemeyle sarın (`runCatching`, kısa beklemeli bir iki tekrar) ve ilk boş sayfada durun.
`KanalD`, `runCatching { ... }.getOrDefault(emptyList())` ile bunu yapar.

**Cloudflare.** Birçok site Cloudflare sınaması kullanır. Seyir, `app` üzerinden giden her istekte sınamayı algılar;
Ayarlar'da *Cloudflare çözümü* açıksa (varsayılan) sistem tarayıcısını (Edge, Chrome, Chromium ya da Brave) DevTools
protokolüyle açar, sınama geçilince çerezleri yeniden kullanır. CLI'da `-Dseyir.cloudflare=true` verin. Eklentinin kendi sınama
denetimine gerek yoktur; belirli bir uyarı metnine bakan yakalayıcıları çıkarın.

**Gerçek veri nereden gelir.** Kazınan sayfa oynatıcıyı çoğunlukla JavaScript içinde saklar. `cli fetch <adres> [referer]` ve
tarayıcının ağ sekmesiyle gerçek API isteğini ya da sayfaya gömülü JSON'u (JSON-LD, `__NEXT_DATA__`, imzalı bir `ajax`
çağrısı) bulun ve eklentiden onu isteyin.

**Medya isteğindeki başlıklar.** CDN `Referer` ya da `Origin` istiyorsa bunları `ExtractorLink` üzerinde verin (`referer`,
`headers`). Yerel aktarıcı tam olarak bu başlıkları gönderir; hata veren bir segmenti ana playlist adresini `Referer` yaparak
bir kez daha dener.

## 6. Sıfırdan eklenti yazmak (KanalD örneği)

[`plugin-template/KanalD/`](../plugin-template/KanalD/), kanald.com.tr için küçük ve eksiksiz bir sağlayıcıdır (ücretsiz,
yasal, korumasız HLS). Bu bölümü okurken `KanalD.kt` dosyasını yanınızda açın.

**1. Sağlayıcıyı tanımlayın.**

```kotlin
class KanalD : MainAPI() {
    override var mainUrl = "https://www.kanald.com.tr"
    override var name = "Kanal D (örnek)"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Güncel Diziler",
        "$mainUrl/diziler/arsiv" to "Arşiv Dizileri",
        "$mainUrl/programlar" to "Programlar",
    )
```

`name`, kullanıcıların ve CLI'nın gördüğü addır ve kurulu bütün eklentiler arasında benzersiz olmalıdır: iki eklenti aynı
`name` değerini kaydederse Seyir eklenti `version` değeri yüksek olanı (eşitse önce yükleneni) tutar ve uyarı yazar. Şablondaki
örnek bu yüzden `Kanal D (örnek)` adını taşır; kendi sağlayıcınıza ayırt edici bir ad verin. `mainPage`, ana sayfa sıralarını
`adres to başlık` olarak verir.

**2. Liste (`getMainPage`).** Sıranın adresini `app.get(...)` ile alın; dönen yanıtın `.document` alanı bir jsoup `Document`'tir.
Kartları seçin ve her birini `newTvSeriesSearchResponse(başlık, adres, TvType.TvSeries)` ile `SearchResponse`'a çevirin.
KanalD yalnız arşiv sırasına `?page=N` ekler, ötekiler için `hasNext = false` döndürür.

**3. Arama (`search`).** Sitenin kendi araması dikey afişi olmayan bölüm kartları döndürdüğü için KanalD bütün dizi
kataloğunu bir kez okur, bir alanda önbelleğe alır ve başlığa göre süzer (Türkçe harf farklarını yok sayarak); hiçbiri
eşleşmezse sitenin aramasına başvurur. Sitenize göre hangisi daha iyi sonuç veriyorsa onu seçin.

**4. Sezonlu ayrıntı (`load`).** Dizi sayfasından başlık, afiş, arka plan ve özeti okuyun (`og:` meta etiketleri, afiş
görselleri). Sonra `/<dizi>/bolumler` sayfasını okuyun, sezon bağlantılarını (`activeSeasonNumber=N`) toplayın ve her sezonun
sayfalarını yeni bölüm eklemeyen bir sayfaya gelene kadar gezin. Her bölüm `newEpisode(adres) { name; season; episode; posterUrl }`
ile oluşturulur, `data` olarak bölüm sayfasının adresi verilir. Sonuç
`newTvSeriesLoadResponse(başlık, adres, TvType.TvSeries, bölümler) { posterUrl; backgroundPosterUrl; plot }` olur. Film için
`newMovieLoadResponse(başlık, adres, TvType.Movie, dataUrl)` kullanın.

**5. Bağlantılar (`loadLinks`).** Bölüm sayfası, HLS playlist'ini gösteren `contentUrl` içeren bir JSON-LD gömer.

```kotlin
val html = app.get(data).text
val path = Regex(""""contentUrl"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(html)?.groupValues?.get(1)
    ?.replace("\\/", "/") ?: return false
callback(
    newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
        referer = "$mainUrl/"
        quality = Qualities.Unknown.value
    }
)
return true
```

Eski bölümlerde adres görelidir; kökü sayfadaki `MediaCdnDomainUrl` verir. `loadLinks` bağlantı bulduysa `true` döndürür.
Altyazılar `subtitleCallback(SubtitleFile(dil, adres))` ile verilir. Üçüncü taraf sunucular için
`loadExtractor(adres, referer, subtitleCallback, callback)` çağırın ve oynatıcıyı kütüphanenin çıkarıcılarına çözdürün.

**6. Giriş sınıfı ve üst veri.** `KanalDPlugin.kt` `KanalD()` sağlayıcısını kaydeder; `KanalD/build.gradle.kts` içinde
[bölüm 2](#2-eklenti-yapısı)'de anlatıldığı gibi `version`, `displayName`, `description`, `tvTypes` vb. bulunur.

**Kendi eklentinize başlamak için:** şablonu kopyalayın, `KanalD` klasörünün (ve paketin, sınıfların) adını değiştirin, üst
veriyi düzenleyin, `KanalD.kt` dosyasını yeniden yazın; sonra [bölüm 3](#3-adım-adım-taşıma)'teki gibi derleyip deneyin.
`probe` çalıştırmadan önce her işlevi CLI ile ayrı ayrı deneyin (`home`, `search`, `load`, `links`).

## 7. Yayımlama

### Depo yapısı

Eklenti deposu, kökü şöyle görünen bir GitHub deposudur:

```
build.gradle.kts        # kök derleme: eklenti klasörlerini okur, build/repo yazar
settings.gradle.kts     # build.gradle.kts içeren her klasör bir eklentidir
gradle.properties       # excludedPlugins
gradlew, gradlew.bat, gradle/
libs/seyir-api.jar      # eklentilerin derlendiği API
BenimSite/
BaskaSite/
```

En kolay başlangıç, [`plugin-template/`](../plugin-template/README.tr.md) klasörünü yeni bir depoya kopyalamaktır.

### Derleme ve yayın

```bash
./gradlew makeRepo --continue   # build/repo/: <Ad>.jar, plugins.json, repo.json
./gradlew publishRepo           # build/repo'yu origin'in "builds" dalına zorla iter
```

`publishRepo` önce derler, sonra `build/repo` içeriğinden tek commit'lik bir geçmiş oluşturur ve `origin` deposunun `builds`
dalına zorla iter; yani `builds` büyümez. Şablonda ayrıca `.github/workflows/build.yml` vardır; GitHub Actions
kullanılabiliyorsa `main`'e her itmede aynı işi yapar. Kullanılamıyorsa (ör. ödeme sorunu) `publishRepo` yerelde çalıştırılır.

### Kullanıcılar eklentilerinizi nasıl ekler

Seyir'de Eklentiler -> Depolar sekmesine şunlardan birini yapıştırır:

| Girilen | Seyir'in okuduğu |
|---|---|
| `https://github.com/<kullanıcı>/<depo>` | `https://raw.githubusercontent.com/<kullanıcı>/<depo>/builds/repo.json` |
| doğrudan `repo.json` adresi | `pluginLists` içeren, CloudStream biçimli bildirim |
| doğrudan `plugins.json` adresi | eklenti listesinin kendisi |
| tek bir `.jar` (ya da `.cs3`) adresi | yalnız o eklenti kurulur |

`plugins.json` içindeki göreli `url` değerleri, onları listeleyen dosyaya göre çözülür. **Dosyadan kur**, indirilmiş tek bir
`.jar` dosyasını kurar. Yayımlamadan yerelde denemek için `build/repo` klasörünü sunun (`python -m http.server 8765`) ve
`http://127.0.0.1:8765/repo.json` adresini ekleyin.

**GitHub dışında barındırmak.** Bir depo yalnız durağan dosyalardan oluşur: `build/repo` içinde `.jar` dosyaları,
`plugins.json` ve `repo.json` bulunur. Bu klasörü herhangi bir web sunucusuna yükleyin (HTTPS önerilir) ve kullanıcılara
`repo.json` adresini verin, örneğin `https://example.com/seyir/repo.json`. Kurma, güncelleme ve seçim penceresi aynı
çalışır; yalnız `github.com/<kullanıcı>/<depo>` kısaltması ve GitHub erişim anahtarı GitHub'a özgüdür.

### Bütünlük ve güven

Bir eklenti jar'ı rastgele koddur; bu yüzden Seyir nereden geldiği konusunda katıdır:

- **`sha256` (isteğe bağlı, `plugins.json` içinde her kayıtta).** `url` ile gösterilen dosyanın SHA-256 özeti, küçük harfli
  onaltılık yazımla. `makeRepo` her eklenti için bunu yazar. Alan varsa Seyir indirilen baytların özetini hesaplar ve uyuşmazlıkta
  kurulumu reddeder (biçimi bozuk bir değer de uyuşmazlık sayılır); alan yoksa eklenti eskisi gibi kurulur.
- **Değiştirmeden önce doğrulama.** Kurulu bir jar'ın yerine geçmeden önce yeni dosya zip olarak açılmalı, okunabilir bir
  `manifest.json` içermeli ve `pluginClassName` ile gösterilen giriş sınıfını barındırmalıdır. Aksi hâlde kurulu sürüme dokunulmaz.
- **Yalnız HTTPS.** Depo ve eklenti adresleri, yönlendirmelerden sonra da, `https://` olmalıdır. `http://` yalnız `localhost`,
  `127.0.0.1` ve `::1` için (yerel geliştirme) kabul edilir. Yerel dosya yolları kabul edilir.
- **Kaynak sabitleme.** Seyir her eklentinin hangi depodan kurulduğunu hatırlar ve onu yalnız o depodan günceller; aynı iç adı
  listeleyen başka bir depodan asla. Dosyadan ya da tek bir adresten kurulan eklenti hiçbir depoya bağlı değildir ve
  kendiliğinden güncellenmez.

### Özel depolar

Özel bir GitHub deposu için kullanıcı Seyir -> Ayarlar'a bir GitHub erişim anahtarı girer. Anahtar yalnız GitHub adreslerine
(`github.com`, `raw.githubusercontent.com`, `api.github.com`, `objects.githubusercontent.com`) gönderilir, bir eklentinin
sitesine asla gönderilmez. Depo bulunamazsa Seyir bunu söyler ve erişim anahtarına işaret eder.

### Sürümleme ve güncelleme

Depo, kurulu olandan yüksek bir `version` listelediğinde Seyir eklentiyi günceller. *Otomatik eklenti güncellemesi* açıkken
(varsayılan) açılışta, kurulu eklentiler kurulduğu depodan güncellenir. Kurulu olmayan eklentiler kendiliğinden
kurulmaz. **Yayımladığınız her değişiklikte klasördeki `build.gradle.kts` içinde `version` değerini artırın.**

### Eklentiyi derlemeden çıkarmak

Bir klasör depoda kalıp derlemeden atlanabilir; örneğin site kapalıyken. Klasör adlarını `gradle.properties` içine yazın:

```properties
# Kapanmış siteler, gerekçesi yorum olarak
excludedPlugins=EskiSite,Filmİzlesene
```

`gradle.properties` bir Java özellikler dosyasıdır; bu yüzden Türkçe harfler Unicode kaçış dizisiyle yazılmalıdır: `İ` = `İ`,
`ı` = `ı`, `ş` = `ş`, `ç` = `ç`, `ğ` = `ğ`, `ö` = `ö`, `ü` = `ü`. Satır sonundaki `\`, listeyi
sonraki satırda sürdürür.

## 8. Deneysel: `.cs3` eklentileri doğrudan kurmak

> **Deneysel.** Önerilen yol kaynak koddan taşımaktır (bölüm 3 ve 4). Çalışma anında daha hızlıdır, site değişikliklerini siz
> düzeltebilirsiniz ve sonuç normal bir Seyir eklentisidir.

CloudStream eklentileri `.cs3` dosyası olarak dağıtır: içinde `classes.dex` (Android bayt kodu) ve `manifest.json` bulunan bir
zip. Seyir bu dosyayı kurarken [dex2jar](https://github.com/ThexXTURBOXx/dex2jar) ile JVM jar'ına çevirebilir:

- **Dosyadan kur**, `.jar` yanında `.cs3` de kabul eder.
- Adresle eklenen depo, eklenti listesi `.cs3` dosyalarına işaret eden bir CloudStream deposu olabilir; Depolar kutusu tek bir
  `.cs3` adresini de kabul eder.
- Komut satırından: `cli cs3 <girdi.cs3> <cikti.jar>` dosyayı kurmadan çevirir.

Çevirici manifest'i ve çevrilmiş sınıfları kopyalar; Android kaynakları (`resources.arsc`, `res/`) taşınmaz ve manifest'inde
`requiresResources: true` yazan eklenti reddedilir.

Birçok eklentide çalışır. Denememizde `.cs3` dosyasından kurulan DMax, kaynaktan taşınan eklentiyle aynı davrandı. Garanti
değildir: Seyir'in sağlamadığı bir Android ya da CloudStream uygulaması sınıfını çağıran çevrilmiş kod, kurulumda değil o
kod yolu çalışınca (genellikle `NoClassDefFoundError` ile) hata verir. Çevrilmiş bir eklenti yanlış davranırsa kaynağından
taşıyın.

## 9. Emeği geçenler ve lisans

CloudStream eklentileri çoğunlukla GPL-3.0 ile lisanslıdır. Birini taşırken:

- kopyaladığınız her dosyada özgün yazarın başlığını ve özgün depoya bağlantıyı koruyun;
- özgün kodun lisansını koruyun. CloudStream eklentilerinden türetilen eklenti depoları çoğunlukla GPL-3.0'dır ve bu
  lisansı ile yazar başlıklarını korumalıdır;
- neyi değiştirdiğinizi belirtin (yukarıda anılan `// Seyir:` yorumları).

CloudStream kütüphanesi ve eklenti API'si [recloudstream](https://github.com/recloudstream/cloudstream) ekibinin
emeğidir. Her eklenti deposu içindeki sağlayıcıların yazarlarını kendisi anar.

Eklentiler kullanıcının bilgisayarında kod çalıştırır. Kullanıcıdan bilgi toplayan ya da sitenin istediğinden fazlasını yapan
eklenti yayımlamayın. Eklenti yalnız herkese açık sayfaları okumalı ve eriştiği
içeriğin haklarına uymalıdır; bu sorumluluk eklenti yazarınındır.
