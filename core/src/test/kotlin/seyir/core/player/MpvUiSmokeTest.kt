package seyir.core.player

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seyir oynatıcı arayüzüyle gerçek mpv duman testi (görüntüsüz: `--vo=null --ao=null`). Ağ gerektirir,
 * bu yüzden yalnızca `SEYIR_MANUAL=1` ile çalışır. mpv günlüğü `build/mpv-ui-smoke.log` dosyasına yazılır.
 *
 * Doğrulananlar: betikler hatasız yüklenir, sonraki bölüm bilgisi betiğe ulaşır, "sonraki bölüm" düğmesinin
 * iletisi (`script-message seyir-next`) IPC'den `onNextRequested` olarak gelir, kalite değişimi oturumu
 * kapatmaz (dosya bitti/kapandı sanılmaz).
 *
 * Çalıştırma: `$env:SEYIR_MANUAL=1; .\gradlew.bat :core:test --tests '*MpvUiSmokeTest*' --console=plain -i`
 */
@Tag("manual")
class MpvUiSmokeTest {
    @Test
    fun `ui scripts load, next button reaches the session and quality switch keeps the session alive`() {
        assumeTrue(System.getenv("SEYIR_MANUAL") == "1", "elle çalıştırılan test (SEYIR_MANUAL=1)")
        val bundled = listOf("../app/resources/windows/mpv/mpv.exe", "app/resources/windows/mpv/mpv.exe")
            .map(::File).firstOrNull { it.isFile }?.absolutePath
        val command = MpvLocator.find(System.getenv("SEYIR_MPV") ?: bundled)
        assumeTrue(command != null, "mpv bulunamadı")
        val ui = MpvUi.locate(workingDir = File("..").absoluteFile.normalize().path)
        assumeTrue(ui != null, "arayüz klasörü bulunamadı")
        assumeTrue(MpvUi.commandSupportsUi(command!!), "mpv arayüz için eski")

        val out = File("build").apply { mkdirs() }
        val log = File(out, "mpv-ui-smoke.log").also { it.delete() }
        val report = File(out, "mpv-ui-smoke-report.txt").also { it.delete() }
        // Düğmeye basmayı ve kalite değişimini taklit eden betik: düğmelerin çalıştırdığı komutların aynısını verir
        val driver = File(out, "mpv-ui-smoke-driver.lua").apply {
            writeText(
                """
                local function out(s) local f = io.open([[${report.absolutePath}]], 'a'); f:write(s .. '\n'); f:close() end
                local starts = 0
                mp.register_event('start-file', function() starts = starts + 1 end)
                mp.register_event('end-file', function(e) out('end-file ' .. tostring(e.reason)) end)
                mp.register_event('file-loaded', function()
                  mp.add_timeout(4, function()
                    for _, t in ipairs(mp.get_property_native('track-list')) do
                      out(string.format('track %s id=%s selected=%s %sx%s', t.type, t.id, tostring(t.selected), tostring(t['demux-w']), tostring(t['demux-h'])))
                    end
                    out('editions=' .. tostring(mp.get_property('editions')) .. ' current=' .. tostring(mp.get_property('current-edition')))
                    out('quality-before time=' .. tostring(mp.get_property('time-pos')))
                    mp.commandv('script-message-to', 'seyir', 'quality-select', '480')
                  end)
                  mp.add_timeout(9, function()
                    out('quality-after current=' .. tostring(mp.get_property('current-edition')) .. ' time=' .. tostring(mp.get_property('time-pos')) .. ' starts=' .. starts)
                    mp.commandv('script-message-to', 'seyir', 'next')
                  end)
                end)
                """.trimIndent(),
            )
        }

        val nextRequested = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val listener = object : PlaybackListener {
            override fun onNextRequested() = nextRequested.countDown()
            override fun onExit(position: Double?, duration: Double?, reachedEnd: Boolean, error: String?) = exited.countDown()
        }
        val request = PlayRequest(
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            title = "Seyir arayüz denemesi",
            startSeconds = 60.0,
            ui = PlayerUi(ui!!.absolutePath, "tr"),
            extraArgs = listOf(
                "--vo=null", "--ao=null", "--msg-level=all=v", "--log-file=${log.absolutePath}", "--script=${driver.absolutePath}",
            ),
        )
        val session = MpvPlayer(command).play(request, listener)
        try {
            session.setNextEpisode("S1 B2 — Deneme, başlık")
            assertTrue(nextRequested.await(60, TimeUnit.SECONDS), "sonraki bölüm iletisi IPC'den gelmedi")
            assertEquals(1, exited.count, "kalite değişimi oturumu kapatmamalı")
        } finally {
            session.stop()
            exited.await(15, TimeUnit.SECONDS)
        }
        val text = log.readText()
        println(report.takeIf { it.exists() }?.readText() ?: "(rapor yok)")
        val problems = text.lines().filter { Regex("""^\[\s*[\d.]+]\[e]\[(seyir|uosc|thumbfast)]""").containsMatchIn(it) || "Can't load unknown script" in it }
        assertTrue(problems.isEmpty(), "betik hataları:\n" + problems.joinToString("\n"))
        assertTrue("[seyir] loading file" in text.replace("][d][", "][").replace(Regex("""\[\s*[\d.]+]\[\w]"""), ""), "seyir.lua yüklenmedi")
        val reportText = report.readText()
        assertFalse("end-file" in reportText.substringBefore("quality-after"), "kalite değişimi end-file üretmemeli:\n$reportText")
    }
}
