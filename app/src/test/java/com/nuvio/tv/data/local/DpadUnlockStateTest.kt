package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadUnlockStateTest {

    @Test
    fun `combo is set only with hash salt and positive length`() {
        assertTrue(
            DpadUnlockState(
                comboHash = "a".repeat(64),
                salt = "b".repeat(32),
                comboLength = 6
            ).isComboSet
        )
        assertFalse(DpadUnlockState().isComboSet)
        assertFalse(DpadUnlockState(comboHash = "a".repeat(64)).isComboSet)
        assertFalse(DpadUnlockState(salt = "b".repeat(32)).isComboSet)
        assertFalse(DpadUnlockState(comboLength = 6).isComboSet)
        assertFalse(
            DpadUnlockState(
                comboHash = "a".repeat(64),
                salt = "b".repeat(32),
                comboLength = 0
            ).isComboSet
        )
    }

    @Test
    fun `enabled alone does not count as a set combo`() {
        val state = DpadUnlockState(enabled = true)
        assertFalse(state.isComboSet)
        assertTrue(state.enabled)
    }
}
