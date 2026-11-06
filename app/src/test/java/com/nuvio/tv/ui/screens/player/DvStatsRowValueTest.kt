package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** HUD DV row (Register A2 P14: STRIP_DV and OFF name their path). */
class DvStatsRowValueTest {

    private val notAsked: () -> Boolean = { fail("libdovi counters read outside DV81_LIBDOVI"); false }

    @Test
    fun `non-P7 source is native outside the strip modes`() {
        assertEquals("P8 native · MEL" to StatsDot.NONE, dvStatsRowValue("8", " · MEL", "DV81_LIBDOVI", notAsked))
        assertEquals("P8 native" to StatsDot.NONE, dvStatsRowValue("8", "", "AUTO", notAsked))
    }

    @Test
    fun `strip modes name a stripped P8, P5 stays native`() {
        // F-11: the MKV strip path now records the source profile, and it strips every P8 RPU.
        assertEquals("P8→HDR10 (RPU stripped)" to StatsDot.NONE, dvStatsRowValue("8", "", "STRIP_DV", notAsked))
        assertEquals("P8→HDR10 base layer" to StatsDot.NONE, dvStatsRowValue("8", "", "HDR10_BASE_LAYER", notAsked))
        assertEquals("P5 native" to StatsDot.NONE, dvStatsRowValue("5", "", "STRIP_DV", notAsked))
    }

    @Test
    fun `STRIP_DV reads RPU stripped`() {
        assertEquals("P7→HDR10 (RPU stripped) · FEL" to StatsDot.NONE, dvStatsRowValue("7", " · FEL", "STRIP_DV", notAsked))
    }

    @Test
    fun `OFF reads MediaCodec`() {
        assertEquals("P7 · MediaCodec" to StatsDot.NONE, dvStatsRowValue("7", "", "OFF", notAsked))
    }

    @Test
    fun `existing arms unchanged`() {
        assertEquals("P7→HDR10 base layer" to StatsDot.NONE, dvStatsRowValue("7", "", "HDR10_BASE_LAYER", notAsked))
        assertEquals("P7→8.1 libdovi" to StatsDot.GOOD, dvStatsRowValue("7", "", "DV81_LIBDOVI") { true })
        assertEquals("P7→8.1 libdovi" to StatsDot.WARN, dvStatsRowValue("7", "", "DV81_LIBDOVI") { false })
        assertEquals("7 native" to StatsDot.NONE, dvStatsRowValue("7", "", "AUTO", notAsked))
    }
}
