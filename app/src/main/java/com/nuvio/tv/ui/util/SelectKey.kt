package com.nuvio.tv.ui.util

import android.view.KeyEvent

/**
 * True for key codes that activate the focused item: D-pad center, Enter,
 * numpad Enter.
 *
 * G6 consolidation (2026-08-30): previously ten byte-identical private copies
 * across ui/components and ui/screens (ContentCard, GridContentCard,
 * NuvioDialog, ContinueWatchingSection, EpisodeOptionsOverlay, EpisodesSection,
 * HeroSection, ModernHomeRows, LibraryScreen — including its name-differing
 * `isCloudSearchSelectKey` — and ProfileSelectionScreen as `isProfileSelectKey`,
 * also consumed by DpadUnlockOverlay).
 *
 * Not covered: the inline Compose-`Key` conditions in DpadComboSetupScreen
 * (deliberately omits numpad Enter) and
 * CommentsSection — different input type, kept local.
 */
fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}

/**
 * [isSelectKey] plus the MENU key — the "activate or long-press-menu"
 * gate used by card rows and dialogs. G6 consolidation (2026-08-30):
 * previously named only in HeroSection (`isSelectOrMenuKey`) and inlined
 * identically at eight further sites.
 */
fun isSelectOrMenuKey(keyCode: Int): Boolean {
    return isSelectKey(keyCode) || keyCode == KeyEvent.KEYCODE_MENU
}
