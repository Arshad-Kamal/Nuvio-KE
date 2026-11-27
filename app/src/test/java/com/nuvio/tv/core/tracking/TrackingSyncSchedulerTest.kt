package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 104 S2d: trackers refresh on app resume at most every 5 minutes, and on every profile switch; never on a timer. */
class TrackingSyncSchedulerTest {

    private val refreshed = mutableListOf<String>()
    private val profileIdFlow = MutableStateFlow<Int?>(1)
    private var now = 0L
    private val mdblist = FakeTrackingProvider(TrackingProviderId.MDBLIST, connected = true)
    private val simkl = FakeTrackingProvider(TrackingProviderId.SIMKL, connected = false)

    private fun progress(id: TrackingProviderId, connected: Boolean) = mockk<TrackingProgressProvider> {
        every { providerId } returns id
        every { isAuthenticated } returns flowOf(connected)
        coEvery { refresh(any()) } answers { refreshed += "progress:${id.storageId}:${profileIdFlow.value}" }
    }

    private fun library(id: TrackingProviderId) = mockk<TrackingLibraryProvider> {
        every { providerId } returns id
        coEvery { refresh(any()) } answers { refreshed += "library:${id.storageId}:${profileIdFlow.value}" }
    }

    private fun TestScope.scheduler(providers: Set<TrackingProvider> = setOf(mdblist, simkl)) = TrackingSyncScheduler(
        progressRefresh = TrackingProgressRefreshCoordinator(
            TrackingProgressProviderRegistry(setOf(progress(TrackingProviderId.MDBLIST, true), progress(TrackingProviderId.SIMKL, false)))
        ),
        libraryProviders = TrackingLibraryProviderRegistry(setOf(library(TrackingProviderId.MDBLIST), library(TrackingProviderId.SIMKL))),
        registry = TrackingProviderRegistry(providers),
        profileManager = mockk<ProfileManager> { every { storedActiveProfileId } returns profileIdFlow },
        scope = backgroundScope,
        nowMs = { now }
    )

    @Test
    fun `resume refreshes the connected trackers at most every five minutes`() = runTest {
        val scheduler = scheduler()
        scheduler.onAppForeground()
        testScheduler.runCurrent()
        assertEquals(listOf("progress:mdblist:1", "library:mdblist:1"), refreshed)

        now += TRACKING_AUTOMATIC_REFRESH_INTERVAL_MS - 1
        scheduler.onAppForeground()
        testScheduler.runCurrent()
        assertEquals(2, refreshed.size)

        now += 1
        scheduler.onAppForeground()
        testScheduler.runCurrent()
        assertEquals(4, refreshed.size)
    }

    @Test
    fun `a profile switch always refreshes, for the new profile`() = runTest {
        val scheduler = scheduler()
        scheduler.onAppForeground()
        testScheduler.runCurrent()
        profileIdFlow.value = 2
        testScheduler.runCurrent()
        assertEquals(listOf("progress:mdblist:1", "library:mdblist:1", "progress:mdblist:2", "library:mdblist:2"), refreshed)
    }

    @Test
    fun `a forced switch run counts for its profile's throttle, and never for another profile's`() = runTest {
        val scheduler = scheduler()
        scheduler.onAppForeground()
        testScheduler.runCurrent()
        profileIdFlow.value = 2
        testScheduler.runCurrent()
        assertEquals(4, refreshed.size)
        now += 60_000
        scheduler.onAppForeground()
        testScheduler.runCurrent()
        assertEquals(4, refreshed.size)
        profileIdFlow.value = 1
        testScheduler.runCurrent()
        assertEquals(listOf("progress:mdblist:1", "library:mdblist:1"), refreshed.drop(4))
    }

    @Test
    fun `the stored profile loading is not a switch, and nothing runs for the default before it`() = runTest {
        profileIdFlow.value = null
        val scheduler = scheduler()
        testScheduler.runCurrent()
        scheduler.onAppForeground()
        testScheduler.runCurrent()
        assertEquals(emptyList<String>(), refreshed)
        profileIdFlow.value = 3
        testScheduler.runCurrent()
        assertEquals(listOf("progress:mdblist:3", "library:mdblist:3"), refreshed)
    }

    @Test
    fun `a build with no tracker does nothing`() = runTest {
        val scheduler = scheduler(providers = emptySet())
        scheduler.onAppForeground()
        profileIdFlow.value = 2
        testScheduler.runCurrent()
        assertEquals(emptyList<String>(), refreshed)
    }
}
