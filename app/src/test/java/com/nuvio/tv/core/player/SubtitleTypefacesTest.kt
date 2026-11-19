package com.nuvio.tv.core.player

import java.awt.Font
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 135: the ExoPlayer text-subtitle typeface falls back only when the system one lacks Vietnamese. */
class SubtitleTypefacesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val probes = SUBTITLE_COVERAGE_PROBES.toSet()

    @Test
    fun `system typeface kept when it covers every probe and the bundled one is never loaded`() {
        var loads = 0
        val chosen = chooseSubtitleTypeface("system", covers = { _, _ -> true }, loadBundled = { loads++; "bundled" })
        assertEquals("system", chosen)
        assertEquals(0, loads)
    }

    @Test
    fun `bundled typeface used when any one probe is missing`() {
        for (missing in probes) {
            val chosen = chooseSubtitleTypeface(
                "system",
                covers = { _, text -> text != missing },
                loadBundled = { "bundled" }
            )
            assertEquals("missing $missing", "bundled", chosen)
        }
    }

    @Test
    fun `system typeface kept when the bundled one cannot load`() {
        val chosen = chooseSubtitleTypeface("system", covers = { _, _ -> false }, loadBundled = { null })
        assertEquals("system", chosen)
    }

    @Test
    fun `probes are single characters outside Latin-1 that the bundled fonts cover`() {
        for (probe in SUBTITLE_COVERAGE_PROBES) {
            assertEquals(probe, 1, probe.codePointCount(0, probe.length))
            assertTrue(probe, probe.codePointAt(0) > 0xFF)
        }
        for ((name, _) in LIBASS_FALLBACK_FONTS) {
            val font = Font.createFont(Font.TRUETYPE_FONT, File("src/main/res/raw", name))
            assertTrue(name, SUBTITLE_COVERAGE_PROBES.all { font.canDisplay(it.codePointAt(0)) })
        }
    }

    @Test
    fun `writeIfChanged creates the file, keeps an identical one and replaces a stale one`() {
        val file = File(tmp.root, "fonts/a.ttf")
        writeIfChanged(file, byteArrayOf(1, 2, 3))
        assertTrue(file.readBytes().contentEquals(byteArrayOf(1, 2, 3)))

        file.setLastModified(1_000L)
        writeIfChanged(file, byteArrayOf(1, 2, 3))
        assertEquals("identical file not rewritten", 1_000L, file.lastModified())

        writeIfChanged(file, byteArrayOf(4, 5, 6))
        assertTrue(file.readBytes().contentEquals(byteArrayOf(4, 5, 6)))
        assertEquals("no temp file left", listOf("a.ttf"), tmp.root.resolve("fonts").list()!!.toList())
    }
}
