package seyir.app

import seyir.app.data.AppSettings
import seyir.app.data.SettingsStore
import seyir.core.hls.QualityPreference
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultQualitySettingTest {
    @Test
    fun `varsayilan kalite en yuksektir`() {
        assertEquals(QualityPreference.HIGHEST, QualityPreference.fromId(AppSettings().defaultQuality))
    }

    @Test
    fun `kalite tercihi kaydedilir ve eski dosyada bos kalirsa en yuksek olur`() {
        val dir = Files.createTempDirectory("seyir-quality-test").toFile()
        try {
            val file = File(dir, "settings.json")
            // Bu alan olmadan yazılmış eski bir dosya
            file.writeText("""{"language":"tr"}""")
            assertEquals("highest", SettingsStore(file, null).current.defaultQuality)

            val store = SettingsStore(file, null)
            store.update { it.copy(defaultQuality = QualityPreference.P720.id) }
            assertEquals(QualityPreference.P720, QualityPreference.fromId(SettingsStore(file, null).current.defaultQuality))
        } finally {
            dir.deleteRecursively()
        }
    }
}
