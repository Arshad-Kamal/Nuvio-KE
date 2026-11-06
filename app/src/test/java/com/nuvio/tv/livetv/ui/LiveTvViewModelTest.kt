package com.nuvio.tv.livetv.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.data.LiveTvUserStore
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.player.LivePlayer
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.livetv.player.StreamHealthStore
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceFetcher
import com.nuvio.tv.livetv.source.SourceKind
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 34 L8a: the opening filter and focus (Q1, L8-D3), the saved filter, Recents after 10 s of
 * play (L8-D2), lost channels never tuned (L8-D4). The minute ticker never idles: time moves by
 * [runCurrent] / [advanceTimeBy] only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val io = UnconfinedTestDispatcher()
    private val manager = mockk<LiveSourceManager>().also {
        every { it.sources } returns MutableStateFlow(
            listOf(LiveSource("a", SourceKind.M3U, "My playlist", "https://example.org/a.m3u", 0L))
        )
        coEvery { it.load() } just runs
        coEvery { it.refreshStale() } just runs
    }
    private val prefs = TestPreferencesDataStore()
    private val factory = mockk<ProfileDataStoreFactory>().also {
        every { it.get(any(), any()) } returns prefs as DataStore<Preferences>
    }
    private val profiles = mockk<ProfileManager>().also { every { it.activeProfileId } returns MutableStateFlow(1) }
    private val userStore = LiveTvUserStore(factory, profiles)
    private val tuneState = MutableStateFlow<LiveTuneState>(LiveTuneState.Idle)
    private val player = mockk<LivePlayer>(relaxed = true).also { every { it.state } returns tuneState }
    private val channels by lazy { ChannelRepository(tmp.root, { 0L }, io) }

    private fun ch(key: String, groups: List<String> = emptyList()) =
        Channel(key = key, sourceId = "a", name = key, groups = groups, streams = listOf(LiveStream("https://example.org/$key.m3u8")))

    private val a1 = ch("a1", listOf("Sports"))
    private val a2 = ch("a2")
    private val a3 = ch("a3")

    /** a1, a3 listed; a2 lost (the source's second refresh stopped listing it). */
    private suspend fun seedChannels() {
        channels.replaceSource("a", listOf(a1, a2, a3))
        channels.replaceSource("a", listOf(a1, a3))
    }

    private fun TestScope.viewModel(): LiveTvViewModel {
        val vm = LiveTvViewModel(
            manager,
            channels,
            GuideRepository(tmp.root, io),
            IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { 0L }, io, "http://127.0.0.1:1/"),
            HiddenChannelStore(tmp.root, io),
            StreamHealthStore(tmp.root, io),
            userStore,
            mockk<ScreensaverController>(relaxed = true),
            player,
            mainDispatcherRule.dispatcher
        )
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.info.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun `first open goes to Favorites when one is listed, focus on the last-watched channel`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            userStore.toggleFavorite("a2")
            userStore.recordWatched("a3", 1)
            val vm = viewModel()
            val ui = vm.uiState.value
            assertFalse(ui.loading)
            assertEquals(LiveTvFilter.Favorites, ui.filter)
            // The lost favorite is listed there, dimmed (L8-D4).
            assertEquals(listOf("a2"), ui.rows.map { it.channel.key })
            assertTrue(ui.rows.single().lost && ui.rows.single().favorite)
            assertEquals(1, ui.counts[LiveTvFilter.Recents])
            assertEquals("a3", vm.focusedKey.value)
        }

    @Test
    fun `no favorite and nothing saved opens All`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        assertEquals(LiveTvFilter.ALL, viewModel().uiState.value.filter)
    }

    @Test
    fun `a saved filter wins, and the pick focus settles on is saved after 500 ms`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            userStore.setLastFilter("sports")
            val vm = viewModel()
            assertEquals(LiveTvFilter.Browse(category = "sports"), vm.uiState.value.filter)
            vm.selectFilter(LiveTvFilter.Recents)
            runCurrent()
            advanceTimeBy(300)
            vm.selectFilter(LiveTvFilter.ALL)
            runCurrent()
            advanceTimeBy(499)
            runCurrent()
            assertEquals("sports", userStore.state.first().lastFilter) // Recents was passed, never saved
            advanceTimeBy(2)
            runCurrent()
            assertEquals("all", userStore.state.first().lastFilter)
        }

    @Test
    fun `Recents keeps its order while shown and takes the new order when selected again`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            userStore.recordWatched("a1", 1)
            userStore.recordWatched("a3", 2)
            val vm = viewModel()
            vm.selectFilter(LiveTvFilter.Recents)
            runCurrent()
            assertEquals(listOf("a3", "a1"), vm.uiState.value.rows.map { it.channel.key })
            userStore.recordWatched("a1", 3)
            runCurrent()
            assertEquals(listOf("a3", "a1"), vm.uiState.value.rows.map { it.channel.key })
            vm.selectFilter(LiveTvFilter.ALL)
            vm.selectFilter(LiveTvFilter.Recents)
            runCurrent()
            assertEquals(listOf("a1", "a3"), vm.uiState.value.rows.map { it.channel.key })
        }

    @Test
    fun `Favorites and Recents keep the picker values, a Browse replaces them`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            userStore.setLastFilter("sports")
            val vm = viewModel()
            val sports = LiveTvFilter.Browse(category = "sports")
            assertEquals(sports, vm.uiState.value.browse)
            assertEquals(listOf("a1"), vm.uiState.value.rows.map { it.channel.key })
            // Inside Sports: a1 only (no country), so All = 1 and No country = 1.
            assertEquals(
                listOf(PickerValue(null, 1), PickerValue(LiveTvFilter.NO_COUNTRY, 1)),
                vm.uiState.value.pickers!!.country.values
            )
            vm.selectFilter(LiveTvFilter.Favorites)
            runCurrent()
            assertEquals(LiveTvFilter.Favorites, vm.uiState.value.filter)
            assertEquals(sports, vm.uiState.value.browse)
            vm.selectFilter(LiveTvFilter.ALL)
            runCurrent()
            assertEquals(LiveTvFilter.ALL, vm.uiState.value.browse)
            assertEquals(listOf("a1", "a3"), vm.uiState.value.rows.map { it.channel.key })
        }

    @Test
    fun `toggling a favorite twice adds then removes it`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        val vm = viewModel()
        vm.toggleFavorite("a1")
        runCurrent()
        assertEquals(1, vm.uiState.value.counts[LiveTvFilter.Favorites])
        vm.toggleFavorite("a1")
        runCurrent()
        assertEquals(0, vm.uiState.value.counts[LiveTvFilter.Favorites])
    }

    @Test
    fun `a channel enters Recents after 10 s of play`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        viewModel()
        tuneState.value = LiveTuneState.Playing(a1)
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(userStore.state.first().recents.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals("a1", userStore.state.first().recents.single().key)
    }

    @Test
    fun `a play interrupted before 10 s starts the count again`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        viewModel()
        tuneState.value = LiveTuneState.Playing(a1)
        runCurrent()
        advanceTimeBy(6_000)
        tuneState.value = LiveTuneState.Tuning(a1, 1, 1)
        runCurrent()
        tuneState.value = LiveTuneState.Playing(a1)
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(userStore.state.first().recents.isEmpty())
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals("a1", userStore.state.first().recents.single().key)
    }

    @Test
    fun `a lost channel is never tuned by focus, OK or zapping`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        listOf("a1", "a2", "a3").forEach { userStore.toggleFavorite(it) }
        val vm = viewModel()
        val lost = vm.uiState.value.rows.single { it.lost }.channel
        vm.onRowFocused(lost)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(vm.tuneNow(lost))
        verify(exactly = 0) { player.tune(any()) }

        // Favorites in number order: a1, a3, then lost a2 (moved to its source's end). From a3 the zap wraps to a1.
        tuneState.value = LiveTuneState.Playing(a3)
        assertEquals("a1", vm.zap(+1))
        verify(exactly = 1) { player.tune(match { it.key == "a1" }) }
    }

    @Test
    fun `Back to a lost last channel and a return to a focused lost channel tune nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            listOf("a1", "a2", "a3").forEach { userStore.toggleFavorite(it) }
            val vm = viewModel()
            val lost = vm.uiState.value.rows.single { it.lost }.channel
            // Tuned the lost channel's key before it went lost: now it is the last channel.
            tuneState.value = LiveTuneState.Playing(lost.copy(lostAtMs = null))
            vm.tuneNow(a1)
            tuneState.value = LiveTuneState.Playing(a1)
            assertFalse(vm.lastChannel())
            assertEquals("a1", vm.focusedKey.value)

            vm.onRowFocused(lost)
            tuneState.value = LiveTuneState.Idle
            vm.resumePreview()
            runCurrent()
            verify(exactly = 0) { player.tune(match { it.key == lost.key }) }
        }
}
