package com.nuvio.tv.core.torrent

import android.content.Context
import com.nuvio.tv.R
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Strict mock: a wrong format argument fails with MockKException instead of matching. */
class TorrentFormatTest {

    private val context = mockk<Context>()
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        every { context.getString(R.string.unit_speed_mb_s, "1.0") } returns "1.0 MB/s"
        every { context.getString(R.string.unit_speed_mb_s, "1.5") } returns "1.5 MB/s"
        every { context.getString(R.string.unit_speed_kb_s, "2") } returns "2 KB/s"
        every { context.getString(R.string.unit_speed_b_s, 1023L) } returns "1023 B/s"
        every { context.getString(R.string.unit_size_mb, "5.0") } returns "5.0 MB"
        every { context.getString(R.string.unit_size_mb, "0.0") } returns "0.0 MB"
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `speed picks the unit by threshold`() {
        assertEquals("1.0 MB/s", formatTorrentSpeed(context, 1_048_576))
        assertEquals("1.5 MB/s", formatTorrentSpeed(context, 1_572_864))
        assertEquals("2 KB/s", formatTorrentSpeed(context, 2_048))
        assertEquals("1023 B/s", formatTorrentSpeed(context, 1_023))
    }

    @Test
    fun `size is megabytes with one decimal`() {
        assertEquals("5.0 MB", formatTorrentMB(context, 5_242_880))
        assertEquals("0.0 MB", formatTorrentMB(context, 0))
    }
}
