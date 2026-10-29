# Seyir

[English](README.md) · **Türkçe**

**Seyir, Windows ve Linux için eklenti tabanlı bir masaüstü medya uygulamasıdır.** Eklenti depoları eklenir, kurulu
bütün sağlayıcılarda birlikte gezinilip aranır; filmler, diziler ve canlı kanallar mpv'de oynatılır ya da Google TV ve
Chromecast'e gönderilir. CloudStream uyumlu eklentileri, deneysel olarak CloudStream'in `.cs3` dosyalarını da çalıştırır.

Seyir, Kotlin ve Compose Desktop ile yazılmıştır. Uygulamanın kendi içinde hiçbir siteye ait kod yoktur; neyin
izlenebileceğini tamamen kurulan eklentiler belirler. Eklentiler CloudStream eklenti API'sini kullanır; bu yüzden
CloudStream sağlayıcılarının çoğu kaynak koduna az dokunularak ya da hiç dokunulmadan taşınabilir. Eklentiler ayrı
depolarda durur ve depoları kullanıcı kendisi ekler.

## Özellikler

- **Sağlayıcılar arasında arama.** Tek sorgu açık olan bütün sağlayıcılarda aynı anda çalışır; sonuçlar geldikçe
  sağlayıcı sağlayıcı listelenir. Aranabilir sağlayıcı süzgeci aramayı tek siteyle sınırlar.
- **Ana sayfa sıraları.** Seçili sağlayıcıdan öne çıkan pano ve afiş sıraları; altında bütün sağlayıcılardan "Devam et"
  sırası.
- **Kitaplık.** Kalınan yerden devam etme, izleme geçmişi ve **Listem**.
- **İndirme.** Düz dosyalar doğrudan indirilir; HLS akışları ffmpeg ile kaydedilir.
- **Uygulama içi oynatıcı.** Video Seyir penceresinin içinde mpv ile, Seyir'in kendi denetimleriyle oynar: ileri sarma
  önizlemesi, altyazı, ses, kalite, hız ve kaynak menüleri, bölüm listesi, önceki/sonraki bölüm ve bölüm sonuna doğru
  "sıradaki bölüm" kartı. Altyazılar başlıklarıyla eklenir, tercih ettiğiniz dil seçilir, boyutu değiştirilebilir. Yalnız
  görüntü veren akışlar (ör. YouTube) ayrı ses izleriyle oynatılır. İstenirse ayrı mpv penceresi kullanılır (Ayarlar);
  Linux'ta uygulama içi oynatıcı orada doğrulanana kadar varsayılan budur.
- **Televizyona yayın.** Ağınızdaki Google TV ya da Chromecast'e akış gönderilir. Alıcılar mDNS ile bulunur; Ayarlar'dan
  adres elle de girilebilir. Kumandayla yapılan duraklatma ve sarma uygulamaya yansır.
- **Eklenti depoları.** GitHub adresiyle, `repo.json` ya da `plugins.json` ile depo eklenir. Eklentiler Eklentiler
  ekranından kurulur, güncellenir, kaldırılır; tek bir eklenti `.jar` / `.cs3` dosyasından ya da adresinden de kurulabilir.
- **Otomatik eklenti güncellemesi.** Açılışta, depoda daha yüksek `version` yayımlanmış kurulu eklentiler kendiliğinden
  güncellenir; depoya sonradan eklenen eklentiler de kurulur, kaldırdıklarınız geri gelmez (ikisi de Ayarlar'dan
  kapatılabilir). Deposundan çıkarılan kurulu eklenti Eklentiler ekranında işaretlenir.
- **Özel depolar.** Ayarlar'daki GitHub erişim anahtarı özel eklenti depolarını okumak için kullanılır; yalnız GitHub
  adreslerine gönderilir.
- **İsteğe bağlı TMDB üst verisi.** Ayarlar'a TMDB anahtarı girilirse eksik afiş, arka plan, özet, yıl ve puan TMDB'den
  tamamlanır (yalnız eşleşme kesinse; eklentinin verdiği bilgi her zaman önceliklidir). Varsayılan olarak kapalıdır.
- **Klavye ve ekran okuyucu.** Her şey klavyeyle kullanılır (Tab, Enter/Boşluk, geri için Esc ya da Alt+Sol, arama için
  Ctrl+F ya da `/`); odak halkası görünür, denetimlerin ekran okuyucu etiketleri vardır.
