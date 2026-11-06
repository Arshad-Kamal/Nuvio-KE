package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfrNoisyPolicyTest {

    private fun resume(
        noisy: Boolean = true,
        playWhenReady: Boolean = false,
        userPaused: Boolean = false,
        switchAt: Long = 100_000L,
        now: Long = 101_600L
    ) = AfrNoisyPolicy.shouldResume(noisy, playWhenReady, userPaused, switchAt, now)

    @Test
    fun `a noisy pause right after a mode switch resumes`() = assertTrue(resume())

    @Test
    fun `the window edge still resumes, past it the pause stands`() {
        assertTrue(resume(now = 100_000L + AfrNoisyPolicy.GRACE_MS))
        assertFalse(resume(now = 100_000L + AfrNoisyPolicy.GRACE_MS + 1))
    }

    @Test
    fun `no switch this session means a real disconnect`() = assertFalse(resume(switchAt = 0L))

    @Test
    fun `other pause reasons, a user pause, or still playing are left alone`() {
        assertFalse(resume(noisy = false))
        assertFalse(resume(userPaused = true))
        assertFalse(resume(playWhenReady = true))
    }
}
