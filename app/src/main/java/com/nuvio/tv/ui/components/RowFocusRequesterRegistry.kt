package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/**
 * Per-item focus requesters for a lazy row that restores focus when focus
 * re-enters it (Compose `focusRestorer` fallback + explicit restore requests).
 *
 * G6 home (2026-08-30) for the focus-restoring-row idiom previously hand-rolled
 * per row: a key-to-requester map pruned when the item list changes, plus the
 * [restorerTarget] resolution. Callers keep their own LazyRow scaffolding
 * (padding, focus gates, bring-into-view specs differ per surface); this class
 * owns only the shared machinery.
 */
@Stable
class RowFocusRequesterRegistry internal constructor() {

    private val requesters = mutableMapOf<String, FocusRequester>()

    /**
     * Requester for [key], created on first use. Safe to call before the item's
     * composition body runs (the restore path does); the instance returned is
     * the one the item's `focusRequester` modifier attaches.
     */
    fun requesterFor(key: String): FocusRequester =
        requesters.getOrPut(key) { FocusRequester() }

    /** Drop requesters for keys that left the item list. */
    fun retainKeys(validKeys: Set<String>) {
        requesters.keys.retainAll(validKeys)
    }

    /**
     * Fallback target for `focusRestorer(...)` (evaluated at composition): [preferredIndex] when
     * its item is currently visible, else the first visible item when
     * [fallbackToFirstVisible], else [FocusRequester.Default] so the framework's
     * default focus search decides. Only visible indices resolve — W5.1:
     * requesters are attachable only while composed, and returning an
     * unattached requester throws on fast D-pad travel.
     *
     * Load-bearing invariant: [keyAt] must return, for each index, the same key
     * the item at that index passes to [requesterFor] when it composes. Visible
     * implies composed implies registered, so resolution then finds an attached
     * requester; the lazy create below only fires for pre-composition restores.
     *
     * Detail's restore-token rows (return-from-detail) pass the restore target (or 0) with
     * `fallbackToFirstVisible = false`, keeping their strict first-item-only fallback; the person
     * page's credit row passes its last focused card and `true` (else the first visible card).
     */
    fun restorerTarget(
        visibleIndices: List<Int>,
        keyAt: (Int) -> String?,
        preferredIndex: Int?,
        fallbackToFirstVisible: Boolean
    ): FocusRequester {
        val targetIndex = preferredIndex?.takeIf { it in visibleIndices }
            ?: if (fallbackToFirstVisible) visibleIndices.firstOrNull() else null
        return targetIndex?.let(keyAt)?.let { requesterFor(it) } ?: FocusRequester.Default
    }
}

@Composable
fun rememberRowFocusRequesterRegistry(): RowFocusRequesterRegistry =
    remember { RowFocusRequesterRegistry() }
