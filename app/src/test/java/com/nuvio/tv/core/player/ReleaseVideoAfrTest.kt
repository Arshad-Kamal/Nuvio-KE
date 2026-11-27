package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Spec 152: a changelog release video plays with AFR off; every other session keeps the stored setting. */
class ReleaseVideoAfrTest {

    private val storedCombinations = FrameRateMatchingMode.entries.flatMap { mode ->
        listOf(true, false).map { resolution ->
            PlayerSettings(frameRateMatchingMode = mode, resolutionMatchingEnabled = resolution)
        }
    }

    @Test
    fun `release video session resolves to AFR and resolution matching off whatever is stored`() {
        storedCombinations.forEach { stored ->
            val session = stored.forPlaybackSession(releaseVideo = true)
            // Only the two AFR fields change.
            assertEquals(
                stored.copy(frameRateMatchingMode = FrameRateMatchingMode.OFF, resolutionMatchingEnabled = false),
                session
            )
        }
    }

    @Test
    fun `normal session keeps the stored settings`() {
        storedCombinations.forEach { stored ->
            assertSame(stored, stored.forPlaybackSession(releaseVideo = false))
        }
    }
}
