package com.nuvio.tv.ui.screens.settings.trackers

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.core.tracking.TrackingSource.MDBLIST
import com.nuvio.tv.core.tracking.TrackingSource.NUVIO
import com.nuvio.tv.core.tracking.TrackingSource.SIMKL
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.repository.LibraryTransferMode
import com.nuvio.tv.data.repository.LibraryTransferPreview
import com.nuvio.tv.data.repository.LibraryTransferReadException
import com.nuvio.tv.data.repository.LibraryTransferService
import com.nuvio.tv.data.repository.LibraryTransferState
import com.nuvio.tv.data.repository.computeTransferPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Spec 104 S4d: the Transfer library panel's From/To defaults and plan states. */
class LibraryTransferViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val service = mockk<LibraryTransferService> {
        every { state } returns MutableStateFlow(LibraryTransferState.Idle)
        coEvery { preview(any(), any(), any()) } answers {
            LibraryTransferPreview(firstArg(), secondArg(), thirdArg(), computeTransferPlan(emptyList(), emptyList()))
        }
    }

    private fun viewModel(
        library: TrackingSource,
        vararg connected: TrackingProviderId,
        withLibrary: Set<TrackingProviderId> = TrackingProviderId.entries.toSet()
    ) = LibraryTransferViewModel(
        service = service,
        registry = mockk<TrackingProviderRegistry> { every { connectedProviderIdsFlow } returns flowOf(connected.toSet()) },
        libraryProviders = mockk<TrackingLibraryProviderRegistry> {
            every { provider(any()) } answers { if (firstArg<TrackingProviderId>() in withLibrary) mockk() else null }
        },
        sourceSettings = mockk<ContinueWatchingSettingsDataStore> { every { librarySource } returns flowOf(library) },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(3) },
        authManager = mockk<AuthManager> { every { isAuthenticated } returns true }
    )

    private fun TestScope.opened(vm: LibraryTransferViewModel): TransferPanelUi {
        backgroundScope.launch { vm.ui.collect {} }
        vm.open()
        advanceUntilIdle()
        return vm.ui.value
    }

    @Test
    fun `with a tracker as the Library source, Nuvio goes into it`() = runTest {
        val ui = opened(viewModel(MDBLIST, TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL))

        assertEquals(NUVIO, ui.from)
        assertEquals(MDBLIST, ui.to)
        assertEquals(listOf(MDBLIST), ui.destinations)
        assertEquals(listOf(NUVIO, MDBLIST, SIMKL), ui.sources)
        assertTrue(ui.plan is TransferPlanUi.Ready)
        assertEquals(3, (ui.plan as TransferPlanUi.Ready).preview.profileId)
    }

    @Test
    fun `a connected tracker with no library is neither a source nor a destination`() = runTest {
        val ui = opened(
            viewModel(NUVIO, TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL, withLibrary = setOf(TrackingProviderId.SIMKL))
        )

        assertEquals(SIMKL, ui.from)
        assertEquals(NUVIO, ui.to)
        // Nuvio is no source here: Simkl is not yet a transfer destination (Spec 106 T7).
        assertEquals(listOf(SIMKL), ui.sources)
        assertEquals(listOf(NUVIO), ui.destinations)
        coVerify(exactly = 0) { service.preview(any(), MDBLIST, any()) }
    }

    @Test
    fun `with Nuvio as the Library source, the first tracker comes into Nuvio`() = runTest {
        val ui = opened(viewModel(NUVIO, TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL))

        assertEquals(MDBLIST, ui.from)
        assertEquals(NUVIO, ui.to)
    }

    @Test
    fun `with only Simkl connected, Simkl is the only From and Nuvio the only To`() = runTest {
        val vm = viewModel(SIMKL, TrackingProviderId.SIMKL)
        val ui = opened(vm)

        assertEquals(SIMKL, ui.from)
        assertEquals(NUVIO, ui.to)
        assertEquals(listOf(SIMKL), ui.sources)
        vm.selectTo(SIMKL)
        assertEquals(NUVIO, vm.ui.value.to)
    }

    @Test
    fun `a failed read names the end that failed, and Try again reads again`() = runTest {
        coEvery { service.preview(any(), any(), any()) } throws LibraryTransferReadException(MDBLIST, IOException())
        val vm = viewModel(MDBLIST, TrackingProviderId.MDBLIST)

        assertEquals(TransferPlanUi.Failed(MDBLIST), opened(vm).plan)

        coEvery { service.preview(any(), any(), any()) } answers {
            LibraryTransferPreview(firstArg(), secondArg(), thirdArg(), computeTransferPlan(emptyList(), emptyList()))
        }
        vm.retryPlan()
        advanceUntilIdle()
        assertTrue(vm.ui.value.plan is TransferPlanUi.Ready)
    }

    @Test
    fun `choosing another From recomputes To, and Start hands the plan over`() = runTest {
        val vm = viewModel(MDBLIST, TrackingProviderId.MDBLIST)
        opened(vm)

        vm.selectFrom(MDBLIST)
        advanceUntilIdle()
        assertEquals(NUVIO, vm.ui.value.to)
        val preview = (vm.ui.value.plan as TransferPlanUi.Ready).preview
        every { service.start(any(), any()) } returns true
        vm.start(LibraryTransferMode.COPY)
        verify { service.start(preview, LibraryTransferMode.COPY) }
    }

    @Test
    fun `reopening during a run reads nothing`() = runTest {
        every { service.state } returns MutableStateFlow(LibraryTransferState.Running(NUVIO, MDBLIST, LibraryTransferMode.COPY, 1, 4))
        val vm = viewModel(MDBLIST, TrackingProviderId.MDBLIST)

        opened(vm)

        coVerify(exactly = 0) { service.preview(any(), any(), any()) }
    }
}
