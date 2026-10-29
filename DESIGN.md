---
name: Seyir
description: Loş odada izlenen film ve dizi için, neredeyse siyah zeminde tek kehribar vurgulu masaüstü izleme uygulaması.
colors:
  background: "#0F0F12"
  surface: "#1C1C21"
  surface-high: "#26262C"
  surface-hover: "#2E2E35"
  outline: "#34343C"
  hairline: "#FFFFFF12"
  text: "#F4F4F6"
  text-secondary: "#C5C6CD"
  text-muted: "#8B8D98"
  accent: "#E0A33A"
  accent-hover: "#EBB14C"
  accent-pressed: "#C98A26"
  on-accent: "#1A1305"
  accent-container: "#3A2A10"
  success: "#4CC38A"
  danger: "#FF7A70"
typography:
  display-large:
    fontFamily: "Inter Display, sans-serif"
    fontSize: "56px"
    fontWeight: 800
    lineHeight: "60px"
    letterSpacing: "-1.9px"
  display-small:
    fontFamily: "Inter Display, sans-serif"
    fontSize: "42px"
    fontWeight: 800
    lineHeight: "46px"
    letterSpacing: "-1.3px"
  headline-medium:
    fontFamily: "Inter Display, sans-serif"
    fontSize: "30px"
    fontWeight: 700
    lineHeight: "36px"
    letterSpacing: "-0.6px"
  headline-small:
    fontFamily: "Inter Display, sans-serif"
    fontSize: "24px"
    fontWeight: 700
    lineHeight: "30px"
    letterSpacing: "-0.4px"
  title-large:
    fontFamily: "Inter, sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: "26px"
    letterSpacing: "-0.25px"
  title-medium:
    fontFamily: "Inter, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: "24px"
    letterSpacing: "-0.15px"
  title-small:
    fontFamily: "Inter, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
  body-large:
    fontFamily: "Inter, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
  body-medium:
    fontFamily: "Inter, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
  body-small:
    fontFamily: "Inter, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "18px"
  label-large:
    fontFamily: "Inter, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
  label-medium:
    fontFamily: "Inter, sans-serif"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: "18px"
  label-small:
    fontFamily: "Inter, sans-serif"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: "16px"
    letterSpacing: "0.1px"
rounded:
  xs: "6px"
  sm: "8px"
  poster: "10px"
  md: "12px"
  lg: "16px"
  dialog: "18px"
  xl: "20px"
  full: "9999px"
spacing:
  hairline: "2px"
  xs: "4px"
  sm: "8px"
  md: "14px"
  lg: "20px"
  row-gap: "18px"
  section: "28px"
  gutter: "40px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    typography: "{typography.label-large}"
    rounded: "{rounded.poster}"
    height: "40px"
    padding: "0 16px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-primary-pressed:
    backgroundColor: "{colors.accent-pressed}"
  button-primary-large:
    height: "48px"
    padding: "0 22px"
  button-secondary:
    backgroundColor: "{colors.surface-high}"
    textColor: "{colors.text}"
    typography: "{typography.label-large}"
    rounded: "{rounded.poster}"
    height: "40px"
    padding: "0 16px"
  button-secondary-hover:
    backgroundColor: "{colors.surface-hover}"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.text-secondary}"
    typography: "{typography.label-large}"
    rounded: "{rounded.poster}"
    height: "40px"
    padding: "0 16px"
  button-quiet-hover:
    backgroundColor: "{colors.surface-high}"
    textColor: "{colors.text}"
  icon-button:
    backgroundColor: "transparent"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.full}"
    size: "40px"
  icon-button-hover:
    backgroundColor: "{colors.surface-hover}"
    textColor: "{colors.text}"
  text-field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.body-medium}"
    rounded: "{rounded.poster}"
    height: "42px"
    padding: "0 14px"
  text-field-large:
    typography: "{typography.title-medium}"
    rounded: "14px"
    height: "56px"
    padding: "0 18px"
  chip:
    backgroundColor: "{colors.surface-high}"
    textColor: "{colors.text-secondary}"
    typography: "{typography.label-small}"
    rounded: "{rounded.xs}"
    padding: "3px 8px"
  chip-highlighted:
    textColor: "{colors.accent}"
  poster-card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.poster}"
    width: "160px"
  nav-rail:
    backgroundColor: "{colors.background}"
    width: "88px"
  nav-rail-item-selected:
    backgroundColor: "{colors.surface-high}"
    textColor: "{colors.text}"
    rounded: "{rounded.md}"
    size: "72px"
  status-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    height: "52px"
---

# Design System: Seyir

## Overview

**Creative North Star: "Karartılmış Salon"**

