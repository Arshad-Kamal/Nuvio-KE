package com.nuvio.tv.core.report

import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.player.FailureFacts
import com.nuvio.tv.core.player.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 116 D4b. */
class PlaybackErrorLogTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L

    private fun record(i: Int, at: Long = now + i) =
        PlaybackErrorRecord(timeMs = at, cause = "source_error_$i", httpCode = 403, host = "download.real-debrid.com")

    @Test
    fun `keeps the newest ten, newest first`() {
        val dir = tmp.newFolder("report")
        (1..12).forEach { PlaybackErrorLog.append(dir, record(it)) }
        val kept = PlaybackErrorLog.records(dir, now + 12)
        assertEquals((12 downTo 3).map { "source_error_$it" }, kept.map { it.cause })
    }

    @Test
    fun `drops records older than 14 days`() {
        val dir = tmp.newFolder("report")
        PlaybackErrorLog.append(dir, record(0, at = now - ReportStorage.MAX_AGE_MS - 1))
        PlaybackErrorLog.append(dir, record(1))
        assertEquals(listOf("source_error_1"), PlaybackErrorLog.records(dir, now).map { it.cause })
        // Reading prunes the file too: once a read finds record 1 too old, an earlier clock no longer sees it.
        assertEquals(emptyList<String>(), PlaybackErrorLog.records(dir, now + ReportStorage.MAX_AGE_MS + 10).map { it.cause })
        assertEquals(emptyList<String>(), PlaybackErrorLog.records(dir, now).map { it.cause })
    }

    @Test
    fun `a failure keeps D4b's fields and never its stream name or description`() {
        val failure = PlaybackFailure(
            cause = FailureCause.LINK_EXPIRED,
            message = "The link expired",
            facts = FailureFacts(
                streamName = "Torrentio RD https://torrentio.strem.fun/resolve/realdebrid/KEY/x",
                streamDescription = "secret https://h.example/u/p",
                addon = "Torrentio",
                host = "download.real-debrid.com",
                fileName = "Movie.2160p.mkv",
                listedSizeBytes = 58_000_000_000L,
                httpCode = 403,
                measuredMbps = 41.5,
                neededMbps = 80.0,
            ),
        )
        val dir = tmp.newFolder("report")
        PlaybackErrorLog.append(dir, playbackErrorRecord(failure, "EXOPLAYER", now))
        val stored = PlaybackErrorLog.records(dir, now).single()
        assertEquals("LINK_EXPIRED", stored.cause)
        assertEquals(58_000_000_000L, stored.sizeBytes)
        assertEquals("EXOPLAYER", stored.engine)
        val text = dir.resolve("playback-errors.json").readText()
        assertFalse("://" in text)
        assertFalse("secret" in text)
    }
}
