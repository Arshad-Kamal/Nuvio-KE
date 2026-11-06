package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.core.profile.DpadDirection
import com.nuvio.tv.ui.components.DpadCaptureOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertNull
import org.junit.Test

class DpadUnlockCaptureTest {

    @Test
    fun `presses stop at the stored combination length`() {
        val capture = DpadUnlockCapture(comboLength = 6)
        repeat(8) { capture.press(DpadDirection.UP) }
        assertEquals(6, capture.entered.size)
        assertTrue(capture.isFull)
    }

    @Test
    fun `a sequence shorter than the stored length never fills`() {
        val capture = DpadUnlockCapture(comboLength = 8)
        repeat(6) { capture.press(DpadDirection.LEFT) }
        assertFalse(capture.isFull)
    }

    @Test
    fun `presses are ignored once an outcome is showing`() {
        val capture = DpadUnlockCapture(comboLength = 6)
        repeat(3) { capture.press(DpadDirection.RIGHT) }
        capture.markFail()
        capture.press(DpadDirection.UP)
        assertEquals(3, capture.entered.size)
    }

    @Test
    fun `presses are ignored once the capture is full`() {
        val capture = DpadUnlockCapture(comboLength = 6)
        repeat(6) { capture.press(DpadDirection.DOWN) }
        capture.press(DpadDirection.UP)
        assertEquals(List(6) { DpadDirection.DOWN }, capture.entered)
    }

    @Test
    fun `presses keep their order`() {
        val capture = DpadUnlockCapture(comboLength = 6)
        listOf(DpadDirection.UP, DpadDirection.DOWN, DpadDirection.LEFT).forEach(capture::press)
        assertEquals(
            listOf(DpadDirection.UP, DpadDirection.DOWN, DpadDirection.LEFT),
            capture.entered
        )
    }

    @Test
    fun `outcome marks only apply from idle`() {
        val capture = DpadUnlockCapture(comboLength = 6)
        capture.markSuccess()
        capture.markFail()
        assertEquals(DpadCaptureOutcome.Success, capture.outcome)
    }

    @Test
    fun `reset clears the sequence and the outcome`() {
        val capture = DpadUnlockCapture(comboLength = 6)
        repeat(6) { capture.press(DpadDirection.RIGHT) }
        capture.markFail()
        capture.reset()
        assertEquals(0, capture.entered.size)
        assertEquals(DpadCaptureOutcome.Idle, capture.outcome)
        assertFalse(capture.isFull)
    }

    @Test
    fun `arrows press, OK and Enter switch to the PIN pad, Back is not ours`() {
        assertEquals(DpadUnlockKeyAction.Press(DpadDirection.UP), dpadUnlockKeyAction(Key.DirectionUp))
        assertEquals(DpadUnlockKeyAction.Press(DpadDirection.DOWN), dpadUnlockKeyAction(Key.DirectionDown))
        assertEquals(DpadUnlockKeyAction.Press(DpadDirection.LEFT), dpadUnlockKeyAction(Key.DirectionLeft))
        assertEquals(DpadUnlockKeyAction.Press(DpadDirection.RIGHT), dpadUnlockKeyAction(Key.DirectionRight))
        assertEquals(DpadUnlockKeyAction.UsePin, dpadUnlockKeyAction(Key.DirectionCenter))
        assertEquals(DpadUnlockKeyAction.UsePin, dpadUnlockKeyAction(Key.Enter))
        assertNull(dpadUnlockKeyAction(Key.Back))
    }
}