Seyir, ışıkları kısılmış bir salonun masaüstü karşılığıdır. Zemin neredeyse siyahtır, arayüz geri çekilir, afişler ve arka plan görselleri ışığı taşır. Tek sıcak renk, kehribar, perdeyi aydınlatan projektör gibi yalnız eyleme, ilerlemeye ve seçime düşer. Düzen kategorinin bilinen kalıbını izler: öne çıkan pano ve altında yatay afiş sıraları. Özen kalıpta değil işçiliktedir: sıkı başlıklar, tutarlı 40px kenar çizgisi, her durumun aynı dille çizilmesi.

Yalnız koyu tema vardır; açık tema yoktur çünkü kullanım sahnesi loş odadır. Yüzeyler gölgeyle değil, birbirinden birkaç ton açık zeminlerle ve %7 beyaz kıl çizgileriyle ayrışır. Material 3 bileşenlerinin varsayılanları kullanılmaz; tema yalnız renk, yazı ve şekil kaynağıdır, bütün etkileşimli parçalar elle çizilmiştir.

**Key Characteristics:**
- Neredeyse siyah zemin, tonal yüzey katmanları, kıl çizgisi sınırlar.
- Tek vurgu: kehribar. Ekranda nadirdir.
- Başlıklarda sıkı Inter Display (800/700), gövdede Inter.
- 2:3 afiş, 10px köşe, üzerine gelince hafif büyüme.
- Klavye odağı kehribar halkayla, fare tıklamasında halka yok.
- Beş durum (yükleniyor, boş, hata, hover, odak) tek dille çizilir.

## Colors

Soğuk-nötr, neredeyse siyah bir zemin üzerinde tek sıcak kehribar; durum renkleri (yeşil, mercan kırmızısı) yalnız durum bildirir.

### Primary
- **Projektör Kehribarı** (`accent`): Birincil düğme dolgusu, ilerleme çubuğu dolgusu, seçili ray simgesi, odak halkası, imleç ve metin seçimi (%32 saydamlık), odaktaki alan çerçevesi (%85). Vurgulu chip'te %16 saydam zemin ve kehribar metin. Üzerindeki metin `on-accent` (koyu kahve-siyah) olur.
- **Kehribar Hover / Basılı** (`accent-hover`, `accent-pressed`): Yalnız birincil düğmenin durumları. `accent-hover` kodda tek seferlik sabit olarak yaşar; sistem değeri olarak kaydedilmiştir.
- **Kehribar Kap** (`accent-container`): Yalnız Material renk şemasının `primaryContainer` yuvasında; ekranlarda doğrudan kullanıldığı görülmedi.

### Neutral
- **Salon Karanlığı** (`background`): Uygulama zemini, ray, arka plan gradyanlarının erime rengi.
- **Koltuk Yüzeyi** (`surface`): Metin kutusu, durum şeridi, afiş yer tutucusu, durum simgesi dairesi.
- **Yükselti** (`surface-high`): İkincil düğme, chip, seçili ray öğesi, sessiz düğme hover'ı.
- **Hover Yüzeyi** (`surface-hover`): Hover'daki ikincil düğme ve simge düğmesi, ilerleme çubuğu izi.
- **Anahat** (`outline`): Devre dışı simge, yer tutucu simgesi, hover'daki metin kutusu çerçevesi.
- **Kıl Çizgisi** (`hairline`, %7 beyaz): Sınırlar, ray ve durum şeridi ayırıcıları, afiş iç çizgisi.
- **Metin** (`text`, `text-secondary`, `text-muted`): Birincil, ikincil ve sessiz metin. Sağlayıcı, künye ve açıklama sessiz kalır.

### Status
- **Çalışıyor Yeşili** (`success`) ve **Hata Mercanı** (`danger`): Durum noktası, hata paneli simgesi, durum şeridi hata simgesi. Başka bir yerde kullanılmaz.

### Named Rules
**The One Warm Light Rule.** Kehribar yalnız eylemde (birincil düğme), ilerlemede (çubuk) ve seçimde/odakta görünür. Süs, başlık veya arka plan rengi olarak kullanılmaz.

**The Tonal Step Rule.** Yüzey sırası `background` < `surface` < `surface-high` < `surface-hover`; bir katman ancak bir üstteki basamakla ayrışır, araya yeni gri eklenmez.

## Typography

**Display Font:** Inter Display (800/700/600; sıkı kesimli görüntü boyu, paketle gelir)
**Body Font:** Inter 4.1 (400/500/600/700; paketle gelir, OFL)

**Character:** Başlıklar iri, ağır ve negatif harf aralıklıdır; gövde sakin ve okunaklıdır. İki aile aynı tasarımın iki optik boyudur, kontrast ağırlık ve boyla kurulur.

