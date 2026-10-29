package seyir.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateTest {
    @Test
    fun comparesVersions() {
        assertTrue(AppUpdate.isNewer("v1.0.1", "1.0.0"))
        assertTrue(AppUpdate.isNewer("1.10.0", "1.9.9"))
        assertTrue(AppUpdate.isNewer("2", "1.9"))
        assertFalse(AppUpdate.isNewer("1.0", "1.0.0"))
        assertFalse(AppUpdate.isNewer("v1.0.0", "1.0.0"))
        assertFalse(AppUpdate.isNewer("1.0.0-beta", "1.0.0"))
        assertFalse(AppUpdate.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun comparesPrereleases() {
        // Ön sürüm kullanan, aynı numaralı son sürümü ve sonraki ön sürümleri görür
        assertTrue(AppUpdate.isNewer("1.0.0", "1.0.0-beta.4"))
        assertTrue(AppUpdate.isNewer("v1.0.0-beta.5", "1.0.0-beta.4"))
        assertTrue(AppUpdate.isNewer("1.0.0-beta.10", "1.0.0-beta.9"))
        assertTrue(AppUpdate.isNewer("1.0.0-rc.1", "1.0.0-beta.9"))
        assertTrue(AppUpdate.isNewer("1.0.0-beta.1", "1.0.0-beta"))
        assertFalse(AppUpdate.isNewer("1.0.0-beta.4", "1.0.0-beta.4"))
        assertFalse(AppUpdate.isNewer("1.0.0-beta.3", "1.0.0-beta.4"))
        assertTrue(AppUpdate.isPrerelease("1.0.0-beta.4"))
        assertFalse(AppUpdate.isPrerelease("1.0.0+build.7"))
    }

    @Test
    fun picksNewestReleaseFromList() {
        val json = jacksonObjectMapper().readTree(
            """[{"tag_name":"v1.0.0-beta.6","draft":true},
                {"tag_name":"v1.0.0-beta.5","prerelease":true},
                {"tag_name":"v0.9.0"},
                {"tag_name":"v1.0.0-beta.4","prerelease":true}]"""
        )
        assertEquals("1.0.0-beta.5", AppUpdate.newestRelease(json, includePrereleases = true)?.version)
        assertEquals("0.9.0", AppUpdate.newestRelease(json, includePrereleases = false)?.version)
    }

    @Test
    fun parsesReleaseAndPicksInstaller() {
        val json = jacksonObjectMapper().readTree(
            """{"tag_name":"v1.1.0","name":"Seyir 1.1.0","body":"Yenilikler","html_url":"https://github.com/o/r/releases/tag/v1.1.0",
               "assets":[{"name":"seyir_1.1.0_amd64.deb","url":"https://api.github.com/a/1"},
                         {"name":"Seyir-1.1.0.exe","url":"https://api.github.com/a/2"},
                         {"name":"Seyir-1.1.0.msi","url":"https://api.github.com/a/3"}]}"""
        )
        val release = AppUpdate.parseRelease(json)!!
        assertEquals("1.1.0", release.version)
        assertEquals("Seyir-1.1.0.msi", AppUpdate.installerFor(release, windows = true)?.key)
        assertNull(AppUpdate.installerFor(release, windows = false))
        assertNull(AppUpdate.parseRelease(jacksonObjectMapper().readTree("""{"message":"Not Found"}""")))
    }
}
