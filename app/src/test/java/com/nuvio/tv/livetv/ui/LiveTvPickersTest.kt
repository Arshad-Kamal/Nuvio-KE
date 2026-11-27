package com.nuvio.tv.livetv.ui

import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioMarquee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 111 D12 (v2 stage 17): the pill row keeps the pickers' captions only when everything fits. */
class LiveTvCaptionsFitTest {
    private val live = NuvioMarquee.tokens.liveTv

    @Test
    fun `the pickers get what the fixed pills and every gap leave`() {
        // 532 - 132 fixed - 4 gaps of 10 between 5 items = 360: three 120 dp captioned pickers fit exactly.
        assertEquals(360.dp, liveTvPickerSpace(532.dp, listOf(32.dp, 100.dp), 3, 10.dp))
        assertEquals(359.dp, liveTvPickerSpace(531.dp, listOf(32.dp, 100.dp), 3, 10.dp))
    }

    @Test
    fun `dropped-caption pickers water-fill the rest - short ones stay whole, long ones share one cap`() {
        val floor = 44.dp
        // Fits exactly: unchanged.
        assertEquals(listOf(64.dp, 194.dp, 94.dp), liveTvPickerShares(352.dp, listOf(64.dp, 194.dp, 94.dp), floor))
        // Over by 1 dp: only the longest gives it up.
        assertEquals(listOf(64.dp, 193.dp, 94.dp), liveTvPickerShares(351.dp, listOf(64.dp, 194.dp, 94.dp), floor))
        // "All", a long Category, "English": the short two stay whole, the long one takes 300 - 64 - 94 = 142.
        assertEquals(listOf(64.dp, 142.dp, 94.dp), liveTvPickerShares(300.dp, listOf(64.dp, 194.dp, 94.dp), floor))
        // Two long ones share what the short one leaves equally.
        assertEquals(listOf(64.dp, 118.dp, 118.dp), liveTvPickerShares(300.dp, listOf(64.dp, 194.dp, 194.dp), floor))
        // All long (stage 17's worst case): equal shares.
        assertEquals(listOf(126.dp, 126.dp, 126.dp), liveTvPickerShares(378.dp, listOf(194.dp, 194.dp, 194.dp), floor))
        // Less than the fixed parts: each keeps its chevron (the row overflows instead).
        assertEquals(listOf(44.dp, 44.dp, 44.dp), liveTvPickerShares(90.dp, listOf(64.dp, 194.dp, 94.dp), floor))
        // A narrow screen where the fixed pills alone overflow: still the floor, never negative.
        assertEquals(listOf(44.dp, 44.dp, 44.dp), liveTvPickerShares((-20).dp, listOf(64.dp, 194.dp, 94.dp), floor))
    }

    @Test
    fun `a picker's width adds its caption and caps its value as the pill does`() {
        // 14 start + 60 value + 6 + 16 chevron + 8 end; the caption adds itself and a 6 dp gap.
        assertEquals(104.dp, liveTvPickerWidth(null, 60.dp, live))
        assertEquals(160.dp, liveTvPickerWidth(50.dp, 60.dp, live))
        assertEquals(liveTvPickerWidth(null, 150.dp, live), liveTvPickerWidth(null, 400.dp, live)) // value cut at 150
        // Favorites 4: 14 + label + 6 + count + 14; Sources: 14 + 16 icon + 6 + label + 14.
        assertEquals(98.dp, liveTvPillWidth(56.dp, 8.dp, icon = false, live))
        assertEquals(110.dp, liveTvPillWidth(60.dp, null, icon = true, live))
    }
}

/** Spec 111 D7, D16: what Back does from a row, and what the guide band's hint says it does. */
class LiveTvBackRulesTest {
    @Test
    fun `Back goes to the filters only from a focused row outside search, full screen and overlays`() {
        assertTrue(liveTvBackToFilters(rowFocused = true, searching = false, fullScreen = false, overlayOpen = false))
        assertFalse(liveTvBackToFilters(rowFocused = false, searching = false, fullScreen = false, overlayOpen = false))
        assertFalse(liveTvBackToFilters(rowFocused = true, searching = true, fullScreen = false, overlayOpen = false))
        assertFalse(liveTvBackToFilters(rowFocused = true, searching = false, fullScreen = true, overlayOpen = false))
        assertFalse(liveTvBackToFilters(rowFocused = true, searching = false, fullScreen = false, overlayOpen = true))
    }

    @Test
    fun `the band names Now away from now, then Close search, then Filters from the grid`() {
        assertEquals(LiveTvGuideBack.NOW, liveTvGuideBack(atNow = false, gridFocused = true, searching = true))
        assertEquals(LiveTvGuideBack.NOW, liveTvGuideBack(atNow = false, gridFocused = true, searching = false))
        assertEquals(LiveTvGuideBack.CLOSE_SEARCH, liveTvGuideBack(atNow = true, gridFocused = true, searching = true))
        assertEquals(LiveTvGuideBack.CLOSE_SEARCH, liveTvGuideBack(atNow = false, gridFocused = false, searching = true))
        assertEquals(LiveTvGuideBack.FILTERS, liveTvGuideBack(atNow = true, gridFocused = true, searching = false))
        assertEquals(LiveTvGuideBack.CLOSE_SEARCH, liveTvGuideBack(atNow = true, gridFocused = false, searching = true))
        assertNull(liveTvGuideBack(atNow = false, gridFocused = false, searching = false)) // a day chip
        assertNull(liveTvGuideBack(atNow = true, gridFocused = false, searching = false)) // a day chip at now
    }
}
