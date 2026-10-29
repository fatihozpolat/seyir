package seyir.app.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** Arayüz dilleri; [code] ayar dosyasındaki değerdir. */
enum class Language(val code: String) {
    TR("tr"),
    EN("en"),
}

/**
 * Geçerli arayüz dili. Composable olmayan kodlar (servis iletileri, durum metinleri) [current]
 * değerini kullanır; composable'larda ise [LocalStrings] okunur.
 */
object I18n {
    /** Ayar değeri: sistem dilini izlemek için "system". */
    const val SYSTEM = "system"

    /** Pencere kurulurken ve dil ayarı değiştiğinde güncellenir. */
    @Volatile
    var current: UiStrings = resolve(SYSTEM)

    /** Ayar değerini ("system" | "tr" | "en") metin takımına çevirir; "system" Türkçe sistemde Türkçe, diğerlerinde İngilizcedir. */
    fun resolve(setting: String): UiStrings = when (setting) {
        Language.TR.code -> TurkishStrings
        Language.EN.code -> EnglishStrings
        else -> if (Locale.getDefault().language == Language.TR.code) TurkishStrings else EnglishStrings
    }
}

/** Composable'ların kullandığı metin takımı; `Main.kt` ayara göre sağlar. */
val LocalStrings = staticCompositionLocalOf<UiStrings> { TurkishStrings }
