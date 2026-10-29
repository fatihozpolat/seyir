# Seyir oynatıcı arayüzü (mpv yapılandırma klasörü)

Bu klasör, Seyir videoyu açarken mpv'ye `--config-dir` olarak verilir (bkz. `MpvArgs.uiArgs`,
`core/src/main/kotlin/seyir/core/player/MpvLogic.kt`). Seyir ayarlarındaki "Seyir oynatıcı arayüzü" kapalıysa
kullanılmaz. Klasör, uygulama paketinde `<kaynaklar>/mpv-ui` olarak yer alır (`app/resources/common`).

mpv bu klasörü kullanırken kullanıcının kendi `mpv.conf`, betik ve kısayolları okunmaz; böylece arayüz ayarları
bozulmaz. mpv'nin kendi OSC'si `--osc=no` ile kapatılır.

## Gömülü üçüncü taraf bileşenler (sürümler sabittir)

| Bileşen | Sürüm | Lisans | Kaynak |
|---|---|---|---|
| uosc | 5.13.0 (etiket `5.13.0`, `uosc.zip` yayın dosyası) | LGPL-2.1 | https://github.com/tomasklaen/uosc/releases/tag/5.13.0 |
| thumbfast | `0f711de3138c9bd6718209d819ac54022c23ded2` (2026-06-28, `master`; sürüm etiketi yok) | MPL-2.0 | https://github.com/po5/thumbfast/tree/0f711de3138c9bd6718209d819ac54022c23ded2 |
| Inter (Regular, Bold) | 4.1 | OFL-1.1 | https://rsms.me/inter/ |

Lisans metinleri `licenses/` altındadır; ayrıca depo kökündeki `THIRD_PARTY_NOTICES.md` dosyasına işlenmiştir.

uosc ve thumbfast dosyaları değiştirilmeden alınmıştır, tek fark: uosc'nin `bin/` klasörü (ziggy: dosya gezgini ve
kendi kendini güncelleme için ikili dosyalar, yaklaşık 18 MB) Seyir kullanmadığı için dahil edilmemiştir. Bu
yüzden uosc'nin "dosya aç" ve "uosc'yi güncelle" özellikleri çalışmaz; Seyir menüsünde yer almazlar.

Türkçe çeviri uosc'nin kendi `scripts/uosc/intl/tr.json` dosyasıdır.

## Seyir'e ait dosyalar

- `scripts/seyir.lua`: kalite menüsü, sonraki bölüm düğmesi ve Seyir ana menüsü.
  Seyir bölümden bölüme geçerken aynı mpv sürecini ve penceresini kullanır (`loadfile ... replace`, bkz. `MpvLoad`);
  betik `start-file` olayında önceki bölümden kalan sonraki/önceki bölüm başlıklarını ve kalite seçimini sıfırlar.
  Bildirilen başlıklar tanılama için `user-data/seyir/next-title` ve `user-data/seyir/previous-title` özelliklerinde durur.
- `script-opts/uosc.conf`: renkler (`SeyirColors` ile aynı) ve çubuk düzeni.
- `script-opts/thumbfast.conf`: ağ akışlarında önizleme (`network=yes`).
- `input.conf`: kısayollar (S altyazı, A ses, V kalite, N sonraki bölüm, sağ tık menü).

## Seyir ile iletişim

- Dil: `--script-opts-append=uosc-languages=tr|en,seyir-lang=tr|en`.
- Seyir → betik (IPC): `script-message-to seyir seyir-next-info <başlık>`; başlık boşsa sonraki bölüm yoktur.
- Betik → Seyir: `script-message seyir-next`; IPC'de `{"event":"client-message","args":["seyir-next"]}` olarak gelir
  (`SeyirScript.parse`).

## Güncelleme

Yeni uosc sürümü alınırken: `uosc.zip`'i açıp `scripts/uosc` ve `fonts/uosc_*` dosyalarını değiştirin (`bin/`
hariç), `scripts/uosc/intl/tr.json` anahtarlarını denetleyin, `uosc.conf` seçeneklerinin hâlâ geçerli olduğunu
doğrulayın, bu dosyadaki ve `THIRD_PARTY_NOTICES.md` içindeki sürümü güncelleyin. Elle deneme için:
`SEYIR_MANUAL=1` ile `:core:test -PmanualTests --tests '*MpvUiSmokeTest*'`.
