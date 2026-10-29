# Product

<!-- impeccable:product-schema 1 -->

## Platform

desktop (Compose Desktop; Windows 10/11 ve Linux). Web değil, mobil değil; fare ve klavyeyle kullanılan
yeniden boyutlandırılabilir tek pencere. Oynatma ayrı bir mpv penceresinde olur.

## Users

Türkiye'de evde, kendi bilgisayarında film/dizi izleyen kişi (çıkarım: depo ve oturumdan; görüşme
yapılmadı, kullanıcı "karar ver, sorma" tercihini belirtmişti). Çoğunlukla akşam, loş odada, masaüstü ya
da dizüstü ekranında; bazen televizyona bağlı bilgisayarda. İşi: izleyeceği şeyi bulmak, kaldığı yerden
devam etmek, bir bölüme hızla geçmek.

## Product Purpose

Eklenti tabanlı masaüstü medya uygulaması: uygulamada site kodu yoktur, içerik CloudStream uyumlu
eklentilerden gelir (içerik, kullanıcının eklediği CloudStream uyumlu eklenti depolarından ve .cs3
dosyalarından gelir). Bir arayüzde birçok siteyi birlikte aramak, ana sayfalarını gezmek, detay/bölüm listesini
görmek ve mpv ile oynatmak. Başarı: aradığını birkaç saniyede bulup oynatabilmek; siteler bozulsa da
uygulamanın çökmeden sakin kalması.

## Positioning

Tek bir sitenin değil, kurulan eklentilerin (sitelerin) ortak vitrini: her içerik bir "sağlayıcı" adıyla gelir.
Şifreli DNS ve TLS parçalamayla ağ kısıtlarına dayanır, Cloudflare'li sitelere sistem tarayıcısıyla ulaşır; kayıt, reklam, hesap yok.

## Operating Context

- Ekranlar: Ana sayfa (seçili eklentinin kategori şeritleri), Ara (bütün eklentilerde, sağlayıcıya göre
  gruplanmış), Detay (poster, künye, özet, sezon/bölüm listesi, benzerler), Bağlantılar penceresi (kalite,
  kaynak), Devam et (geçmiş), Listem, İndirilenler, Eklentiler (aç/kapat, durum, alan adı), Ayarlar.
- İçerik görselleri sitelerden gelir: dikey posterler (2:3), bazen yatay arka plan; kalite ve oran
  değişken, bazıları eksik. Metinler Türkçe, bazen Korece/Japonca başlıklar.
- Durum çubuğu hataları ve Cloudflare beklemesini gösterir.

## Capabilities and Constraints

- Kotlin + Compose Desktop (Material3), Coil ile görsel yükleme. Arayüz `app/src/main/kotlin/seyir/app/ui`.
- Oynatıcı mpv (gömülü değil, ayrı pencere); arayüz oynatma kontrolü çizmez.
- Geliştirme senaryosu `SEYIR_SCRIPT` ile ekranlar fareye dokunmadan gezilebilir.
- Yazı tipi dosyası paketle gelebilir (kaynak klasörü), ağdan font yüklenmez.

## Brand Commitments

- Ad: "Seyir". Simge `app/icons/seyir.svg` (S biçimli boşlukla bölünmüş kehribar oynat üçgeni).
- Arayüz Türkçe ve İngilizce (Ayarlar → Dil); kod İngilizce.
- Görsel yön: kategori standardı (öne çıkan pano + yatay afiş sıraları), düz ve tam doğrulukla
  (29.10.2026'da kullanıcı seçti). Ölçüt: Apple TV uygulaması, Infuse, Netflix masaüstü. Koyu zemin,
  tek sıcak kehribar vurgu, paketle gelen Inter yazı tipi.

## Evidence on Hand

- Gerçek içerik eklentilerden canlı gelir; demo veri gerekmez. Ekran görüntüsü için `SEYIR_DATA` ile
  geçmişi dolu bir veri klasörü kullanılabilir.
- Kullanıcı yorumu yok, ölçüm yok; uydurulmamalı.

## Product Principles

1. İçerik önde: posterler ve başlıklar ekranın büyük kısmını taşır, arayüz geri çekilir.
2. Kaldığın yer her zaman bir tık uzakta (devam et, son bölüm).
3. Sağlayıcı her zaman görünür ama ikinci planda: içerik hangi siteden geliyor bilinir.
4. Bozuk site uygulamayı bozmaz: hata, boş ve yükleniyor durumları sakin ve açıklayıcı.

## Accessibility & Inclusion

Karanlık odada uzun süre bakılacak; parlak geniş beyaz alanlardan kaçınılır, metin kontrastı WCAG AA.
Klavyeyle gezinme (Tab, Enter, Esc ile geri) bozulmamalı.
