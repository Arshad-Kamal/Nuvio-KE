package com.nuvio.tv.ui.screens.settings.trackers

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 106 E2/E4/E5: "Resets in 6 h", "Try again in 12 min". */
class TrackerTimeLeftTest {
    @Test
    fun `hours round up from one hour, minutes below, never zero`() {
        assertEquals(TrackerTimeLeft(TimeUnitShown.HOURS, 6), trackerTimeLeft(5 * 3_600_000L + 1))
        assertEquals(TrackerTimeLeft(TimeUnitShown.HOURS, 1), trackerTimeLeft(3_600_000L))
        assertEquals(TrackerTimeLeft(TimeUnitShown.HOURS, 2), trackerTimeLeft(3_600_000L + 60_000L))
        assertEquals(TrackerTimeLeft(TimeUnitShown.MINUTES, 59), trackerTimeLeft(58 * 60_000L + 1))
        assertEquals(TrackerTimeLeft(TimeUnitShown.MINUTES, 1), trackerTimeLeft(1))
        assertEquals(TrackerTimeLeft(TimeUnitShown.MINUTES, 1), trackerTimeLeft(0))
        assertEquals(TrackerTimeLeft(TimeUnitShown.MINUTES, 1), trackerTimeLeft(-5))
    }
}
