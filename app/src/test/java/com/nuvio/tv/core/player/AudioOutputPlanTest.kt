package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Bluetooth output rules the player build and the in-place route switch share. */
class AudioOutputPlanTest {

    private val forceOpticalSurround = PlayerSettings(
        forceOpticalPassthrough = true,
        decoderPriority = 2,
        downmixEnabled = false,
        audioOutputChannels = AudioOutputChannels.CHANNELS_5_0
    )

    @Test
    fun wiredOutputFollowsTheSettings() {
        val plan = AudioOutputPlan.of(forceOpticalSurround, isBluetooth = false)

        assertTrue(plan.forceOpticalPassthrough)
        assertFalse(plan.downmixEnabled)
        assertEquals(AudioOutputChannels.CHANNELS_5_0, plan.outputChannels)
    }

    @Test
    fun bluetoothDropsOpticalAndDownmixesToStereo() {
        val plan = AudioOutputPlan.of(forceOpticalSurround, isBluetooth = true)

        assertFalse(plan.forceOpticalPassthrough)
        assertTrue(plan.downmixEnabled)
        assertEquals(AudioOutputChannels.CHANNELS_2_0, plan.outputChannels)
    }

    @Test
    fun forceOpticalNeedsTheAppDecoder() {
        val plan = AudioOutputPlan.of(forceOpticalSurround.copy(decoderPriority = 0), isBluetooth = false)

        assertFalse(plan.forceOpticalPassthrough)
    }

    @Test
    fun downmixSettingAppliesOnlyWithThePreferredAppDecoder() {
        val downmix = PlayerSettings(downmixEnabled = true, decoderPriority = 2)

        assertTrue(AudioOutputPlan.of(downmix, isBluetooth = false).downmixEnabled)
        assertFalse(AudioOutputPlan.of(downmix.copy(decoderPriority = 1), isBluetooth = false).downmixEnabled)
    }

    @Test
    fun normalizationIsTheInverseOfMaintainOriginal() {
        assertFalse(AudioOutputPlan.of(PlayerSettings(maintainOriginalAudioOnDownmix = true), false).downmixNormalizationEnabled)
        assertTrue(AudioOutputPlan.of(PlayerSettings(maintainOriginalAudioOnDownmix = false), true).downmixNormalizationEnabled)
    }
}
