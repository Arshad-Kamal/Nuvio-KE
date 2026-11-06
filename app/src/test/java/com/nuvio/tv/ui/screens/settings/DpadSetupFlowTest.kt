package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.profile.DpadDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadSetupFlowTest {

    private val six = List(6) { DpadDirection.UP }
    private val seven = six + DpadDirection.DOWN
    private val twelve = List(12) { DpadDirection.LEFT }

    @Test
    fun `ok before six presses is not ready`() {
        val flow = DpadSetupFlow()
        repeat(5) { flow.press(DpadDirection.RIGHT) }
        assertEquals(DpadSetupOutcome.NotReady, flow.submit())
        assertEquals(DpadSetupStep.Create, flow.step)
    }

    @Test
    fun `create accepts at six and moves to confirm`() {
        val flow = DpadSetupFlow()
        six.forEach(flow::press)
        assertTrue(flow.canSubmit)
        assertEquals(DpadSetupOutcome.AdvancedToConfirm, flow.submit())
        assertEquals(DpadSetupStep.Confirm, flow.step)
        assertEquals(six, flow.draft)
        assertEquals(0, flow.entered.size)
        assertEquals(6, flow.confirmLength)
    }

    @Test
    fun `confirm auto-submit boundary equals the created length`() {
        val flow = DpadSetupFlow()
        seven.forEach(flow::press)
        flow.submit()
        assertEquals(7, flow.confirmLength)
        repeat(6) { flow.press(DpadDirection.RIGHT) }
        assertFalse(flow.canSubmit)
        flow.press(DpadDirection.RIGHT)
        assertTrue(flow.canSubmit)
    }

    @Test
    fun `matching re-entry saves`() {
        val flow = DpadSetupFlow()
        seven.forEach(flow::press)
        flow.submit()
        seven.forEach(flow::press)
        assertEquals(DpadSetupOutcome.Saved, flow.submit())
        assertEquals(DpadSetupStep.Saved, flow.step)
    }

    @Test
    fun `mismatch resets to a cleared create step`() {
        val flow = DpadSetupFlow()
        seven.forEach(flow::press)
        flow.submit()
        val wrong = seven.toMutableList().also { it[2] = DpadDirection.RIGHT }
        wrong.forEach(flow::press)
        assertEquals(DpadSetupOutcome.Mismatch, flow.submit())
        assertEquals(DpadSetupStep.Create, flow.step)
        assertEquals(0, flow.entered.size)
        assertEquals(0, flow.draft.size)
        assertNull(flow.confirmLength)
    }

    @Test
    fun `capture caps at twelve presses`() {
        val flow = DpadSetupFlow()
        twelve.forEach(flow::press)
        assertTrue(flow.isCaptureFull)
        flow.press(DpadDirection.UP)
        assertEquals(12, flow.entered.size)
        assertTrue(flow.canSubmit)
    }

    @Test
    fun `auto-submit fires exactly at the max boundary`() {
        val flow = DpadSetupFlow()
        elevenPresses().forEach(flow::press)
        assertFalse(flow.isCaptureFull)
        flow.press(DpadDirection.DOWN)
        assertTrue(flow.isCaptureFull)
        assertTrue(flow.canSubmit)
        assertEquals(DpadSetupOutcome.AdvancedToConfirm, flow.submit())
    }

    @Test
    fun `saved step accepts no further input`() {
        val flow = DpadSetupFlow()
        six.forEach(flow::press)
        flow.submit()
        six.forEach(flow::press)
        flow.submit()
        flow.press(DpadDirection.UP)
        assertEquals(DpadSetupOutcome.NotReady, flow.submit())
        assertEquals(DpadSetupStep.Saved, flow.step)
        // Entered stays as-confirmed (the success animation keeps the dots filled),
        // but the post-save press must not extend it.
        assertEquals(6, flow.entered.size)
    }

    @Test
    fun `reset clears draft and step`() {
        val flow = DpadSetupFlow()
        six.forEach(flow::press)
        flow.submit()
        flow.reset()
        assertEquals(DpadSetupStep.Create, flow.step)
        assertEquals(0, flow.entered.size)
        assertEquals(0, flow.draft.size)
    }

    private fun elevenPresses(): List<DpadDirection> = List(11) { DpadDirection.RIGHT }

    @Test
    fun `slots start at six, grow one past each press, cap at twelve, confirm shows the created length`() {
        val flow = DpadSetupFlow()
        assertEquals(6, flow.slotCount)
        repeat(5) { flow.press(DpadDirection.UP) }
        assertEquals(6, flow.slotCount)
        repeat(3) { flow.press(DpadDirection.DOWN) }
        assertEquals(9, flow.slotCount)
        repeat(4) { flow.press(DpadDirection.LEFT) }
        assertEquals(12, flow.slotCount)
        flow.submit()
        assertEquals(12, flow.slotCount)
    }
}
