package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T23 (upstream-sync Register A2 P24, NM 7e4e57195): once the session marked the title
 * completed, a later in-progress save (unknown duration -> 5% fallback, stale position)
 * must not overwrite the completed entry.
 */
class WatchProgressSaveActionTest {

    @Test
    fun `first completed save marks completed`() {
        assertEquals(WatchProgressSaveAction.MARK_COMPLETED, watchProgressSaveAction(isCompleted = true, alreadyMarkedCompleted = false))
    }

    @Test
    fun `later completed saves re-prune`() {
        assertEquals(WatchProgressSaveAction.REPRUNE_COMPLETED, watchProgressSaveAction(isCompleted = true, alreadyMarkedCompleted = true))
    }

    @Test
    fun `in-progress save before completion saves`() {
        assertEquals(WatchProgressSaveAction.SAVE_IN_PROGRESS, watchProgressSaveAction(isCompleted = false, alreadyMarkedCompleted = false))
    }

    @Test
    fun `in-progress save after completion only re-prunes, keeping the completed entry`() {
        // Re-prune, not nothing: an earlier in-progress Watch Next upsert can land after the
        // completion prune (TvRecommendationManager orders no writes).
        assertEquals(WatchProgressSaveAction.REPRUNE_COMPLETED, watchProgressSaveAction(isCompleted = false, alreadyMarkedCompleted = true))
    }
}
