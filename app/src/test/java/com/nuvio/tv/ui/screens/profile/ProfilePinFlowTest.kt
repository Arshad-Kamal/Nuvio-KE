package com.nuvio.tv.ui.screens.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilePinFlowTest {

    private fun ProfilePinFlow.typeAll(digits: String): List<PinEntryOutcome> = digits.map { type(it) }

    @Test
    fun `single entry submits at four digits and clears`() {
        val flow = ProfilePinFlow(confirmNew = false)
        val outcomes = flow.typeAll("1234")
        assertEquals(List(3) { PinEntryOutcome.Typing }, outcomes.take(3))
        assertEquals(PinEntryOutcome.Submit("1234"), outcomes.last())
        assertEquals("", flow.pin)
    }

    @Test
    fun `non-digits are ignored, backspace and clear edit the entry`() {
        val flow = ProfilePinFlow(confirmNew = false)
        flow.typeAll("1a2")
        assertEquals("12", flow.pin)
        flow.backspace()
        assertEquals("1", flow.pin)
        flow.clear()
        assertEquals("", flow.pin)
        flow.backspace()
        assertEquals("", flow.pin)
    }

    @Test
    fun `new pin advances to confirm then submits on a match`() {
        val flow = ProfilePinFlow(confirmNew = true)
        assertEquals(PinEntryOutcome.AdvancedToConfirm, flow.typeAll("4321").last())
        assertEquals(ProfilePinEntryStage.Confirm, flow.stage)
        assertEquals("", flow.pin)
        assertEquals(PinEntryOutcome.Submit("4321"), flow.typeAll("4321").last())
    }

    @Test
    fun `a failed save is retried by retyping the confirm`() {
        val flow = ProfilePinFlow(confirmNew = true)
        flow.typeAll("4321")
        assertEquals(PinEntryOutcome.Submit("4321"), flow.typeAll("4321").last())
        // The screen clears the entry on a save error; stage and draft stay.
        flow.typeAll("43")
        flow.clear()
        assertEquals(ProfilePinEntryStage.Confirm, flow.stage)
        assertEquals(PinEntryOutcome.Submit("4321"), flow.typeAll("4321").last())
    }

    @Test
    fun `new pin mismatch returns to a clean create step`() {
        val flow = ProfilePinFlow(confirmNew = true)
        flow.typeAll("1111")
        assertEquals(PinEntryOutcome.Mismatch, flow.typeAll("2222").last())
        assertEquals(ProfilePinEntryStage.Create, flow.stage)
        assertEquals("", flow.pin)
        // The old draft is gone: a fresh entry starts a new create step.
        assertEquals(PinEntryOutcome.AdvancedToConfirm, flow.typeAll("2222").last())
    }

    @Test
    fun `ime text is filtered, cut at the length and submitted when full`() {
        val flow = ProfilePinFlow(confirmNew = false)
        assertEquals(PinEntryOutcome.Typing, flow.setText("1-2"))
        assertEquals("12", flow.pin)
        assertEquals(PinEntryOutcome.Submit("1234"), flow.setText("123456"))
    }
}
