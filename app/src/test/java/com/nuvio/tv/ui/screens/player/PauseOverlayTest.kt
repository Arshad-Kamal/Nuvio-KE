package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 25 U6h: the pause overlay's key model (stages 11-11c, + Spec 33 D7 Network chip) and the cast initials. */
class PauseOverlayTest {

    private fun action(
        keyCode: Int,
        focusedChip: Int? = null,
        chipCount: Int = 6,
        detailOpen: Boolean = false,
        isRtl: Boolean = false,
        isKeyDown: Boolean = true,
        networkChip: Boolean = false,
        networkChipFocused: Boolean = false
    ) = pauseOverlayKeyAction(keyCode, isKeyDown, focusedChip, chipCount, detailOpen, isRtl, networkChip, networkChipFocused)

    @Test
    fun playPauseResumesFromAnywhere() {
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY).forEach { key ->
            assertEquals(PauseKeyAction.RESUME, action(key))
            assertEquals(PauseKeyAction.RESUME, action(key, focusedChip = 3))
            assertEquals(PauseKeyAction.RESUME, action(key, detailOpen = true))
        }
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_MEDIA_STOP, focusedChip = 0))
    }

    @Test
    fun overviewOkResumesDownFocusesTheCastOtherKeysDismiss() {
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER).forEach {
            assertEquals(PauseKeyAction.RESUME, action(it))
        }
        assertEquals(PauseKeyAction.FOCUS_CAST, action(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(PauseKeyAction.DISMISS, action(KeyEvent.KEYCODE_DPAD_DOWN, chipCount = 0))
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU)
            .forEach { assertEquals(PauseKeyAction.DISMISS, action(it)) }
    }

    @Test
    fun personViewOkResumesDownDismisses() {
        assertEquals(PauseKeyAction.RESUME, action(KeyEvent.KEYCODE_DPAD_CENTER, detailOpen = true))
        assertEquals(PauseKeyAction.DISMISS, action(KeyEvent.KEYCODE_DPAD_DOWN, detailOpen = true))
        assertEquals(PauseKeyAction.DISMISS, action(KeyEvent.KEYCODE_DPAD_LEFT, detailOpen = true))
    }

    @Test
    fun aFocusedChipOwnsOkUpLeavesDownIsEaten() {
        assertEquals(PauseKeyAction.PASS, action(KeyEvent.KEYCODE_DPAD_CENTER, focusedChip = 2))
        assertEquals(PauseKeyAction.LEAVE_CHIPS, action(KeyEvent.KEYCODE_DPAD_UP, focusedChip = 2))
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_DPAD_DOWN, focusedChip = 2))
        assertEquals(PauseKeyAction.DISMISS, action(KeyEvent.KEYCODE_MENU, focusedChip = 2))
    }

    @Test
    fun leftRightWalkTheRowAndStopAtItsEnds() {
        assertEquals(PauseKeyAction.PASS, action(KeyEvent.KEYCODE_DPAD_LEFT, focusedChip = 2))
        assertEquals(PauseKeyAction.PASS, action(KeyEvent.KEYCODE_DPAD_RIGHT, focusedChip = 2))
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_DPAD_LEFT, focusedChip = 0))
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_DPAD_RIGHT, focusedChip = 5))
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_DPAD_LEFT, focusedChip = 0, chipCount = 1))
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_DPAD_RIGHT, focusedChip = 0, chipCount = 1))
    }

    @Test
    fun rtlMirrorsTheRowEnds() {
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_DPAD_RIGHT, focusedChip = 0, isRtl = true))
        assertEquals(PauseKeyAction.PASS, action(KeyEvent.KEYCODE_DPAD_LEFT, focusedChip = 0, isRtl = true))
        assertEquals(PauseKeyAction.CONSUME, action(KeyEvent.KEYCODE_DPAD_LEFT, focusedChip = 5, isRtl = true))
    }

    @Test
    fun downReachesTheNetworkChipFromTheCastRowOrTheOverview() {
        assertEquals(PauseKeyAction.FOCUS_NETWORK, action(KeyEvent.KEYCODE_DPAD_DOWN, focusedChip = 2, networkChip = true))
        assertEquals(PauseKeyAction.FOCUS_CAST, action(KeyEvent.KEYCODE_DPAD_DOWN, networkChip = true))
        assertEquals(PauseKeyAction.FOCUS_NETWORK, action(KeyEvent.KEYCODE_DPAD_DOWN, chipCount = 0, networkChip = true))
        assertEquals(
            PauseKeyAction.DISMISS,
            action(KeyEvent.KEYCODE_DPAD_DOWN, detailOpen = true, networkChip = true)
        )
    }

    @Test
    fun theFocusedNetworkChipOwnsOkUpReturnsOtherMovesAreEaten() {
        fun net(keyCode: Int, chipCount: Int = 6, isRtl: Boolean = false) =
            action(keyCode, chipCount = chipCount, isRtl = isRtl, networkChip = true, networkChipFocused = true)
        assertEquals(PauseKeyAction.PASS, net(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(PauseKeyAction.FOCUS_CAST, net(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PauseKeyAction.LEAVE_CHIPS, net(KeyEvent.KEYCODE_DPAD_UP, chipCount = 0))
        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT).forEach {
            assertEquals(PauseKeyAction.CONSUME, net(it))
            assertEquals(PauseKeyAction.CONSUME, net(it, isRtl = true))
        }
        assertEquals(PauseKeyAction.DISMISS, net(KeyEvent.KEYCODE_MENU))
        assertEquals(PauseKeyAction.RESUME, net(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(
            PauseKeyAction.CONSUME,
            action(KeyEvent.KEYCODE_DPAD_CENTER, networkChip = true, networkChipFocused = true, isKeyDown = false)
        )
    }

    @Test
    fun releasesAreEatenSoNoPreviewSeekCommits() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER).forEach {
            assertEquals(PauseKeyAction.CONSUME, action(it, isKeyDown = false))
            assertEquals(PauseKeyAction.CONSUME, action(it, focusedChip = 1, isKeyDown = false))
        }
    }

    @Test
    fun initialsTakeTheFirstAndLastWord() {
        assertEquals("VR", castInitials("Vanja Rukavina"))
        assertEquals("DL", castInitials("Derek de Lint"))
        assertEquals("Z", castInitials("Zendaya"))
        assertEquals("JB", castInitials("  jody   bhe "))
        assertEquals("", castInitials("   "))
        assertEquals("\uD83D\uDE00B", castInitials("\uD83D\uDE00x b"))
    }

    @Test
    fun avatarColourIsStablePerNameAndInRange() {
        val names = listOf("Vanja Rukavina", "Derek de Lint", "", "Sergio Hasselbaink")
        names.forEach { name ->
            val index = castAvatarColorIndex(name, 6)
            assertTrue(index in 0 until 6)
            assertEquals(index, castAvatarColorIndex(name, 6))
        }
    }
}