### Hierarchy
- **Display Large** (800, 56/60, -1.9): Ana sayfa pano başlığı.
- **Display Small** (800, 42/46, -1.3): Detay ekranı başlığı.
- **Headline Medium** (700, 30/36, -0.6): Ekran başlığı (`ScreenHeader`).
- **Headline Small** (700, 24/30, -0.4): İkincil büyük başlıklar.
- **Title Large / Medium / Small** (600; 20/26, 17/24, 14/20): Sıra başlığı ve durum paneli başlığı `title-medium`; iç bölüm başlıkları.
- **Body Large / Medium / Small** (400; 16/24, 14/20, 13/18): Özet, metin kutusu, afiş kartı başlığı (`body-medium`, 500 ağırlık).
- **Label Large / Medium / Small** (600/500/500; 14/20, 13/18, 12/16): Düğme metni, künye satırı ve sayaçlar, chip ve afiş alt satırı. Ray etiketi `label-small` 10.5px, harf aralığı 0.

### Named Rules
**The Display Only For Headlines Rule.** Inter Display yalnız 24px ve üzeri başlıklarda kullanılır; küçük metin her zaman Inter'dir.

**The Quiet Metadata Rule.** Künye satırı `label-medium`, parçalar `  ·  ` ile ayrılır, boş parça atlanır; ikincil bilgi büyütülmez, renkle geri çekilir.

## Layout

Sol tarafta 88px sabit ray, sağında kaydırılan ana içerik, en altta gerektiğinde beliren 52px durum şeridi. Bütün başlıklar, sıralar ve listeler aynı 40px sayfa kenar çizgisine (`PageGutter`) oturur; sıralardaki ok düğmeleri sağda 8px daha içeri girer ki optik kenar tutsun. Ekran başlığı üstten 36px, alttan 20px boşluk alır. Yatay afiş sıraları arasında 28px, başlıkla afiş arasında 14px, afişler arasında 18px boşluk vardır. Afiş kartı varsayılan 160px genişliğindedir, alt satırla arası 10px'tir. Sıra oklarla 520px adımlarla kaydırılır. Pencere varsayılanı 1440x900. Ritim 2/4/8/14/20/28/40 basamaklarında akar; tek bir ölçek ızgarası değil, tekrar eden değerlerdir.

Pano ve detay arka planı görseli sol ve alttan zemine eritilir (yatay gradyan 0 → %28 → %60 → %85, dikey gradyan altta tam zemin); yatay görsel yoksa afiş 56px bulanıklaştırılıp %50 saydamlıkla kullanılır.

### Named Rules
**The Single Gutter Rule.** Her ekranın sol kenarı 40px'tir. Yeni bir yatay hizalama çizgisi eklenmez.

## Elevation & Depth

Hibrit ama ağırlıklı olarak tonal: yüzeyler katman tonuyla ve kıl çizgisiyle ayrışır, gölge yalnız iki yerde vardır. Afiş kartı yumuşak, siyah, dağınık bir gölge taşır; üzerine gelince derinleşir. Büyük birincil düğme kehribar renkli yumuşak bir ışıma bırakır. Sert, ofsetli gölge yoktur.

### Shadow Vocabulary
- **Afiş gölgesi** (`elevation 8dp`, siyah ortam ve nokta rengi): Afiş kartı hareketsizken.
- **Afiş gölgesi, hover** (`elevation 18dp`, siyah): Fare üzerindeyken, kartla birlikte 1.035 ölçek.
- **Kehribar ışıma** (`elevation 14dp`, kehribar ortam ve nokta rengi): Yalnız büyük birincil düğme (pano eylemi).

### Named Rules
**The Flat Surface Rule.** Düğme, metin kutusu, chip, ray ve şerit düzdür; ayrım ton ve kıl çizgisiyledir. Gölge yalnız görsele (afiş) ve tek pano eylemine aittir.

## Shapes

