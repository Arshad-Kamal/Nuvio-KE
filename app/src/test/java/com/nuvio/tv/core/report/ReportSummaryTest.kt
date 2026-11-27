package com.nuvio.tv.core.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 116 B1: the panel's summary lines show only what the scrubbed, trimmed parts still carry. */
class ReportSummaryTest {

    private val none = ReportSecrets(emptySet(), emptyMap())

    private val tv = "manufacturer = NVIDIA\nmodel = SHIELD Android TV\nandroid = 11 (SDK 30)\nram_mb = 2996\ndolby_vision = true"
    private val app = "version = 1.3.0 (1460)\nbuild = public\nabi = armeabi-v7a\ninstalled_days = 3"
    private val settings = listOf(
        SettingLine("player", "internal_player_engine", "EXOPLAYER"),
        SettingLine("video", "frame_rate_matching_mode", "START_STOP"),
    )

    private val summary = ReportSummary(
        tv = TvSummary("SHIELD Android TV", "11", 2996, dolbyVision = true),
        app = AppSummary("1.3.0", 1460, "public", "armeabi-v7a"),
        closes = listOf(CloseSummary("crash", 2_000L, "# exit crash t")),
        errors = listOf(ErrorSummary("LINK_EXPIRED", 3_000L), ErrorSummary("TOO_SLOW", 2_000L)),
        settings = settings,
    )

    private fun parts(tv: String = this.tv, errors: String = "t cause=LINK_EXPIRED http=403\nt cause=TOO_SLOW", log: String = "a\nb") =
        ReportParts(tv, app, "# exit crash t", errors, ReportSettingsPart.text(settings), log)

    @Test
    fun `values the parts carry are kept, with the final log's lines and bytes`() {
        val shown = ReportText.build(parts(log = "é\nb"), none, summary).summary
        assertEquals(summary.tv, shown.tv)
        assertEquals(summary.app, shown.app)
        assertEquals(summary.closes, shown.closes)
        assertEquals(summary.errors, shown.errors)
        assertEquals(settings, shown.settings)
        assertEquals(LogSummary(lines = 2, bytes = 4, trimmed = false), shown.log)
    }

    @Test
    fun `a value the scrub replaced is dropped from the summary`() {
        // The model equals a stored key: the part reads <secret>, so the line must not show it.
        val secrets = ReportSecrets(setOf("SHIELD Android TV"), emptyMap())
        val shown = ReportText.build(parts(), secrets, summary).summary
        assertNull(shown.tv.model)
        assertEquals("11", shown.tv.android)
        assertFalse("SHIELD Android TV" in ReportText.build(parts(), secrets, summary).bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `every raw value the scrub replaced is dropped`() {
        // One known value per raw field: each line then reads <secret> and its summary value goes.
        val secrets = ReportSecrets(setOf("11", "1.3.0", "armeabi-v7a", "public", "EXOPLAYER", "START_STOP"), emptyMap())
        val shown = ReportText.build(parts(), secrets, summary).summary
        assertNull(shown.tv.android)
        assertNull(shown.app.versionName)
        assertNull(shown.app.abi)
        assertNull(shown.app.build)
        assertTrue(shown.settings.isEmpty())
        assertEquals("SHIELD Android TV", shown.tv.model)
    }

    @Test
    fun `a close whose header the cut removed is dropped`() {
        val closes = listOf(
            CloseSummary("anr", 3_000L, "# exit anr t1"),
            CloseSummary("crash", 1_000L, "# crash time=1000"),
        )
        val p = ReportParts(tv, app, "# exit anr t1 Input dispatching timed out\n\"main\" prio=5", "none", "", "")
        val shown = ReportText.build(p, none, summary.copy(closes = closes)).summary
        assertEquals(listOf(closes[0]), shown.closes)
    }

    @Test
    fun `a crash on a thread whose name the scrub changed is still listed`() {
        val crash = CloseSummary("crash", 1_000L, "# crash time=1000")
        val p = ReportParts(tv, app, "# crash time=1000 thread=OkHttp https://x.example/a?token=abc\njava.lang.IllegalStateException", "none", "", "")
        val file = ReportText.build(p, none, summary.copy(closes = listOf(crash)))
        assertFalse("x.example/a" in file.parts.lastCloses)
        assertEquals(listOf(crash), file.summary.closes)
    }

    @Test
    fun `errors cut by the trim or absent from the part are dropped, and empty parts show none`() {
        val shown = ReportText.build(parts(errors = "t cause=LINK_EXPIRED http=403"), none, summary).summary
        assertEquals(listOf(ErrorSummary("LINK_EXPIRED", 3_000L)), shown.errors)
        val empty = ReportText.build(
            ReportParts(tv, app, ReportSummary.NONE, ReportSummary.NONE, "", ""), none, summary
        ).summary
        assertTrue(empty.closes.isEmpty())
        assertTrue(empty.errors.isEmpty())
        assertTrue(empty.settings.isEmpty())
        assertEquals(LogSummary(0, 0, false), empty.log)
    }

    @Test
    fun `a log trimmed to fit is marked trimmed`() {
        val log = (1..400).joinToString("\n") { "$it " + "x".repeat(1_000) }
        val shown = ReportText.build(parts(log = log), none, summary).summary.log
        assertTrue(shown.trimmed)
        assertTrue(shown.lines in 1 until 400)
    }

    @Test
    fun `closes keep abnormal ends newest first and count a crash in both records once`() {
        val exits = listOf(
            CloseSummary("crash", 100_000L),
            CloseSummary("reason=10", 90_000L), // a user exit
            CloseSummary("memory-kill", 50_000L),
        )
        val files = listOf(CloseSummary("crash", 130_000L, "a"), CloseSummary("crash", 20_000L, "b"))
        val closes = ReportSummary.closes(exits, files)
        assertEquals(
            listOf(CloseSummary("crash", 100_000L), CloseSummary("memory-kill", 50_000L), files[1]),
            closes,
        )
        // Below API 30 there are no records: the crash files alone.
        assertEquals(listOf(files[1]), ReportSummary.closes(emptyList(), listOf(files[1])))
    }
}
