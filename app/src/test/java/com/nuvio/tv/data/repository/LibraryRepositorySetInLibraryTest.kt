package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * setInLibrary brings membership to the desired state through the same add/remove +
 * sync path toggleDefault uses, and is a no-op when already there.
 */
class LibraryRepositorySetInLibraryTest {

    private val profileManager = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(PROFILE_ID)
    }
    private val libraryPreferences = mockk<LibraryPreferences>(relaxed = true) {
        every { libraryItems } returns flowOf(emptyList())
    }
    private val librarySyncService = mockk<LibrarySyncService> {
        coEvery { pushToRemote(any()) } returns Result.success(Unit)
    }
    private val authManager = mockk<AuthManager> {
        every { isAuthenticated } returns true
    }
    private val repository = LibraryRepositoryImpl(
        libraryPreferences = libraryPreferences,
        librarySyncService = librarySyncService,
        authManager = authManager,
        metaRepository = mockk<MetaRepository>(relaxed = true),
        profileManager = profileManager
    ).apply { hasCompletedInitialPull = true }

    @Test
    fun `adds and syncs when absent and desired in`() = runTest {
        stubContains(false)

        repository.setInLibrary(input(), inLibrary = true)

        coVerify(exactly = 1) {
            libraryPreferences.addItem(match { it.id == ITEM_ID && it.type == "movie" }, PROFILE_ID)
        }
        coVerify(exactly = 0) { libraryPreferences.removeItem(any(), any(), any()) }
        coVerify(timeout = SYNC_TIMEOUT_MS, exactly = 1) { librarySyncService.pushToRemote(PROFILE_ID) }
        // A timed verify returns at the first match; settle past the debounce to catch a second push.
        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 1) { librarySyncService.pushToRemote(any()) }
    }

    @Test
    fun `removes and syncs when present and desired out`() = runTest {
        stubContains(true)

        repository.setInLibrary(input(), inLibrary = false)

        coVerify(exactly = 1) { libraryPreferences.removeItem(ITEM_ID, "movie", PROFILE_ID) }
        coVerify(exactly = 0) { libraryPreferences.addItem(any(), any()) }
        coVerify(timeout = SYNC_TIMEOUT_MS, exactly = 1) { librarySyncService.pushToRemote(PROFILE_ID) }
        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 1) { librarySyncService.pushToRemote(any()) }
    }

    @Test
    fun `already in the desired state writes and syncs nothing`() = runTest {
        stubContains(true)
        repository.setInLibrary(input(), inLibrary = true)
        stubContains(false)
        repository.setInLibrary(input(), inLibrary = false)

        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 0) { libraryPreferences.addItem(any(), any()) }
        coVerify(exactly = 0) { libraryPreferences.removeItem(any(), any(), any()) }
        coVerify(exactly = 0) { librarySyncService.pushToRemote(any()) }
    }

    @Test
    fun `add payload is identical to the toggleDefault payload`() = runTest {
        stubContains(false)
        val fromToggle = slot<SavedLibraryItem>()
        val fromSet = slot<SavedLibraryItem>()
        coEvery { libraryPreferences.addItem(capture(fromToggle), any()) } returns Unit
        repository.toggleDefault(input())
        coEvery { libraryPreferences.addItem(capture(fromSet), any()) } returns Unit
        repository.setInLibrary(input(), inLibrary = true)

        assertEquals(fromToggle.captured, fromSet.captured)
        // Golden pin: the shared payload maps LibraryEntryInput field by field
        // (addedAt is left at its 0L default; the add path stamps it downstream).
        val expected = SavedLibraryItem(
            id = ITEM_ID,
            type = "movie",
            name = "Movie",
            poster = "poster",
            posterShape = PosterShape.POSTER,
            background = null,
            description = "desc",
            releaseInfo = "2024",
            imdbRating = null,
            genres = listOf("Drama"),
            addonBaseUrl = "https://addon.example",
            logo = null,
            addedAt = 0L
        )
        assertEquals(expected, fromSet.captured)
    }

    private fun stubContains(value: Boolean) {
        coEvery { libraryPreferences.containsItem(ITEM_ID, "movie", PROFILE_ID) } returns value
    }

    private fun input() = LibraryEntryInput(
        itemId = ITEM_ID,
        itemType = "movie",
        title = "Movie",
        poster = "poster",
        description = "desc",
        releaseInfo = "2024",
        genres = listOf("Drama"),
        addonBaseUrl = "https://addon.example"
    )

    private companion object {
        const val PROFILE_ID = 1
        const val ITEM_ID = "tt0000003"
        // triggerRemoteSync debounces the push by 500 ms.
        const val SYNC_TIMEOUT_MS = 3_000L
        // Debounce + 500 ms: long enough for a debounced push (or a duplicate) to land.
        const val DEBOUNCE_SETTLE_MS = 1_000L
    }
}
