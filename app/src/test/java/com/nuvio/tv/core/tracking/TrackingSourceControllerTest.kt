package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 104 S2a: the source pickers (C1, C2), the disconnect reset (A4) and the revoked-token fallback (C3). */
class TrackingSourceControllerTest {

    private val profiles = mutableMapOf(1 to TestPreferencesDataStore(), 2 to TestPreferencesDataStore())
    private val activeIdFlow = MutableStateFlow(1)
    private val profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeIdFlow }
    private val settings = ContinueWatchingSettingsDataStore(
        factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), ContinueWatchingSettingsDataStore.FEATURE) } answers { profiles.getValue(firstArg()) }
        },
        profileManager = profileManager
    )
    private val mdblist = FakeTrackingProvider(TrackingProviderId.MDBLIST, connected = true)
    private val simkl = FakeTrackingProvider(TrackingProviderId.SIMKL, connected = false)
    private val refreshed = mutableListOf<String>()
    private val mdblistProgress = mockk<TrackingProgressProvider> {
        every { providerId } returns TrackingProviderId.MDBLIST
        coEvery { refresh(any()) } answers { refreshed += "progress:${firstArg<TrackingRefreshIntent>()}" }
    }
    private val mdblistLibrary = mockk<TrackingLibraryProvider> {
        every { providerId } returns TrackingProviderId.MDBLIST
        coEvery { refresh(any()) } answers { refreshed += "library:${firstArg<TrackingRefreshIntent>()}" }
    }
    private val cache = mockk<ContinueWatchingEnrichmentCache>(relaxed = true)
    private val controller = TrackingSourceController(
        settings = settings,
        registry = TrackingProviderRegistry(setOf(mdblist, simkl)),
        progressProviders = TrackingProgressProviderRegistry(setOf(mdblistProgress)),
        libraryProviders = TrackingLibraryProviderRegistry(setOf(mdblistLibrary)),
        enrichmentCache = cache,
        profileManager = profileManager
    )

    @Test
    fun `a connected tracker can be chosen, the switch drops the cached rows and refreshes it once`() = runTest {
        assertTrue(controller.selectProgressSource(TrackingSource.MDBLIST))
        assertTrue(controller.selectLibrarySource(TrackingSource.MDBLIST))

        assertEquals(TrackingSource.MDBLIST, settings.getProgressSource(1))
        assertEquals(TrackingSource.MDBLIST, settings.getLibrarySource(1))
        assertEquals(listOf("progress:USER_INITIATED", "library:USER_INITIATED"), refreshed)
        coVerify(exactly = 1) { cache.saveInProgressSnapshot(emptyList(), true, 1) }
        coVerify(exactly = 1) { cache.saveNextUpSnapshot(emptyList(), true, 1) }
        assertEquals(TrackingSource.MDBLIST, controller.effectiveProgressSource.first())
    }

    @Test
    fun `choosing the current source again writes and refreshes nothing`() = runTest {
        assertTrue(controller.selectProgressSource(TrackingSource.NUVIO))
        assertTrue(refreshed.isEmpty())
        coVerify(exactly = 0) { cache.saveInProgressSnapshot(any(), any(), any()) }
    }

    @Test
    fun `a tracker that is not connected cannot be chosen`() = runTest {
        assertFalse(controller.selectProgressSource(TrackingSource.SIMKL))
        assertFalse(controller.selectLibrarySource(TrackingSource.SIMKL))
        assertEquals(TrackingSource.NUVIO, settings.getProgressSource(1))
        assertEquals(TrackingSource.NUVIO, settings.getLibrarySource(1))
    }

    @Test
    fun `a failing refresh after the switch keeps the choice`() = runTest {
        coEvery { mdblistProgress.refresh(any()) } throws IllegalStateException("offline")
        assertTrue(controller.selectProgressSource(TrackingSource.MDBLIST))
        assertEquals(TrackingSource.MDBLIST, settings.getProgressSource(1))
    }

    @Test
    fun `a revoked tracker keeps the choice but reads fall back to Nuvio until it reconnects (C3)`() = runTest {
        controller.selectProgressSource(TrackingSource.MDBLIST)
        controller.selectLibrarySource(TrackingSource.MDBLIST)

        mdblist.authenticated.value = false
        assertEquals(TrackingSource.NUVIO, controller.effectiveProgressSource.first())
        assertEquals(TrackingSource.NUVIO, controller.effectiveLibrarySource.first())
        assertEquals(TrackingSource.MDBLIST, settings.getProgressSource(1))

        mdblist.authenticated.value = true
        assertEquals(TrackingSource.MDBLIST, controller.effectiveProgressSource.first())
    }

    @Test
    fun `disconnect resets only the sources that pointed at that tracker, on that profile (A4)`() = runTest {
        settings.setProgressSource(TrackingSource.MDBLIST, 1)
        settings.setLibrarySource(TrackingSource.SIMKL, 1)
        settings.setProgressSource(TrackingSource.MDBLIST, 2)

        controller.onProviderDisconnected(TrackingProviderId.MDBLIST, profileId = 1)

        assertEquals(TrackingSource.NUVIO, settings.getProgressSource(1))
        assertEquals(TrackingSource.SIMKL, settings.getLibrarySource(1))
        assertEquals(TrackingSource.MDBLIST, settings.getProgressSource(2))
    }

    @Test
    fun `the registry's connection flow follows sign-in and is empty without trackers`() = runTest {
        val registry = TrackingProviderRegistry(setOf(mdblist, simkl))
        assertEquals(setOf(TrackingProviderId.MDBLIST), registry.connectedProviderIdsFlow.first())
        simkl.authenticated.value = true
        assertEquals(setOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL), registry.connectedProviderIdsFlow.first())
        assertEquals(emptySet<TrackingProviderId>(), TrackingProviderRegistry(emptySet()).connectedProviderIdsFlow.first())
    }

    @Test
    fun `the Library label names the tracker while it is the source and connected, else nothing (C4)`() = runTest {
        assertEquals(null, controller.activeLibraryTracker.first())
        controller.selectLibrarySource(TrackingSource.MDBLIST)
        assertEquals(TrackingProviderId.MDBLIST, controller.activeLibraryTracker.first()?.id)
        mdblist.authenticated.value = false
        assertEquals(null, controller.activeLibraryTracker.first())
    }
}