Yumuşak ama keskinliği koruyan yuvarlak köşe dili. Afiş ve düğme ailesi 10px, ray öğesi ve kartlar 12px, chip 6px, iletişim penceresi 18px. Simge düğmesi, durum noktası ve ilerleme çubuğu tam yuvarlaktır. İlerleme çubuğu 4px yüksekliğinde, yuvarlak uçludur. Afişin üzerinde 1px iç çizgi bulunur (hareketsizken kıl çizgisi, hover'da %28 beyaz). Kaydırma çubuğu 8px, 4px köşeli, %10 beyaz, hover'da %28 beyaz.

## Components

Rafine ve sakin: hareket kısa (140-220ms renk geçişleri), hiçbir bileşen sıçramaz.

### Buttons
- **Shape:** 10px köşe; 40px yükseklik (büyük 48px), yatay dolgu 16px (büyük 22px).
- **Primary:** Kehribar dolgu, `on-accent` metin. Büyük hâlinde kehribar ışıma. Devre dışıyken nötr ikincil görünüme döner; soluk kehribar yapılmaz.
- **Secondary:** `surface-high` dolgu, 1px kıl çizgisi, `text` metin; hover `surface-hover`, basılı `surface`.
- **Quiet:** Şeffaf, `text-secondary` metin; hover'da `surface-high`.
- **Hover / Focus:** Renk geçişi 140ms. Klavye odağında 2px kehribar halka; fareyle tıklamada halka yok. Devre dışı: %45 saydamlık. Meşgul: 16px dönen gösterge.
- **Icon button:** Yuvarlak, arka plansız, simge boyutu düğmenin %55'i; hover'da `surface-hover`.

### Chips
- **Style:** `surface-high` zemin, `text-secondary` `label-small` metin, 6px köşe, 8x3px dolgu.
- **State:** Vurgulu hâl kehribar %16 zemin ve kehribar metin.

### Cards / Containers
- **Poster card:** 2:3 görsel, 10px köşe, iç çizgi, alt başlık iki satıra kadar `body-medium` 500 (hover'da `text`, aksi `text-secondary`), altında `label-small` sessiz alt satır. Hover'da 1.035 ölçek, 220ms. İlerleme varsa görselin altında 44px siyah gradyan ve 10px içerden çubuk. Görsel yoksa ya da yüklenemezse `surface-high` → `surface` gradyanı üzerinde `outline` renkli film simgesi.
- **Backdrop:** Pano/detay arka planı, sol ve alttan zemine eriyen gradyanlar.

### Inputs / Fields
- **Style:** `surface` zemin, 1px çerçeve (hareketsiz kıl çizgisi, hover `outline`), 10px köşe, 42px yükseklik. Büyük varyant 56px, 14px köşe, `title-medium` metin ve dolu iken temizle düğmesi.
- **Focus:** Çerçeve %85 kehribar, imleç kehribar. Yer tutucu `text-muted`.

### Navigation
- **Rail:** 88px, `background` zemini, sağ kenarda kıl çizgisi; üstte 34px logo ve ad, ortada ana ekranlar, en altta Ayarlar. Öğe 72x60px, 12px köşe, 22px simge ve altında 10.5px etiket. Seçili: `surface-high` dolgu, `text` etiket, kehribar simge. Hover: `surface` dolgu. Hareketsiz: `text-muted`.

### Durum bildirimi (StatePanel)
Ortalanmış 64px daire içinde simge (`surface` dolgu, kıl çizgisi), altında `title-medium` başlık ve en çok 460px genişlikte sessiz açıklama; isteğe bağlı eylem. Hata hâli simgeyi `danger` yapar ve "Tekrar dene" düğmesi sunar. Yükleniyor: 28px kehribar gösterge.

### Durum şeridi
52px, `surface` zemin, üstte kıl çizgisi; çalan içerik (kehribar oynat simgesi, "Oynatılıyor" sessiz etiket, başlık, sessiz "Durdur" düğmesi) ya da hata mesajı gösterir.

## Do's and Don'ts

### Do:
- **Do** kehribarı yalnız birincil eylem, ilerleme ve seçim/odak için kullan.
- **Do** her yeni ekranı 40px sayfa kenarına ve `ScreenHeader` / `RowSection` iskeletine oturt.
- **Do** yeni yüzeyi `background`, `surface`, `surface-high`, `surface-hover` basamaklarından ve 1px `hairline` ile ayır.
- **Do** etkileşimli her öğeye klavye odağında 2px (afişte 3px) kehribar halka ver.
- **Do** yükleniyor, boş, hata durumlarını `CenteredLoading`, `EmptyPanel`, `ErrorPanel` ile çiz.
- **Do** görsel gelen yerde afişi 2:3 ve 10px köşeyle, yer tutucu gradyanıyla birlikte kullan.

### Don't:
- **Don't** Material 3 varsayılan bileşenlerini (düğme, metin alanı, dalga efekti) çıplak kullanma; tema yalnız token kaynağıdır.
- **Don't** düz bir griye tek vurguyla yetinen, ritmi ve tipografisi çalışılmamış ekran çizme.
- **Don't** sert ofsetli gölge ya da kehribar dışında renkli ışıma ekleme.
- **Don't** açık tema ya da ikinci vurgu rengi ekleme.
- **Don't** 24px altı metinde Inter Display kullanma.

---

Not canonized: Bu geçişte kayda alınmayan kusur yok; kaydedilmeyen tek şey `accent-container`'ın ekranlarda kullanılmayan Material yuvası olması ve `Color(0xFFEBB14C)` hover değerinin Theme.kt dışında, Components.kt içinde sabit yazılı olmasıdır (token olarak kaydedildi, kodda `SeyirColors`'a taşınması bekliyor).
