package com.nuvio.tv.ui.util

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class HoldReleaseGuardTest {

    private val center = KeyEvent.KEYCODE_DPAD_CENTER
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    @Test
    fun `a not-ready guard swallows the opening hold's repeats and release`() {
        assertEquals(HoldReleaseStep(ready = false, consume = true), holdReleaseStep(false, center, down, 3))
        assertEquals(HoldReleaseStep(ready = false, consume = true), holdReleaseStep(false, center, up, 0))
        assertEquals(HoldReleaseStep(ready = false, consume = true), holdReleaseStep(false, KeyEvent.KEYCODE_MENU, up, 0))
        assertEquals(HoldReleaseStep(ready = false, consume = true), holdReleaseStep(false, KeyEvent.KEYCODE_ENTER, up, 0))
    }

    @Test
    fun `a fresh press makes the guard ready and passes through`() {
        assertEquals(HoldReleaseStep(ready = true, consume = false), holdReleaseStep(false, center, down, 0))
    }

    @Test
    fun `a ready guard passes every event`() {
        assertEquals(HoldReleaseStep(ready = true, consume = false), holdReleaseStep(true, center, up, 0))
        assertEquals(HoldReleaseStep(ready = true, consume = false), holdReleaseStep(true, KeyEvent.KEYCODE_ENTER, down, 5))
    }

    @Test
    fun `non-select keys always pass and leave readiness alone`() {
        assertEquals(HoldReleaseStep(ready = false, consume = false), holdReleaseStep(false, KeyEvent.KEYCODE_DPAD_DOWN, down, 0))
        assertEquals(HoldReleaseStep(ready = false, consume = false), holdReleaseStep(false, KeyEvent.KEYCODE_BACK, up, 0))
    }
}
