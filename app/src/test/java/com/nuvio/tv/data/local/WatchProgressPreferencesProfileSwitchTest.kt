package com.nuvio.tv.data.local

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L9-39: the hot active-profile snapshot outlives a profile switch; reads for the new
 * active profile must never be served the previous profile's entries.
 */
class WatchProgressPreferencesProfileSwitchTest {
    private val activeProfileId = MutableStateFlow(1)
    private val stores = mutableMapOf<Pair<Int, String>, TestPreferencesDataStore>()
    private val preferences: WatchProgressPreferences

    init {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            synchronized(stores) {
                stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesDataStore() }
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        preferences = WatchProgressPreferences(factory, profileManager)
    }

    @Test
    fun `reads after a switch never yield the previous profile's snapshot`() = runTest {
        preferences.saveProgress(progress("a-item"), profileId = 1)
        preferences.saveProgress(progress("b-item"), profileId = 2)
        // Warm the hot snapshot with profile 1 (it may first replay the pre-save empty state).
        assertEquals(
            listOf("a-item"),
            preferences.allProgress.first { it.isNotEmpty() }.map { it.contentId }
        )

        activeProfileId.value = 2

        assertEquals(listOf("b-item"), preferences.allProgress.first().map { it.contentId })
        assertNull(preferences.getProgress("a-item").first())
        assertEquals("b-item", preferences.getProgress("b-item").first()?.contentId)
    }

    private fun progress(contentId: String) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = 100L
    )
}