- **Cloudflare sınamaları.** Sınamalar sistemdeki Edge, Chrome, Chromium ya da Brave ile çözülür. Tarayıcı önce ekran
  dışında açılır, sınama kendiliğinden geçilmezse görünür olur.
- **Gizlilik ve sansüre dayanıklılık.** Şifreli DNS (DNS over HTTPS: Cloudflare, Google, AdGuard), TLS ClientHello
  parçalama (ilk TLS kaydı SNI ortasından bölünür; yaygın bir DPI karşıtı yöntemdir) ve isteğe bağlı SOCKS/HTTP vekil.
- **Alan adı takibi.** Bir site yeni alan adına taşınıp eskisinden yönlendirme bıraktığında Seyir yönlendirmeyi izler ve
  eklentinin adresini günceller.

## Kurulum

Paketler bu deponun [GitHub Releases](https://github.com/fatihozpolat/seyir/releases) sayfasında yayımlanır.

v1.0.0 yalnız Windows paketleriyle çıktı; Linux paketleri (aşağıdaki satırlar) doğrulandıktan sonra eklenecek.
Şimdilik Linux'ta kaynaktan çalıştırılabilir (aşağıda "Kaynaktan derleme").

| Sistem | Paket | Not |
|---|---|---|
| Windows 10/11 | `.msi` veya `.exe` | Kullanıcıya kurulur, yönetici gerekmez. **mpv içinde gelir.** |
| Debian / Ubuntu | `.deb` | `sudo apt install ./seyir_*.deb` (mpv bağımlılık olarak bildirilir, apt kurar) |
| Fedora | `.rpm` | `sudo dnf install ./seyir-*.rpm` (mpv gerekir; Fedora'da RPM Fusion deposundan gelir) |
| Diğer Linux | `seyir-linux-x64.tar.gz` | Açıp `Seyir/bin/Seyir` çalıştırın. mpv'yi paket yöneticisinden kurun. |

Windows paketleri kod imzası taşımaz; ilk kurulumda SmartScreen "Windows bilgisayarınızı korudu" uyarısı çıkarsa
**Ek bilgi → Yine de çalıştır** deyin. Sonraki güncellemeler uygulama içinden gelir ve Seyir'in kendi imzasıyla
(Ed25519, `SHA256SUMS.sig`) doğrulanır; imza uymazsa kurulmaz.

Linux'ta mpv'yi paket yöneticisiyle kurun (`sudo apt install mpv`). HLS akışlarını indirmek için `ffmpeg` PATH'te olmalıdır.
Cloudflare çözümü için Edge, Chrome, Chromium veya Brave kurulu olmalıdır.

Veriler Windows'ta `%APPDATA%\Seyir`, Linux'ta `~/.local/share/seyir` altında tutulur (ayarlar, geçmiş, önbellek,
`seyir.log`). İndirilenler `~/Downloads/Seyir` klasörüne gider.

## İlk açılış

Seyir hiç eklenti ve hazır depo adresi olmadan gelir. İlk açılışta ana sayfada bir metin alanı çıkar: bir eklenti
deposunun adresini yapıştırıp **Ekle ve kur** düğmesine basın (İngilizce arayüzde "Add and install"). **Eklentiler
ekranını aç** bağlantısı sizi doğrudan Eklentiler ekranına götürür. Depoyu sonradan da **Eklentiler -> Depolar -> adresi
yapıştırın** yoluyla ekleyebilirsiniz. Örnek:

```
https://github.com/<kullanıcı>/<depo>
```

Yapıştırabilecekleriniz:

- **Bir GitHub depo adresi** (`https://github.com/<kullanıcı>/<depo>`). Seyir depo listesini şu sırayla arar: `builds`
  dalındaki `builds/repo.json`, sonra `master` ya da `main` dalındaki `repo.json`, sonra `builds/plugins.json`.
- **Doğrudan bir `repo.json` ya da `plugins.json` adresi.**
- **Tek bir eklenti adresi**: `.jar` ya da `.cs3` ile biten bir bağlantı.
- **Diskteki bir dosya**: Eklentiler ekranındaki **Dosyadan kur** ile.

Depo özelse önce Ayarlar'a bir GitHub erişim anahtarı girin. Anahtar yalnız GitHub adreslerine gönderilir.

## Nasıl çalışır

```
eklenti deposu (GitHub "builds" dalı: repo.json, plugins.json, <Ad>.jar)
        |  kurma / güncelleme (Eklentiler ekranı)
        v
%APPDATA%\Seyir\plugins\*.jar           her jar'da manifest.json (giriş sınıfı, sürüm)
        |  PluginLoader: her eklentiye ayrı URLClassLoader
        v
core
  |- android/*, com/lagradost/*   Android ve CloudStream sınıflarının taklitleri
  |                               (eklentiler Android olmadan JVM'de derlenir ve çalışır)
  |- net/                         TLS kaydı bölme, DoH, vekil, Cloudflare işleme
  |- relay/                       yerel aktarıcı: playlist yeniden yazma, başlıklar, CONNECT tüneli, yayın için ağ kapısı
  |- player/                      mpv süreci ve IPC (konum, altyazı, gömülü oynatıcı durumu)
  |- cast/                        Google Cast bulma ve protokol
  |- browser/                     Chrome DevTools Protocol ile Cloudflare çözücü
  `- DomainTracker                site taşınmalarını izler
        |
        v
app (Compose Desktop)   /   cli (komut satırı deneme aracı)
```

Oynatma hattı: eklentinin bulduğu bağlantı yerel aktarıcıya kaydedilir, mpv `http://127.0.0.1:<port>/...` adresini
açar. Aktarıcı her istekte uygulamanın kendi HTTP istemcisini (DoH, ClientHello parçalama, Cloudflare işleme) ve
eklentinin istediği başlıkları kullanır, HLS playlist'lerini
yeniden yazar, `.png` kılığındaki segmentleri `.ts` olarak sunar. Segment sunucusu 403 verirse ana playlist adresini
`Referer` yaparak bir kez daha dener (tarayıcıdaki oynatıcının yaptığı gibi). Televizyona yayın, aynı aktarıcının ayrı bir
yerel ağ kapısından gider; bu kapı yalnız geçerli oturum için GET/HEAD/OPTIONS kabul eder.

## Kaynaktan derleme

Gereksinimler: JDK 21 (Gradle toolchain ile kendisi bulur ya da indirir) ve git.

```bash
./gradlew :app:run                  # uygulamayı çalıştır
./gradlew :app:createDistributable  # uygulama imajı: app/build/compose/binaries/main/app
./gradlew :app:packageMsi :app:packageExe        # Windows paketleri
./gradlew :app:packageDeb :app:packageRpm        # Linux paketleri (Linux'ta)
./gradlew :core:test                # birim testleri (ağ/pencere açanlar için -PmanualTests ekleyin)
```

Windows paketleri için önce gömülecek mpv'yi `powershell -File scripts/fetch-mpv.ps1` ile indirin. Sürümler yerelde
`pwsh scripts/release.ps1 -Version <x.y.z> -NotesFile <notlar.md>` ile derlenir, imzalanır ve yayımlanır; uygulama yalnız
geçerli bir `SHA256SUMS.sig` (Ed25519, Base64) taşıyan `SHA256SUMS` ile uyuşan kurulum dosyasını yükler. Sürüm
`gradle.properties` içindeki `seyirVersion` değerindedir.

Her push öncesi testlerin çalışması için depo kancasını bir kez etkinleştirin: `git config core.hooksPath .githooks`
(`scripts/verify.sh` çalışır; Windows'ta aynısını `scripts/verify.ps1` yapar).

### Komut satırı aracı

`cli` modülü eklentileri arayüzsüz yükler; bir eklentiyi denemenin en hızlı yoludur.

```bash
./gradlew :cli:installDist
cli/build/install/cli/bin/cli list                                  # kurulu eklentilerin sağlayıcıları
cli/build/install/cli/bin/cli home   "Kanal D"                     # ana sayfanın ilk sıraları
cli/build/install/cli/bin/cli search DMax usta                      # tek sağlayıcıda arama
cli/build/install/cli/bin/cli load   DMax <dizi-adresi>             # ayrıntı ve bölüm listesi
cli/build/install/cli/bin/cli links  DMax <bölüm-verisi>            # video bağlantıları
cli/build/install/cli/bin/cli probe  "Kanal D" "uzak şehir"         # arama -> ayrıntı -> bağlantı (-> mpv)
cli/build/install/cli/bin/cli fetch  <url> [referer]                # ham sayfa; sitenin gerçekte ne döndürdüğünü görmek için
cli/build/install/cli/bin/cli cast-scan                             # ağdaki Google Cast alıcılarını listele
cli/build/install/cli/bin/cli cs3    <girdi.cs3> <cikti.jar>        # CloudStream .cs3 dosyasını çevir (deneysel)
cli/build/install/cli/bin/cli compat <eklenti.jar|eklenti.cs3>      # statik denetim: Seyir'de çalışır mı? (eklenti kodu çalıştırılmaz)
```

CLI varsayılan olarak uygulamanın kurduğu eklentileri yükler. Bir jar klasörünü yüklemek için
`-Dseyir.plugins=<klasör>` verin (ör. kendi derlediğiniz bir eklenti deposu için `-Dseyir.plugins=<eklenti deposu>/build/repo`). `-Dseyir.play=<sn>` ve
`-Dseyir.mpv=<yol>` ile `probe`, bulunan en iyi üç bağlantıyı mpv'de o kadar saniye sessizce oynatıp `OYNATMA OK` ya da
`OYNATMA HATA` yazar.

Windows'ta `cli.bat` kullanın. Ayarlar `JAVA_OPTS` ile verilir:

| Ayar | Anlamı |
|---|---|
| `-Dseyir.plugins=<klasör>` | Eklenti jar'larının klasörü (varsayılan: uygulamanın eklenti klasörü) |
| `-Dseyir.log=debug` | Eklenti günlüklerini göster (`verbose/debug/info/warn/error`) |
| `-Dseyir.cloudflare=true` | Cloudflare sınamalarını sistem tarayıcısıyla çöz |
| `-Dseyir.fragment=false` | ClientHello parçalamayı kapat |
| `-Dseyir.doh=false` | DoH yerine sistem DNS'i |
| `-Dseyir.proxy=socks5://127.0.0.1:40000` | Vekil (ör. Cloudflare WARP vekil kipi) |
| `-Dseyir.data=<klasör>` | Veri klasörü |
| `-Dseyir.play=<sn>` / `-Dseyir.mpv=<yol>` | `probe` oynatma süresi / mpv yolu |

## Eklenti yazmak

Eklentiler CloudStream eklenti API'sini kullanır; bir CloudStream eklentisi çoğu zaman klasörü kopyalanıp Android'e özgü
kodu çıkarılarak taşınabilir.

- Adım adım taşıma rehberi: [docs/porting-cloudstream-plugins.tr.md](docs/porting-cloudstream-plugins.tr.md)
- Çalışan bir örnekle (Kanal D) gelen, derlemeye hazır başlangıç projesi: [plugin-template/](plugin-template/README.tr.md)

Uyumluluk katmanında eklentilere görünen bir değişiklikten sonra `./gradlew syncPluginTemplate` çalıştırın. API jar'ını
(`seyir-api.jar`), sürüm kataloğunu ve Gradle sarmalayıcısını `plugin-template/` klasörüne, `-PpluginRepoDir=<klasör>`
verilirse kendi eklenti deponuza da kopyalar.

## Proje yapısı

```
core/             eklenti yükleyici, Android/CloudStream taklitleri, ağ, aktarıcı, mpv, Cast, Cloudflare çözücü
app/              Compose Desktop uygulaması (ekranlar, kitaplık, indirme, eklenti yöneticisi)
cli/              komut satırı deneme aracı
plugin-template/  yeni eklentiler için başlangıç projesi (örnek: KanalD)
docs/             taşıma rehberi
packaging/linux/  Linux paketleri için Docker derlemesi
scripts/          fetch-mpv.ps1 (Windows paketine gömülen mpv'yi indirir)
```

## Bilinen kısıtlar

- Eklentiler okudukları web sitelerine bağlıdır. Site değiştiğinde eklenti, yazarı bir güncelleme yayımlayana kadar
  çalışmayabilir; Seyir eklediğiniz depolardaki güncellemeleri kendiliğinden kurar.
- Ağınızdan erişilemeyen sunucular için vekil gerekir (vekili Ayarlar'dan girin).
- Seyir hiçbir içerik ya da site kodu içermez. Eklentilerin neye eriştiği ve o içeriğin lisanslı olup olmadığı
  eklenti yazarının ve kullanıcının sorumluluğundadır; Seyir yalnızca oynatıcıyı ve eklenti sistemini sağlar.

## Emeği geçenler

- [CloudStream](https://github.com/recloudstream/cloudstream) (recloudstream): Seyir'in dayandığı eklenti API'si ve
  `library-jvm`.
- [mpv](https://mpv.io): oynatma.
- [Inter](https://rsms.me/inter/) (SIL OFL 1.1): arayüz yazı tipi.

## Lisans

Seyir, GNU Genel Kamu Lisansı sürüm 3 (GPL-3.0) ile lisanslıdır ([LICENSE](LICENSE) dosyasına bakın). Üçüncü taraf
bileşenler ve lisansları [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) dosyasında listelenir.
