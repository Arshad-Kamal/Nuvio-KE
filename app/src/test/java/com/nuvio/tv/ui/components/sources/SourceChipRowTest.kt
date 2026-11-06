package com.nuvio.tv.ui.components.sources

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.core.stream.StreamLimitVerdict
import com.nuvio.tv.core.stream.StreamListSelection.Addon
import com.nuvio.tv.core.stream.StreamListSelection.All
import com.nuvio.tv.core.stream.StreamListSelection.Hidden
import com.nuvio.tv.core.stream.StreamListView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 51 P6: the chip row goes by selection values; the eyebrow, hidden line and row bitrate it frames. */
class SourceChipRowTest {

    private val names = listOf("AIOStreams", "Torrentio")
    private val chips = chipRowSelections(names, hiddenCount = 4)

    @Test
    fun `the limit chip sits right after All only while streams are hidden`() {
        assertEquals(listOf(All, Addon("AIOStreams"), Addon("Torrentio")), chipRowSelections(names, hiddenCount = 0))
        assertEquals(listOf(All, Hidden, Addon("AIOStreams"), Addon("Torrentio")), chips)
    }

    @Test
    fun `Left and Right on a row step by selection through the limit chip, stopping at either end`() {
        assertEquals(Hidden, chips.stepFrom(All, step = 1))
        assertEquals(Addon("AIOStreams"), chips.stepFrom(Hidden, step = 1))
        assertEquals(Hidden, chips.stepFrom(Addon("AIOStreams"), step = -1))
        assertNull(chips.stepFrom(All, step = -1))
        assertNull(chips.stepFrom(Addon("Torrentio"), step = 1))
    }

    @Test
    fun `without hidden streams an addon steps straight back to All`() {
        assertEquals(All, chipRowSelections(names, hiddenCount = 0).stepFrom(Addon("AIOStreams"), step = -1))
    }

    @Test
    fun `Left steps back and Right forward, mirrored right to left, other keys not at all`() {
        assertEquals(-1, horizontalStep(Key.DirectionLeft, isRtl = false))
        assertEquals(1, horizontalStep(Key.DirectionRight, isRtl = false))
        assertEquals(1, horizontalStep(Key.DirectionLeft, isRtl = true))
        assertEquals(-1, horizontalStep(Key.DirectionRight, isRtl = true))
        assertNull(horizontalStep(Key.DirectionUp, isRtl = false))
    }

    @Test
    fun `the chip row reaches the refresh by Left from All and leaves it by Right`() {
        assertEquals(ChipSlot.Refresh, chipRowStep(chips, ChipSlot.Chip(All), All, step = -1))
        assertEquals(ChipSlot.Chip(All), chipRowStep(chips, ChipSlot.Refresh, All, step = 1))
        assertNull(chipRowStep(chips, ChipSlot.Refresh, All, step = -1))
        assertNull(chipRowStep(chips, ChipSlot.Chip(Addon("Torrentio")), Addon("Torrentio"), step = 1))
    }

    @Test
    fun `a focused chip that left the row steps from the selection's chip`() {
        // The limit chip went (nothing hidden): the row reads its position as All, the selection.
        val noLimit = chipRowSelections(names, hiddenCount = 0)
        assertEquals(ChipSlot.Chip(Addon("AIOStreams")), chipRowStep(noLimit, ChipSlot.Chip(Hidden), All, step = 1))
    }

    @Test
    fun `the eyebrow counts shown of total while streams are hidden, all of them when none is`() {
        assertEquals(19 to 23, eyebrowShownOfTotal(StreamListView(hiddenCount = 4, total = 23)))
        // A view listing only hidden streams still reads as nothing shown.
        assertEquals(0 to 23, eyebrowShownOfTotal(StreamListView(hiddenCount = 23, allCount = 23, total = 23)))
        assertNull(eyebrowShownOfTotal(StreamListView(allCount = 23, total = 23)))
    }

    @Test
    fun `a hidden row's bitrate rounds up, so it never reads as the figure it is over`() {
        assertEquals(134, overLimitMbpsLabel(verdict(figure = 133, estimate = 133.2)))
        assertEquals(109, overLimitMbpsLabel(verdict(figure = 70, estimate = 109.0)))
    }

    private fun verdict(
        figure: Int = 60,
        estimate: Double = figure + 10.0
    ) = StreamLimitVerdict(estimateMbps = estimate, figureMbps = figure, source = null)
}
