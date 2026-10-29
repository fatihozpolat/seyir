# Seyir eklenti şablonu

[English](README.md) · **Türkçe**

Türk filmlerini, dizilerini ve canlı TV kanallarını izlemeye yarayan masaüstü uygulaması
[Seyir](https://github.com/fatihozpolat/seyir) için eklenti yazmaya hazır bir başlangıç projesi. Eklentiler CloudStream
eklenti API'sini kullanır ve her klasör **CloudStream düzeninde** bir eklentidir: bir CloudStream deposundaki klasör
yapısının aynısı. Derleme sonunda Seyir'in doğrudan okuyabildiği bir depo (`repo.json`, `plugins.json`, `<Ad>.jar`) üretilir.

Örnek eklenti **`KanalD/`**: kanald.com.tr için ana sayfa, arama, sezonlar ve JSON-LD'den okunan HLS bağlantılarıyla küçük ve
eksiksiz bir sağlayıcı. [Taşıma ve geliştirme rehberi](../docs/porting-cloudstream-plugins.tr.md) ile birlikte okuyun.

## Nasıl çalışır

- Proje kökündeki `build.gradle.kts` içeren her klasör bir eklentidir.
- Klasördeki `build.gradle.kts` **hiç çalıştırılmaz**. CloudStream üst verisini (`version` ve `cloudstream { }` bloğu) taşır;
  kök `build.gradle.kts` onu düzenli ifadelerle okur, gerçek derleme ayarlarını kendisi verir. `buildSrc` yoktur.
- Kök derleme her eklentiyi `libs/seyir-api.jar` (Seyir'in Android ve CloudStream uyumluluk katmanı) ile CloudStream
  kütüphanesine karşı JVM için derler ve jar'a bir `manifest.json` yazar.
- `./gradlew makeRepo` depoyu `build/repo/` altına yazar; `./gradlew publishRepo` onu `builds` dalına iter.

## Gerekenler

- JDK 21 (yoksa Gradle indirir)
- İlk derlemede internet (bağımlılıklar)

## Hızlı başlangıç

1. **Şablonu kopyalayın**: yeni bir projeye ya da GitHub deposuna (`build/` hariç).

2. **Örneği yeniden adlandırın.** `KanalD/` klasörünün adını değiştirin (klasör adı eklentinin iç adıdır), sonra içindeki
   paketi ve sınıfları yeniden adlandırın. Gerekmeyen örnek kaynakları silin.

3. **Üst veriyi düzenleyin**: `<Eklentiniz>/build.gradle.kts`:

   ```kotlin
   version = 1 // her yayında artırın; Seyir güncellemeyi buna bakarak bulur

   cloudstream {
       displayName = "Siteniz"                        // yalnız Seyir, isteğe bağlı: uygulamada görünen ad
       description = "Sitenin kısa tanımı"
       authors     = listOf("github-kullanici-adiniz")
       language    = "tr"
       tvTypes     = listOf("Movie", "TvSeries")      // Movie, TvSeries, Anime, Documentary, Live ...
       iconUrl     = "https://ornek.com/favicon.ico"
       status      = 1                                // 0: çalışmıyor, 1: çalışıyor, 2: yavaş, 3: deneme (varsayılan 3)
   }
   ```

   Değerler yalın değişmez değer olmalıdır (`version = 2`, `authors = listOf("a")`); başka biçim anlaşılmaz.

4. **Kodu yazın**: `src/main/kotlin/...` altına. İki sınıf gerekir:
   - `@CloudstreamPlugin` ile işaretli, `Plugin`'den türeyen, `registerMainAPI(...)` (çıkarıcılar için
     `registerExtractorAPI(...)`) çağıran giriş sınıfı;
   - `MainAPI`'den türeyen sağlayıcı: `getMainPage`, `search`, `load` ve `loadLinks`.

   API, CloudStream'inkidir (`com.lagradost.cloudstream3.*`). Android arayüz kodu (ayar ekranları, `R` kaynakları) ve WebView
   tabanlı çıkarıcılar desteklenmez.

5. **Derleyin:**

   ```bash
   ./gradlew makeRepo --continue   # Windows: gradlew.bat makeRepo --continue
   ```

   Çıktı `build/repo/` altındadır. Derlenmeyen eklenti dışarıda kalır; `--continue` ötekilerin bitmesini sağlar.

6. **Deneyin.**
   - *Seyir CLI'sı ile* (Seyir deposunda `./gradlew :cli:installDist` ile derlenir):

     ```bash
     JAVA_OPTS="-Dseyir.plugins=/yol/bu-proje/build/repo -Dseyir.play=8 -Dseyir.mpv=/usr/bin/mpv" \
       cli probe "Siteniz" "aranacak sözcükler"
     ```

     Çalışan bir eklenti `OYNATMA OK` yazar. `cli list`, `home`, `search`, `load`, `links` ve `fetch` adımları tek tek dener.
   - *Uygulamada:* Eklentiler -> **Dosyadan kur** -> `build/repo/Eklentiniz.jar`.
   - *Yerel depo ile:* `build/repo` içinde `python -m http.server 8765` çalıştırın, sonra Eklentiler -> Depolar sekmesine
     `http://127.0.0.1:8765/repo.json` adresini ekleyin.

7. **Yayımlayın.**
   - Projeyi bir GitHub deposuna itin.
   - `./gradlew publishRepo` çalıştırın. Derler ve `build/repo` içeriğini tek commit olarak `origin`'in `builds` dalına zorla
     iter.
   - GitHub Actions kullanılabiliyorsa `.github/workflows/build.yml` `main`'e her itmede aynı işi yapar (depo ayarlarında
     Actions -> General -> Workflow permissions bölümünü "Read and write" yapın).
   - Kullanıcılar Seyir -> Eklentiler -> Depolar sekmesine `https://github.com/<kullanıcı>/<depo>` adresini girer. Seyir bunu
     `https://raw.githubusercontent.com/<kullanıcı>/<depo>/builds/repo.json` olarak okur. Doğrudan `repo.json` /
     `plugins.json` adresi ya da tek bir `.jar` adresi de olur. Özel depolar için Seyir'in Ayarlar bölümüne GitHub erişim anahtarı
     girilmelidir.

