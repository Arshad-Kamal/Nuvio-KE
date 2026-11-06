package com.nuvio.tv.data.local

import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 27e: Library's per-profile play counts (movies only, one per completed play). */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchCountDataStoreTest {

    private val profiles = mapOf(1 to TestPreferencesDataStore(), 2 to TestPreferencesDataStore())
    private val activeProfileIdFlow = MutableStateFlow(1)
    private val store = WatchCountDataStore(
        factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), WatchCountDataStore.FEATURE) } answers { profiles.getValue(firstArg()) }
        },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeProfileIdFlow }
    )

    @Test
    fun `each completed movie play adds one`() = runTest {
        store.recordCompletedPlay("tt1", "movie")
        store.recordCompletedPlay("tt1", "Movie")
        store.recordCompletedPlay("tt2", "movie")

        assertEquals(mapOf("tt1" to 2, "tt2" to 1), store.counts.first())
    }

    @Test
    fun `series, other types and blank ids are not counted`() = runTest {
        store.recordCompletedPlay("tt1", "series")
        store.recordCompletedPlay("tt2", "tv")
        store.recordCompletedPlay("tt3", "channel")
        store.recordCompletedPlay(" ", "movie")

        assertEquals(emptyMap<String, Int>(), store.counts.first())
    }

    @Test
    fun `counts are per profile`() = runTest {
        store.recordCompletedPlay("tt1", "movie")
        activeProfileIdFlow.value = 2

        assertEquals(emptyMap<String, Int>(), store.counts.first())
        store.recordCompletedPlay("tt1", "movie")
        assertEquals(mapOf("tt1" to 1), store.counts.first())
        activeProfileIdFlow.value = 1
        assertEquals(mapOf("tt1" to 1), store.counts.first())
    }
}
