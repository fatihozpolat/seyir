---
version: 1
slug: "app-src-main-kotlin-seyir-app-ui"
primary_target: "app/src/main/kotlin/seyir/app/ui"
related_targets: []
---

# Seyir masaüstü arayüzü

Kapsam: uygulamanın bütün ekranları (kabuk, Ana sayfa, Ara, Detay, Devam et, Listem, İndirilenler,
Eklentiler, Ayarlar, Kaynaklar penceresi). Kip: Operate.

Kullanıcı loş odada film/dizi arar, kaldığı yerden devam eder, bölüm seçer. Ölçüt: aynı raftaki en iyi
masaüstü izleme uygulamaları (Apple TV uygulaması, Infuse, Netflix masaüstü) kadar temiz ve oturmuş.

## Direction contract

THESIS: Kategori standardı, tam doğrulukla: öne çıkan pano + yatay afiş sıraları. İroni yok, kaçak tuhaflık
yok; özen işçilikte. Gri ekran + tek vurgu tembelliğini reddeder: kalite tipografi, ritim ve görselde.

OWN-WORLD: Neredeyse siyah zemin #0F0F12, yüzey #1C1C21, yükselti #26262C, %7 beyaz kıl çizgileri;
ikincil metin #8B8D98. Sıcak kehribar #E0A33A yalnız birincil düğmede, ilerleme çubuklarında, seçimde.
Inter (paketle gelir), 800 ağırlıklı sıkı başlıklar. Afiş 2:3, 10 köşe, ince iç çizgi, yumuşak gölge.
88dp sol ray, seçili öğe yüzey dolgulu.

STORY: Açınca devam edeceği şey ve sitenin vitrini önünde; bir tıkla detaya, bir tıkla oynatmaya.
Sağlayıcı her yerde görünür ama sessiz.

FIRST VIEWPORT: Solda 88dp ray (logo üstte, Ayarlar altta). Ana içerikte üstte saydam araç çubuğu
(ekran adı solda, eklenti seçici + yenile sağda). Altında ~430dp pano: öne çıkan yapımın görseli
zemine eriyen gradyanla, sol altta 56sp başlık, künye satırı, özet, kehribar birincil düğme + ikincil
"Listeme ekle". Altında "Devam et" sırası ilerleme çubuklarıyla, sonra kategori sıraları.

FORM: canon (kategori standardı), listede canon kapısı; seed:5429ada3e37b78eb

FINISH: Her durum (yükleniyor, boş, hata, üzerine gelme, odak) aynı dille çizilmiş; temalı kaydırma
çubukları, seçim rengi, imleç; hiçbir Material varsayılanı kalmamış.
