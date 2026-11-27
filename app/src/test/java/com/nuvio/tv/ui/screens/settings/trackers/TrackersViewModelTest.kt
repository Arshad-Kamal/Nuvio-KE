package com.nuvio.tv.ui.screens.settings.trackers

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.server.PasteKeyPageStrings
import com.nuvio.tv.core.server.PasteKeyServer
import com.nuvio.tv.core.server.StartedPasteKeyServer
import com.nuvio.tv.core.tracking.FakeDeviceAuth
import com.nuvio.tv.core.tracking.FakeProviderSetup
import com.nuvio.tv.core.tracking.TrackingAccountStatus
import com.nuvio.tv.core.tracking.TrackingKeyAuth
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.FakeTrackingProvider
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingDevicePoll
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderId.MDBLIST
import com.nuvio.tv.core.tracking.TrackingProviderId.SIMKL
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.simkl.RelatedTitlesSource
import com.nuvio.tv.data.simkl.SimklAnimeSeasons
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Spec 104 S3c: Settings › Trackers with a fake provider (no real tracker exists until Specs 105/106). */
class TrackersViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val mdblist = FakeTrackingProvider(MDBLIST, connected = true)
    private val simklSetup = FakeProviderSetup(hint = "7f3a")
    private val simkl = FakeTrackingProvider(SIMKL, connected = false, setup = simklSetup)
    private val pastePagesStarted = mutableListOf<Pair<PasteKeyPageStrings, Int>>()
    private var pasteSubmit: ((PasteKeyServer, String) -> PasteCheck)? = null
    private var pasteServer: PasteKeyServer? = null
    private val mdblistAuth = mdblist.deviceAuth as FakeDeviceAuth
    private val simklAuth = simkl.deviceAuth as FakeDeviceAuth

    private val accounts = mapOf(
        MDBLIST to MutableStateFlow<TrackingAccount?>(TrackingAccount("a", accountName = "arshadk", lastSyncedAtEpochMs = 42L)),
        SIMKL to MutableStateFlow<TrackingAccount?>(null)
    )
    private val progressSource = MutableStateFlow(TrackingSource.NUVIO)
    private val librarySource = MutableStateFlow(TrackingSource.NUVIO)
    private val animeSeasons = MutableStateFlow(SimklAnimeSeasons.PER_SHOW)
    private val animeSeasonsSaved = mutableListOf<Pair<Int, SimklAnimeSeasons>>()
    private val relatedSource = MutableStateFlow(RelatedTitlesSource.TMDB)
    private val relatedSaved = mutableListOf<Pair<Int, RelatedTitlesSource>>()
    private val refreshed = mutableListOf<String>()
    private var refreshFailure: Exception? = null

    private val sourceController = mockk<TrackingSourceController>(relaxed = true)
    private val activeProfileFlow = MutableStateFlow(3)
    private val accountReadsFor = mutableListOf<Int>()

    private fun progress(id: TrackingProviderId) = mockk<TrackingProgressProvider> {
        every { providerId } returns id
        coEvery { refresh(any()) } answers { refreshFailure?.let { throw it }; refreshed += "progress:${id.storageId}" }
    }

    private fun library(id: TrackingProviderId) = mockk<TrackingLibraryProvider> {
        every { providerId } returns id
        coEvery { refresh(any()) } answers { refreshed += "library:${id.storageId}" }
    }

    private fun viewModel(
        providers: Set<TrackingProvider> = setOf(mdblist, simkl),
        libraryIds: Set<TrackingProviderId> = setOf(MDBLIST, SIMKL)
    ) = TrackersViewModel(
        registry = TrackingProviderRegistry(providers),
        accountStore = mockk<TrackingAccountStore> {
            every { account(any()) } answers { accounts.getValue(firstArg()) }
            coEvery { get(any(), any()) } answers {
                accountReadsFor += firstArg<Int>()
                accounts.getValue(secondArg()).value
            }
        },
        sourceController = sourceController,
        sourceSettings = mockk<ContinueWatchingSettingsDataStore> {
            every { this@mockk.progressSource } returns this@TrackersViewModelTest.progressSource
            every { this@mockk.librarySource } returns this@TrackersViewModelTest.librarySource
            every { activeSimklAnimeSeasons } returns animeSeasons
            coEvery { setSimklAnimeSeasons(any(), any()) } answers { animeSeasonsSaved += secondArg<Int>() to firstArg() }
            every { relatedTitlesSource } returns relatedSource
            coEvery { setRelatedTitlesSource(any(), any()) } answers { relatedSaved += secondArg<Int>() to firstArg() }
        },
        progressProviders = TrackingProgressProviderRegistry(setOf(progress(MDBLIST), progress(SIMKL))),
        libraryProviders = TrackingLibraryProviderRegistry(libraryIds.mapTo(mutableSetOf(), ::library)),
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeProfileFlow },
        startPastePage = { strings, port, submit ->
            pastePagesStarted += strings to port
            pasteSubmit = submit
            val server = PasteKeyServer("0123456789abcdef0123456789abcdef", { "" }, submit, 1_024, "127.0.0.1", 0)
            pasteServer = server
            StartedPasteKeyServer(server, "http://tv/p/")
        },
        nowEpochMs = { mainRule.dispatcher.scheduler.currentTime }
    )

    private fun TestScope.collect(vm: TrackersViewModel): MutableList<TrackersEvent> {
        backgroundScope.launch { vm.uiState.collect {} }
        val events = mutableListOf<TrackersEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        return events
    }

    @Test
    fun `rows show each tracker's account, and the source pickers list Nuvio plus connected trackers`() = runTest {
        val vm = viewModel()
        collect(vm)
        val state = vm.uiState.value
        assertEquals(listOf(MDBLIST, SIMKL), state.rows.map { it.id })
        assertEquals(TrackerStatus.CONNECTED, state.rows[0].status)
        assertEquals("arshadk", state.rows[0].accountName)
        assertEquals(42L, state.rows[0].lastSyncedAtEpochMs)
        assertEquals(TrackerStatus.NOT_CONNECTED, state.rows[1].status)
        assertEquals(listOf(TrackingSource.NUVIO, TrackingSource.MDBLIST), state.sourceChoices)
        assertTrue(state.sourcesEnabled)
    }

    @Test
    fun `Simkl's anime seasons mode shows the active profile's and is saved for it`() = runTest {
        val vm = viewModel()
        collect(vm)
        assertEquals(SimklAnimeSeasons.PER_SHOW, vm.uiState.value.simklAnimeSeasons)
        vm.selectSimklAnimeSeasons(SimklAnimeSeasons.PER_SEASON)
        runCurrent()
        assertEquals(listOf(3 to SimklAnimeSeasons.PER_SEASON), animeSeasonsSaved)
        animeSeasons.value = SimklAnimeSeasons.PER_SEASON
        runCurrent()
        assertEquals(SimklAnimeSeasons.PER_SEASON, vm.uiState.value.simklAnimeSeasons)
    }

    @Test
    fun `More like this offers Simkl only while Simkl is connected, and saves for the active profile`() = runTest {
        val vm = viewModel()
        collect(vm)
        // MDBList alone is connected: the row is listed (Simkl is in this build) but dimmed, TMDB the only option.
        assertTrue(vm.uiState.value.hasRelatedSource)
        assertFalse(vm.uiState.value.relatedSourceEnabled)
        assertEquals(listOf(RelatedTitlesSource.TMDB), vm.uiState.value.relatedChoices)
        vm.selectRelatedSource(RelatedTitlesSource.SIMKL)
        runCurrent()
        assertEquals(emptyList<Pair<Int, RelatedTitlesSource>>(), relatedSaved)

        simkl.authenticated.value = true
        runCurrent()
        assertTrue(vm.uiState.value.relatedSourceEnabled)
        assertEquals(listOf(RelatedTitlesSource.TMDB, RelatedTitlesSource.SIMKL), vm.uiState.value.relatedChoices)
        vm.selectRelatedSource(RelatedTitlesSource.SIMKL)
        runCurrent()
        assertEquals(listOf(3 to RelatedTitlesSource.SIMKL), relatedSaved)

        // Signed out later: the choice is kept and shown; the row dims again.
        relatedSource.value = RelatedTitlesSource.SIMKL
        simkl.authenticated.value = false
        runCurrent()
        assertEquals(RelatedTitlesSource.SIMKL, vm.uiState.value.relatedSource)
        assertFalse(vm.uiState.value.relatedSourceEnabled)
    }

    @Test
    fun `a revoked tracker turns amber and a source on it reads from Nuvio, keeping the choice`() = runTest {
        val vm = viewModel()
        collect(vm)
        accounts.getValue(MDBLIST).value = TrackingAccount("a", accountName = "arshadk", revoked = true)
        mdblist.authenticated.value = false
        progressSource.value = TrackingSource.MDBLIST
        runCurrent()
        val state = vm.uiState.value
        assertEquals(TrackerStatus.REVOKED, state.rows[0].status)
        assertEquals(TrackerSourceUi(TrackingSource.MDBLIST, readsFromNuvio = true), state.progressSource)
        assertFalse(state.sourcesEnabled)
    }

    @Test
    fun `sign-in polls while open, and approval returns to the hub with a toast and a first sync`() = runTest {
        val vm = viewModel()
        val events = collect(vm)
        simklAuth.polls = mutableListOf(TrackingDevicePoll.Pending, TrackingDevicePoll.Approved)
        vm.openSignIn(SIMKL)
        runCurrent()
        assertEquals(1, simklAuth.started)
        assertEquals(3, vm.uiState.value.signIn?.profileId)
        assertTrue(vm.uiState.value.signIn?.state is TrackerSignIn.Waiting)
        advanceTimeBy(5_001)
        assertEquals(listOf("CODE1"), simklAuth.polled)
        // The provider saves the account before it answers Approved.
        accounts.getValue(SIMKL).value = TrackingAccount("t", accountName = "arshad.k")
        advanceTimeBy(5_001)
        assertNull(vm.uiState.value.signIn)
        assertEquals(listOf<TrackersEvent>(TrackersEvent.Connected(SIMKL, SIMKL.name, "arshad.k")), events)
        assertEquals(listOf("progress:simkl", "library:simkl"), refreshed)
        coVerify(exactly = 0) { sourceController.selectProgressSource(any()) }
    }

    @Test
    fun `closing the sign-in stops the polling`() = runTest {
        val vm = viewModel()
        collect(vm)
        vm.openSignIn(SIMKL)
        runCurrent()
        vm.closeSignIn()
        advanceTimeBy(60_000)
        assertEquals(emptyList<String>(), simklAuth.polled)
        assertNull(vm.uiState.value.signIn)
    }

    @Test
    fun `an unreachable service shows the error, and Try again asks for a new code`() = runTest {
        val vm = viewModel()
        collect(vm)
        simklAuth.startFailure = IOException("offline")
        vm.openSignIn(SIMKL)
        runCurrent()
        assertEquals(TrackerSignIn.Reason.UNREACHABLE, (vm.uiState.value.signIn?.state as TrackerSignIn.Failed).reason)
        simklAuth.startFailure = null
        vm.restartSignIn()
        runCurrent()
        assertEquals("CODE1", (vm.uiState.value.signIn?.state as TrackerSignIn.Waiting).code.userCode)
    }

    @Test
    fun `disconnect forgets the tokens, then sets sources on the tracker back to Nuvio, on the same profile`() = runTest {
        val vm = viewModel()
        collect(vm)
        var tokensGoneAtReset: List<Int>? = null
        coEvery { sourceController.onProviderDisconnected(any(), any()) } answers { tokensGoneAtReset = mdblistAuth.disconnected.toList() }
        vm.disconnect(MDBLIST)
        activeProfileFlow.value = 5
        runCurrent()
        assertEquals(listOf(3), mdblistAuth.disconnected)
        assertEquals(listOf(3), tokensGoneAtReset)
        coVerify(exactly = 1) { sourceController.onProviderDisconnected(MDBLIST, 3) }
    }

    @Test
    fun `a failed disconnect leaves the sources as they were`() = runTest {
        val failing = FakeTrackingProvider(MDBLIST, connected = true)
        val vmFailing = TrackersViewModel(
            TrackingProviderRegistry(setOf(failing)), mockk(relaxed = true), sourceController,
            mockk<ContinueWatchingSettingsDataStore> {
                every { progressSource } returns flowOf(TrackingSource.MDBLIST)
                every { librarySource } returns flowOf(TrackingSource.NUVIO)
                every { activeSimklAnimeSeasons } returns flowOf(SimklAnimeSeasons.PER_SHOW)
                every { relatedTitlesSource } returns flowOf(RelatedTitlesSource.TMDB)
            },
            TrackingProgressProviderRegistry(emptySet()), TrackingLibraryProviderRegistry(emptySet()),
            mockk { every { activeProfileId } returns activeProfileFlow }, { _, _, _ -> null }, { 0L }
        )
        (failing.deviceAuth as FakeDeviceAuth).disconnectFailure = IOException("disk")
        vmFailing.disconnect(MDBLIST)
        runCurrent()
        coVerify(exactly = 0) { sourceController.onProviderDisconnected(any(), any()) }
    }

    @Test
    fun `New code while a poll waits polls only the new code, and opening another tracker stops the first`() = runTest {
        val vm = viewModel()
        collect(vm)
        vm.openSignIn(SIMKL)
        runCurrent()
        vm.restartSignIn()
        runCurrent()
        advanceTimeBy(5_001)
        assertEquals(listOf("CODE2"), simklAuth.polled)
        vm.openSignIn(MDBLIST)
        runCurrent()
        advanceTimeBy(20_001)
        assertEquals(listOf("CODE2"), simklAuth.polled)
        assertEquals(MDBLIST, vm.uiState.value.signIn?.providerId)
        assertTrue(mdblistAuth.polled.isNotEmpty())
    }

    @Test
    fun `a provider's own timeout is a failed poll, so the sign-in retries, then says it could not reach the service`() = runTest {
        val vm = viewModel()
        collect(vm)
        simklAuth.pollFailure = CancellationException("provider timeout")
        vm.openSignIn(SIMKL)
        runCurrent()
        advanceTimeBy(5_001)
        assertEquals(1, (vm.uiState.value.signIn?.state as TrackerSignIn.Waiting).failures)
        advanceTimeBy(120_000)
        assertEquals(TrackerSignIn.Reason.UNREACHABLE, (vm.uiState.value.signIn?.state as TrackerSignIn.Failed).reason)
    }

    @Test
    fun `a profile switch mid sign-in keeps every call and the account on the starting profile, and skips the first sync`() = runTest {
        val vm = viewModel()
        val events = collect(vm)
        simklAuth.polls = mutableListOf(TrackingDevicePoll.Pending, TrackingDevicePoll.Approved)
        vm.openSignIn(SIMKL)
        runCurrent()
        activeProfileFlow.value = 5
        advanceTimeBy(10_002)
        assertEquals(listOf(3, 3, 3), simklAuth.profileIds)
        assertEquals(listOf(3), accountReadsFor)
        assertEquals(1, events.size)
        assertEquals(emptyList<String>(), refreshed)
    }

    @Test
    fun `sync now refreshes the tracker and says how it went`() = runTest {
        val vm = viewModel()
        val events = collect(vm)
        vm.syncNow(MDBLIST)
        runCurrent()
        assertEquals(listOf("progress:mdblist", "library:mdblist"), refreshed)
        refreshFailure = IOException("offline")
        vm.syncNow(MDBLIST)
        runCurrent()
        assertEquals(listOf(TrackersEvent.Synced(MDBLIST, MDBLIST.name), TrackersEvent.SyncFailed(MDBLIST, MDBLIST.name)), events)
        assertFalse(vm.uiState.first().rows[0].syncing)
    }

    @Test
    fun `a build with no tracker has no rows and dimmed sources`() = runTest {
        val vm = TrackersViewModel(
            TrackingProviderRegistry(emptySet()), mockk(), sourceController,
            mockk<ContinueWatchingSettingsDataStore> {
                every { progressSource } returns flowOf(TrackingSource.NUVIO)
                every { librarySource } returns flowOf(TrackingSource.NUVIO)
                every { activeSimklAnimeSeasons } returns flowOf(SimklAnimeSeasons.PER_SHOW)
                every { relatedTitlesSource } returns flowOf(RelatedTitlesSource.TMDB)
            },
            TrackingProgressProviderRegistry(emptySet()), TrackingLibraryProviderRegistry(emptySet()),
            mockk { every { activeProfileId } returns MutableStateFlow(1) }, { _, _, _ -> null }, { 0L }
        )
        collect(vm)
        assertEquals(emptyList<TrackerRowUi>(), vm.uiState.value.rows)
        assertFalse(vm.uiState.value.sourcesEnabled)
        assertFalse(vm.uiState.value.hasRelatedSource)
    }

    @Test
    fun `a provider's own timeout while asking for a code shows could not reach`() = runTest {
        val vm = viewModel()
        collect(vm)
        simklAuth.startFailure = CancellationException("provider timeout")
        vm.openSignIn(SIMKL)
        runCurrent()
        assertEquals(TrackerSignIn.Reason.UNREACHABLE, (vm.uiState.value.signIn?.state as TrackerSignIn.Failed).reason)
    }

    @Test
    fun `a second Sync now while one runs starts nothing`() = runTest {
        val vm = viewModel()
        val events = collect(vm)
        vm.syncNow(MDBLIST)
        vm.syncNow(MDBLIST)
        runCurrent()
        assertEquals(listOf("progress:mdblist", "library:mdblist"), refreshed)
        assertEquals(listOf<TrackersEvent>(TrackersEvent.Synced(MDBLIST, MDBLIST.name)), events)
    }

    @Test
    fun `a tracker whose setup is not done reads Set up whatever its account, and its setup opens a phone page`() = runTest {
        simklSetup.hint.value = null
        accounts.getValue(SIMKL).value = TrackingAccount("kept")
        val vm = viewModel()
        collect(vm)
        val row = vm.uiState.value.rows[1]
        assertEquals(TrackerStatus.NEEDS_SETUP, row.status)
        assertEquals(simklSetup.text, row.setup)
        assertNull(row.setupHint)
        assertNull("MDBList has nothing to set up", vm.uiState.value.rows[0].setup)

        accounts.getValue(SIMKL).value = null
        vm.openSetup(SIMKL)
        runCurrent()
        assertEquals(TrackerSetupUi(SIMKL, "http://tv/p/"), vm.uiState.value.setup)
        assertEquals(listOf(simklSetup.text.phonePage to 8160), pastePagesStarted)

        // The phone sends a working id: the panel turns into Connect (K4) and the row reads Connect.
        val server = pasteServer!!
        assertEquals(PasteCheck.Saved, pasteSubmit!!(server, "client-id-1234"))
        runCurrent()
        assertEquals(listOf("client-id-1234"), simklSetup.submitted)
        assertEquals(TrackerStatus.NOT_CONNECTED, vm.uiState.value.rows[1].status)
        assertEquals("1234", vm.uiState.value.rows[1].setupHint)

        vm.closeSetup()
        runCurrent()
        assertNull(vm.uiState.value.setup)
        assertTrue(server.isClosed)
        assertEquals(PasteCheck.Closed, pasteSubmit!!(server, "client-id-5678"))
        assertEquals(listOf("client-id-1234"), simklSetup.submitted)
    }

    @Test
    fun `Connect from the setup panel closes it and opens the sign-in, and a sign-in closes an open setup`() = runTest {
        val vm = viewModel()
        collect(vm)
        vm.openSetup(SIMKL)
        runCurrent()
        vm.connectFromSetup(SIMKL)
        runCurrent()
        assertNull(vm.uiState.value.setup)
        assertEquals(SIMKL, vm.uiState.value.signIn?.providerId)
        assertEquals(1, simklAuth.started)

        vm.openSetup(SIMKL)
        runCurrent()
        assertNull("the setup panel replaces the sign-in", vm.uiState.value.signIn)
        vm.openSignIn(SIMKL)
        runCurrent()
        assertNull(vm.uiState.value.setup)
    }

    @Test
    fun `Remove id closes the panel and removes the setup, and a tracker with none opens no setup`() = runTest {
        val vm = viewModel()
        collect(vm)
        vm.openSetup(SIMKL)
        runCurrent()
        vm.removeSetup(SIMKL)
        runCurrent()
        assertEquals(1, simklSetup.removed)
        assertNull(vm.uiState.value.setup)
        assertEquals(TrackerStatus.NEEDS_SETUP, vm.uiState.value.rows[1].status)

        vm.openSetup(MDBLIST)
        runCurrent()
        assertNull(vm.uiState.value.setup)
        assertEquals(1, pastePagesStarted.size)
    }

    @Test
    fun `a removal that fails keeps the setup, closes the panel and does not crash`() = runTest {
        val vm = viewModel()
        collect(vm)
        vm.openSetup(SIMKL)
        runCurrent()
        simklSetup.removeFailure = IOException("disk full")
        vm.removeSetup(SIMKL)
        runCurrent()
        assertEquals(1, simklSetup.removed)
        assertNull(vm.uiState.value.setup)
        assertEquals("7f3a", vm.uiState.value.rows[1].setupHint)
    }

    @Test
    fun `leaving the screen closes an open phone page`() = runTest {
        val vm = viewModel()
        collect(vm)
        vm.openSetup(SIMKL)
        runCurrent()
        val server = pasteServer!!
        val store = ViewModelStore()
        store.put("trackers", vm)

        store.clear()

        assertTrue(server.isClosed)
    }

    @Test
    fun `no phone page (no network) still opens the panel, without a QR`() = runTest {
        val vm = TrackersViewModel(
            TrackingProviderRegistry(setOf(simkl)), mockk(relaxed = true) { every { account(any()) } returns MutableStateFlow(null) },
            sourceController,
            mockk<ContinueWatchingSettingsDataStore> {
                every { progressSource } returns flowOf(TrackingSource.NUVIO)
                every { librarySource } returns flowOf(TrackingSource.NUVIO)
                every { activeSimklAnimeSeasons } returns flowOf(SimklAnimeSeasons.PER_SHOW)
                every { relatedTitlesSource } returns flowOf(RelatedTitlesSource.TMDB)
            },
            TrackingProgressProviderRegistry(emptySet()), TrackingLibraryProviderRegistry(emptySet()),
            mockk { every { activeProfileId } returns activeProfileFlow }, { _, _, _ -> null }, { 0L }
        )
        collect(vm)
        vm.openSetup(SIMKL)
        runCurrent()
        assertEquals(TrackerSetupUi(SIMKL, null), vm.uiState.value.setup)
    }

    @Test
    fun `a row carries the tracker's allowance`() = runTest {
        val vm = viewModel()
        collect(vm)
        val status = TrackingAccountStatus(limit = 500, remaining = 368, resetsAtEpochMs = 9L)
        simkl.status.value = status
        runCurrent()
        assertEquals(status, vm.uiState.value.rows[1].accountStatus)
        assertNull(vm.uiState.value.rows[0].accountStatus)
    }

    @Test
    fun `disconnect goes through the key sign-in of a tracker that has no device sign-in`() = runTest {
        val disconnected = mutableListOf<Int>()
        val keyTracker = object : TrackingProvider {
            override val descriptor = TrackingProviderDescriptor(MDBLIST, "MDBList", emptySet())
            override val isAuthenticated = MutableStateFlow(true)
            override val keyAuth = object : TrackingKeyAuth {
                override suspend fun connect(profileId: Int, key: String, stillWanted: () -> Boolean) = PasteCheck.Saved
                override suspend fun disconnect(profileId: Int) {
                    disconnected += profileId
                }
            }
        }
        assertNull(keyTracker.deviceAuth)
        val vm = TrackersViewModel(
            TrackingProviderRegistry(setOf(keyTracker)), mockk(relaxed = true), sourceController,
            mockk<ContinueWatchingSettingsDataStore> {
                every { progressSource } returns flowOf(TrackingSource.MDBLIST)
                every { librarySource } returns flowOf(TrackingSource.NUVIO)
                every { activeSimklAnimeSeasons } returns flowOf(SimklAnimeSeasons.PER_SHOW)
                every { relatedTitlesSource } returns flowOf(RelatedTitlesSource.TMDB)
            },
            TrackingProgressProviderRegistry(emptySet()), TrackingLibraryProviderRegistry(emptySet()),
            mockk { every { activeProfileId } returns activeProfileFlow }, { _, _, _ -> null }, { 0L }
        )
        vm.disconnect(MDBLIST)
        runCurrent()
        assertEquals(listOf(3), disconnected)
        coVerify(exactly = 1) { sourceController.onProviderDisconnected(MDBLIST, 3) }
        vm.openSignIn(MDBLIST)
        assertNull("no device sign-in to open", vm.uiState.value.signIn)
    }

    /** A tracker that connects with a pasted key (Spec 105 E1-E4): answers [answer], saving the account on Saved. */
    private inner class FakeKeyAuth : TrackingKeyAuth {
        val connects = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        @Volatile var answer: PasteCheck = PasteCheck.Saved

        /** When set, a check waits here (on the page's thread) after [entered] counts down: a send still in flight. */
        @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val entered = java.util.concurrent.Semaphore(0)

        override suspend fun connect(profileId: Int, key: String, stillWanted: () -> Boolean): PasteCheck {
            connects += profileId to key
            gate?.let { entered.release(); it.await() }
            if (answer == PasteCheck.Saved) {
                accounts.getValue(MDBLIST).value = TrackingAccount(key, accountName = "arshadk")
                keyTracker.isAuthenticated.value = true
            }
            return answer
        }

        override suspend fun disconnect(profileId: Int) = Unit
    }

    private val keyAuth = FakeKeyAuth()
    private val keyTracker = object : TrackingProvider {
        override val descriptor = TrackingProviderDescriptor(MDBLIST, "MDBList", emptySet())
        override val isAuthenticated = MutableStateFlow(false)
        override val keyAuth: TrackingKeyAuth = this@TrackersViewModelTest.keyAuth
    }

    private fun keyViewModel(): TrackersViewModel {
        accounts.getValue(MDBLIST).value = null
        return viewModel(providers = setOf(keyTracker, simkl))
    }

    @Test
    fun `a key tracker's row asks for a key, and its panel opens the tracker's own phone page`() = runTest {
        val vm = keyViewModel()
        collect(vm)
        val row = vm.uiState.value.rows[0]
        assertEquals(TrackerStatus.NOT_CONNECTED, row.status)
        assertEquals(trackerKeyText(MDBLIST), row.keyText)
        assertNull(row.keyHint)
        assertNull("Simkl signs in with a code", vm.uiState.value.rows[1].keyText)

        vm.openKeyPanel(MDBLIST)
        runCurrent()
        assertEquals(TrackerKeyUi(MDBLIST, "http://tv/p/", null), vm.uiState.value.keyPanel)
        assertEquals(listOf(trackerKeyText(MDBLIST)!!.phonePage to 8190), pastePagesStarted)
        vm.openKeyPanel(SIMKL)
        runCurrent()
        assertEquals("Simkl has no key panel", MDBLIST, vm.uiState.value.keyPanel?.providerId)
    }

    @Test
    fun `a refused key stays in the panel, and an accepted one closes it with a toast and a first sync`() = runTest {
        val vm = keyViewModel()
        val events = collect(vm)
        vm.openKeyPanel(MDBLIST)
        runCurrent()
        val server = pasteServer!!

        keyAuth.answer = PasteCheck.Refused
        assertEquals(PasteCheck.Refused, pasteSubmit!!(server, "bad-key-0000"))
        runCurrent()
        assertEquals(PasteCheck.Refused, vm.uiState.value.keyPanel?.lastCheck)
        keyAuth.answer = PasteCheck.Unreachable
        pasteSubmit!!(server, "any-key-0000")
        runCurrent()
        assertEquals(PasteCheck.Unreachable, vm.uiState.value.keyPanel?.lastCheck)

        keyAuth.answer = PasteCheck.Saved
        assertEquals(PasteCheck.Saved, pasteSubmit!!(server, "good-key-3f2a"))
        runCurrent()
        assertNull(vm.uiState.value.keyPanel)
        assertTrue(server.isClosed)
        assertEquals(listOf<TrackersEvent>(TrackersEvent.Connected(MDBLIST, "MDBList", "arshadk")), events)
        assertEquals(listOf("progress:mdblist", "library:mdblist"), refreshed)
        val row = vm.uiState.value.rows[0]
        assertEquals(TrackerStatus.CONNECTED, row.status)
        assertEquals("3f2a", row.keyHint)
        assertEquals(listOf(3, 3, 3), keyAuth.connects.map { it.first })
    }

    @Test
    fun `a key is saved on the profile the panel opened on, and no first sync runs on another`() = runTest {
        val vm = keyViewModel()
        val events = collect(vm)
        vm.openKeyPanel(MDBLIST)
        runCurrent()
        activeProfileFlow.value = 5
        pasteSubmit!!(pasteServer!!, "good-key-3f2a")
        runCurrent()
        assertEquals(listOf(3 to "good-key-3f2a"), keyAuth.connects)
        assertEquals(1, events.size)
        assertEquals(emptyList<String>(), refreshed)
    }

    @Test
    fun `the key panel, the setup panel and the sign-in close each other, and Done ends the phone page`() = runTest {
        val vm = keyViewModel()
        collect(vm)
        vm.openKeyPanel(MDBLIST)
        runCurrent()
        vm.openSignIn(SIMKL)
        runCurrent()
        assertNull(vm.uiState.value.keyPanel)

        vm.openKeyPanel(MDBLIST)
        runCurrent()
        assertNull(vm.uiState.value.signIn)
        vm.openSetup(SIMKL)
        runCurrent()
        assertNull(vm.uiState.value.keyPanel)
        assertEquals(SIMKL, vm.uiState.value.setup?.providerId)

        vm.openKeyPanel(MDBLIST)
        runCurrent()
        assertNull(vm.uiState.value.setup)
        val server = pasteServer!!
        vm.closeKeyPanel()
        runCurrent()
        assertNull(vm.uiState.value.keyPanel)
        assertTrue(server.isClosed)
        assertEquals(PasteCheck.Closed, pasteSubmit!!(server, "late-key-0000"))
        assertTrue(keyAuth.connects.isEmpty())
    }

    @Test
    fun `the Library picker leaves out a connected tracker that has no library yet`() = runTest {
        val vm = viewModel(libraryIds = setOf(SIMKL))
        collect(vm)
        assertEquals(listOf(TrackingSource.NUVIO, TrackingSource.MDBLIST), vm.uiState.value.sourceChoices)
        assertEquals(listOf(TrackingSource.NUVIO), vm.uiState.value.libraryChoices)
        assertTrue(vm.uiState.value.sourcesEnabled)
        assertFalse(vm.uiState.value.librarySourceEnabled)
    }

    /** Sends [key] from the page's own thread and holds its check until [release]. */
    private fun sendHeld(key: String): Pair<Thread, kotlinx.coroutines.CompletableDeferred<Unit>> {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        keyAuth.gate = gate
        val submit = pasteSubmit!!
        val server = pasteServer!!
        val thread = Thread { submit(server, key) }.apply { start() }
        assertTrue(keyAuth.entered.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS))
        return thread to gate
    }

    @Test
    fun `an answer for an older key panel leaves the newer one as it is`() = runTest {
        val vm = keyViewModel()
        collect(vm)
        vm.openKeyPanel(MDBLIST)
        runCurrent()
        keyAuth.answer = PasteCheck.Refused
        val (thread, gate) = sendHeld("old-key-0000")
        vm.openKeyPanel(MDBLIST)
        runCurrent()
        gate.complete(Unit)
        thread.join(5_000)
        runCurrent()
        assertEquals(TrackerKeyUi(MDBLIST, "http://tv/p/", null), vm.uiState.value.keyPanel)
    }

    @Test
    fun `a key accepted after the panel gave way to Simkl's sign-in leaves the sign-in open`() = runTest {
        val vm = keyViewModel()
        val events = collect(vm)
        vm.openKeyPanel(MDBLIST)
        runCurrent()
        val (thread, gate) = sendHeld("good-key-3f2a")
        vm.openSignIn(SIMKL)
        runCurrent()
        gate.complete(Unit)
        thread.join(5_000)
        runCurrent()
        assertEquals(SIMKL, vm.uiState.value.signIn?.providerId)
        assertEquals(listOf<TrackersEvent>(TrackersEvent.Connected(MDBLIST, "MDBList", "arshadk")), events)
    }

    @Test
    fun `a key accepted from an older page also closes a newer panel for the same profile`() = runTest {
        val vm = keyViewModel()
        collect(vm)
        vm.openKeyPanel(MDBLIST)
        runCurrent()
        val (thread, gate) = sendHeld("good-key-3f2a")
        vm.openKeyPanel(MDBLIST)
        runCurrent()
        gate.complete(Unit)
        thread.join(5_000)
        runCurrent()
        assertNull(vm.uiState.value.keyPanel)
        assertEquals(TrackerStatus.CONNECTED, vm.uiState.value.rows[0].status)
    }
}
