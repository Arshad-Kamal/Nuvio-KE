package com.nuvio.tv.core.player

import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import com.nuvio.tv.core.player.AudioPassthroughPolicy.Group
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio-setup helpers the VOD and Live TV players share (Spec 34 L6d): behaviour pinned. */
class AudioSetupHelpersTest {

    @Test
    fun `decoder priority prefers extensions, then raises to ON under force passthrough, else the setting`() {
        assertEquals(EXTENSION_RENDERER_MODE_PREFER, effectiveDecoderPriority(EXTENSION_RENDERER_MODE_OFF, true, true))
        assertEquals(EXTENSION_RENDERER_MODE_ON, effectiveDecoderPriority(EXTENSION_RENDERER_MODE_OFF, false, true))
        assertEquals(EXTENSION_RENDERER_MODE_PREFER, effectiveDecoderPriority(EXTENSION_RENDERER_MODE_PREFER, false, true))
        assertEquals(EXTENSION_RENDERER_MODE_OFF, effectiveDecoderPriority(EXTENSION_RENDERER_MODE_OFF, false, false))
    }

    @Test
    fun `learned denials are the route's valid entries only`() {
        val confirmed = setOf("hdmi:1::TRUEHD", "hdmi:1::DTS_HD", "hdmi:2::AC3", "hdmi:1::BOGUS", "malformed", "a::b::c")
        assertEquals(setOf(Group.TRUEHD, Group.DTS_HD), learnedDeniedAudioGroups(confirmed, "hdmi:1"))
        assertEquals(emptySet<Group>(), learnedDeniedAudioGroups(confirmed, null))
        assertEquals(emptySet<Group>(), learnedDeniedAudioGroups(confirmed, "bt:9"))
    }

    @Test
    fun `passthrough policy follows the toggles and denies nothing without software decoders`() {
        val settings = PlayerSettings(allowDtsPassthrough = false)
        val policy = audioPassthroughPolicyOf(settings, EXTENSION_RENDERER_MODE_ON, setOf(Group.TRUEHD))
        assertFalse(policy.allowDts)
        assertTrue(policy.allowAc3)
        assertTrue(policy.softwareDecodersAvailable)
        assertEquals(setOf(Group.TRUEHD), policy.learnedDeniedGroups)
        assertFalse(audioPassthroughPolicyOf(settings, EXTENSION_RENDERER_MODE_OFF, emptySet()).softwareDecodersAvailable)
    }
}