## Var olan bir CloudStream eklentisini taşımak

Klasörünü proje köküne kopyalayın, yalnız Android'e özgü kodu çıkarın ve derleyin. Sık görülen derleme hataları ve çalışma
anı tuzaklarıyla tam anlatım
**[docs/porting-cloudstream-plugins.tr.md](../docs/porting-cloudstream-plugins.tr.md)** dosyasındadır.

## Depo adı

Eklentiler ekranında görünen depo adı ve açıklaması, kök `build.gradle.kts` dosyasının başındaki `repoName` ve
`repoDescription` değerleridir.

## Eklenti atlamak

Derlemeye girmesini istemediğiniz klasörlerin adlarını `gradle.properties` içindeki `excludedPlugins` satırına yazın. Dosya
bir Java özellikler dosyasıdır; Türkçe harfler Unicode kaçış dizisiyle yazılır (`İ` = `İ`, `ı` = `ı`,
`ş` = `ş`).

## Seyir API'si

`libs/seyir-api.jar`, Seyir'in eklentilere sunduğu sınıfları (Android ve CloudStream taklitlerini) içerir.
`gradle/libs.versions.toml` uyumlu bağımlılık sürümlerini sabitler. Seyir değiştiğinde ikisini de Seyir deposunda
`./gradlew syncPluginTemplate` ile yenileyin ya da bu şablonun yeni sürümünden alın. Kotlin sürümü uygulamayla aynı kalmalıdır.

## Güvenlik

Eklentiler kullanıcının bilgisayarında kod çalıştırır. Kullanıcıdan bilgi toplayan ya da sitenin istediğinden fazlasını yapan
eklenti yayımlamayın.
