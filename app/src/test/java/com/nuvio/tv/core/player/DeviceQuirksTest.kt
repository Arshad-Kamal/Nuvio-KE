package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceQuirksTest {

    private fun q(manufacturer: String?, model: String?) = DeviceQuirks(manufacturer, model)

    @Test
    fun `the reference Shield counts`() {
        assertTrue(q("NVIDIA", "SHIELD Android TV").isShield)
        assertTrue(q("nvidia", "shield android tv").isShield)
    }

    @Test
    fun `other devices, NVIDIA non-Shield and missing values do not`() {
        assertFalse(q("Google", "Chromecast").isShield)
        assertFalse(q("NVIDIA", "Jetson").isShield)
        assertFalse(q("Xiaomi", "SHIELD clone").isShield)
        assertFalse(q(null, "SHIELD Android TV").isShield)
        assertFalse(q("NVIDIA", null).isShield)
    }

    @Test
    fun `brand flags match the manufacturer case-insensitively`() {
        assertTrue(q("Amazon", "AFTKA").isAmazon)
        assertTrue(q("samsung", "x").isSamsung)
        assertTrue(q("XIAOMI", "x").isXiaomi)
        assertFalse(q("NVIDIA", "SHIELD Android TV").isAmazon)
        assertFalse(q(null, null).isSamsung)
    }

    @Test
    fun `DV HDR10+ defect covers the four Jellyfin Fire TV models exactly`() {
        for (model in listOf("AFTKRT", "AFTKA", "AFTKM", "AFTMM")) {
            assertTrue(model, q("Amazon", model).dvHdr10PlusPlaybackDefect)
        }
        assertFalse(q("Amazon", "AFTKAX").dvHdr10PlusPlaybackDefect)
        assertFalse(q("Amazon", "aftka").dvHdr10PlusPlaybackDefect)
        assertFalse(q("Amazon", null).dvHdr10PlusPlaybackDefect)
    }

    @Test
    fun `unreported DV7 decoder is the exact Shield model string`() {
        assertTrue(q("NVIDIA", "SHIELD Android TV").unreportedDv7Decoder)
        assertFalse(q("NVIDIA", "shield android tv").unreportedDv7Decoder)
        assertFalse(q("NVIDIA", "SHIELD Android TV Pro").unreportedDv7Decoder)
    }
}
