package com.nuvio.tv.livetv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PlaybackActivity] (Spec 73 S3a): the main player's session or any live player counts as playing. */
class PlaybackActivityTest {

    @Test
    fun `playing while the main player is open or any live player is active`() = runTest {
        val session = MutableStateFlow(false)
        val activity = PlaybackActivity(session)
        val a = Any()
        val b = Any()
        assertFalse(activity.playing.first())
        activity.setLive(a, true)
        activity.setLive(b, true)
        activity.setLive(a, false)
        assertTrue(activity.playing.first())
        activity.setLive(b, false)
        activity.setLive(b, false)
        assertFalse(activity.playing.first())
        session.value = true
        assertTrue(activity.playing.first())
    }

    @Test
    fun `main player opened emits once per open, not while it stays open or on close`() = runTest {
        val session = MutableStateFlow(false)
        val activity = PlaybackActivity(session)
        var opened = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { activity.mainPlayerOpened.collect { opened++ } }
        assertEquals(0, opened)
        session.value = true
        assertEquals(1, opened)
        session.value = true
        assertEquals(1, opened)
        session.value = false
        assertEquals(1, opened)
        session.value = true
        assertEquals(2, opened)
    }

    @Test
    fun `main player already open counts as opened when collected`() = runTest {
        val activity = PlaybackActivity(MutableStateFlow(true))
        var opened = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { activity.mainPlayerOpened.collect { opened++ } }
        assertEquals(1, opened)
    }
}
