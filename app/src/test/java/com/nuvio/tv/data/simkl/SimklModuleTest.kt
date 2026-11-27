package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.domain.model.UserProfile
import dagger.Lazy
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 K/S2b: the module's own wiring (sign-out on an id change, what a grant change clears). */
@OptIn(ExperimentalCoroutinesApi::class)
class SimklModuleTest {

    private val engine = FakeSimklEngine()
    private val clock = FakeClock()
    private val client = simklClient(engine, clock)
    private val accounts = accountStore()
    private val session = SimklSession(client, SimklTokens(client, TEST_CONFIG, accounts), SimklQuota { clock.now })

    @Test
    fun `removing the app id signs every profile out and puts its sources back to Nuvio`() = runTest(UnconfinedTestDispatcher()) {
        val dir = Files.createTempDirectory("simkl").toFile()
        val store = SimklAppIdStore(dir.resolve("simkl_app_id"), UnconfinedTestDispatcher(testScheduler), backgroundScope)
        store.save("a3f9c2e17b4d5e6f708192a3b4c5d6e7")
        val deviceAuth = mockk<SimklDeviceAuth>(relaxed = true)
        val sources = mockk<TrackingSourceController>(relaxed = true)
        val profiles = mockk<ProfileManager> {
            every { this@mockk.profiles } returns MutableStateFlow(listOf(UserProfile(1, "A", "#fff"), UserProfile(2, "B", "#fff")))
            every { activeProfileId } returns MutableStateFlow(3)
        }

        SimklModule.provideSimklAppIdSetup(store, client, deviceAuth, Lazy { sources }, profiles).remove()

        listOf(1, 2, 3).forEach { profileId ->
            coVerifyOrder {
                deviceAuth.disconnect(profileId)
                sources.onProviderDisconnected(TrackingProviderId.SIMKL, profileId)
            }
        }
        assertNull(store.get())
    }

    @Test
    fun `a grant change drops the profile's snapshot, kept marks, screen overlay and library changes`() = runTest {
        val dir = Files.createTempDirectory("simkl").toFile()
        val syncStore = SimklSyncStore(dir, Dispatchers.Unconfined)
        val pending = SimklPendingMarks(dir, Dispatchers.Unconfined)
        val progress = mockk<SimklTrackingProgressProvider>(relaxed = true)
        val library = mockk<SimklTrackingLibraryProvider>(relaxed = true)
        syncStore.saveIf(1, 0L, SimklSnapshot())
        pending.add(1, listOf(TrackingHistoryItem(TrackingMediaReference(TrackingMediaKind.MOVIE, "Film", 2020), 1L)))

        // No account stored: disconnect revokes nothing and still runs the grant change.
        SimklModule.provideSimklDeviceAuth(client, TEST_CONFIG, accounts, session, syncStore, pending, progress, library).disconnect(1)

        verify { progress.forget(1) }
        verify { library.forget(1) }
        assertNull(syncStore.get(1))
        assertTrue(pending.get(1).isEmpty())
        assertTrue(engine.calls.isEmpty())
    }
}
