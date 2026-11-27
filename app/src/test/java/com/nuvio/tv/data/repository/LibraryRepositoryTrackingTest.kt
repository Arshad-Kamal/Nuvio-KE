package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 104 S2c: with a tracker as the Library source, Library reads and writes go to it; the local library is left as is. */
class LibraryRepositoryTrackingTest {

    private val libraryPreferences = mockk<LibraryPreferences>(relaxed = true) {
        every { libraryItems } returns flowOf(emptyList())
        every { isInLibrary(any(), any()) } returns flowOf(false)
    }
    private val trackerItems = listOf(entry("tt7"))
    private val inTracker = MutableStateFlow(true)
    private val provider = mockk<TrackingLibraryProvider>(relaxed = true) {
        every { providerId } returns TrackingProviderId.MDBLIST
        every { items } returns flowOf(trackerItems)
        every { isRefreshing } returns flowOf(true)
        every { isInLibrary(any(), any()) } returns inTracker
    }
    private val active = MutableStateFlow<TrackingLibraryProvider?>(provider)
    private val repository = LibraryRepositoryImpl(
        libraryPreferences = libraryPreferences,
        librarySyncService = mockk<LibrarySyncService>(relaxed = true),
        authManager = mockk<AuthManager> { every { isAuthenticated } returns true },
        metaRepository = mockk<MetaRepository>(relaxed = true),
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(PROFILE_ID) },
        trackingSources = mockk { every { activeLibraryProvider } returns active }
    ).apply { hasCompletedInitialPull = true }

    @Test
    fun `reads come from the tracker, and from the local library again after switching back`() = runTest {
        assertEquals(trackerItems, repository.libraryItems.first())
        assertTrue(repository.isInLibrary("tt7", "movie").first())
        assertTrue(repository.isSyncing.first())

        active.value = null
        assertEquals(emptyList<LibraryEntry>(), repository.libraryItems.first())
        assertEquals(false, repository.isInLibrary("tt7", "movie").first())
    }

    @Test
    fun `the toggle writes the opposite of the tracker's membership, on the active profile, never locally`() = runTest {
        repository.toggleDefault(input())
        repository.setInLibrary(input(), inLibrary = true)

        coVerify(exactly = 1) { provider.setInLibrary(PROFILE_ID, input(), false) }
        coVerify(exactly = 1) { provider.setInLibrary(PROFILE_ID, input(), true) }
        coVerify(exactly = 0) { libraryPreferences.addItem(any(), any()) }
        coVerify(exactly = 0) { libraryPreferences.removeItem(any(), any(), any()) }
    }

    @Test
    fun `a failing tracker write reaches the caller and does not fall back to a local write`() = runTest {
        coEvery { provider.setInLibrary(any(), any(), any()) } throws IllegalStateException("offline")
        assertTrue(runCatching { repository.toggleDefault(input()) }.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 1) { provider.setInLibrary(PROFILE_ID, input(), false) }
        coVerify(exactly = 0) { libraryPreferences.addItem(any(), any()) }
        coVerify(exactly = 0) { libraryPreferences.removeItem(any(), any(), any()) }
    }

    private companion object {
        const val PROFILE_ID = 1
        fun input() = LibraryEntryInput(itemId = "tt7", itemType = "movie", title = "Seven")
        fun entry(id: String) = LibraryEntry(
            id = id, type = "movie", name = id, poster = null, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
        )
    }
}
