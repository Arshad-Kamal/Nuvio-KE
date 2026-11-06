package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the null season/episode semantics of [WatchedItemsPreferences.unmarkAsWatched]:
 * a whole-content removal must sweep per-episode entries too (mirroring
 * WatchProgressPreferences.removeProgress), not just the series-level key.
 */
class WatchedItemsPreferencesUnmarkTest {

    private val store = TestPreferencesDataStore()
    private val preferences: WatchedItemsPreferences

    init {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        preferences = WatchedItemsPreferences(factory, profileManager)
    }

    @Test
    fun `unmark with null season and episode removes series-level and episode entries`() = runTest {
        seedItems(
            seriesLevel("tt1"),
            episode("tt1", 1, 1),
            episode("tt1", 1, 2),
            episode("tt2", 1, 1)
        )

        preferences.unmarkAsWatched("tt1", season = null, episode = null)

        val remaining = preferences.allItems.first()
        assertEquals(listOf("tt2" to (1 to 1)), remaining.map { it.contentId to (it.season!! to it.episode!!) })
    }

    @Test
    fun `unmark with explicit episode removes only that episode`() = runTest {
        seedItems(
            seriesLevel("tt1"),
            episode("tt1", 1, 1),
            episode("tt1", 1, 2)
        )

        preferences.unmarkAsWatched("tt1", season = 1, episode = 1)

        val remaining = preferences.allItems.first().map { it.season to it.episode }
        assertEquals(listOf(null to null, 1 to 2), remaining)
    }

    private suspend fun seedItems(vararg items: WatchedItem) {
        preferences.markAsWatchedBatch(items.toList(), profileId = 1)
    }

    private fun seriesLevel(contentId: String) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = contentId,
        season = null,
        episode = null,
        watchedAt = 0L
    )

    private fun episode(contentId: String, season: Int, episode: Int) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = "$contentId s$season e$episode",
        season = season,
        episode = episode,
        watchedAt = 0L
    )

}
