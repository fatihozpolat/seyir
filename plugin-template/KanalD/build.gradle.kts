// CloudStream biçimi: bu dosya çalıştırılmaz, değerleri kök build.gradle.kts okur.
// Sürüm her yayında bir artırılır; Seyir güncellemeyi buna bakarak bulur.
version = 1

cloudstream {
    // Seyir'e özgü, isteğe bağlı: eklenti ekranında görünen ad (Türkçe harf ve boşluk olabilir)
    displayName = "Kanal D (örnek)"
    description = "Kanal D dizileri ve programları: güncel ve arşiv dizilerin bütün bölümleri, 1080p'ye kadar."
    authors = listOf("seyir")
    language = "tr"
    status = 1 // 0: çalışmıyor, 1: çalışıyor, 2: yavaş, 3: deneme
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.kanald.com.tr/favicon.ico"
}
