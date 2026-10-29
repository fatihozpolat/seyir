package seyir.app

import seyir.app.i18n.EnglishStrings
import seyir.app.i18n.TurkishStrings
import kotlin.test.Test
import kotlin.test.assertNotEquals

class StringsSmokeTest {
    // Uygulama modülündeki testlerin derlenip çalıştığını doğrular
    @Test
    fun languagesDiffer() {
        assertNotEquals(TurkishStrings.cancel, EnglishStrings.cancel)
    }
}
