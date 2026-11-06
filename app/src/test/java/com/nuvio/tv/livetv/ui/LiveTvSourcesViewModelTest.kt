package com.nuvio.tv.livetv.ui

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceFetcher
import com.nuvio.tv.livetv.source.SourceKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 34 L7e Add source dialog: check-then-add state machine (Idle, Checking, Ready, Refused, Adding). */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvSourcesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val io = UnconfinedTestDispatcher()
    private val manager = mockk<LiveSourceManager>().also {
        every { it.sources } returns MutableStateFlow(emptyList())
        coEvery { it.load() } just runs
    }
    private val source = LiveSource("s1", SourceKind.M3U, "example.org", "https://example.org/list.m3u", 0L, 0L, channelCount = 3)
    private val checked = LiveSourceManager.Checked(source, 3, LiveSourceManager.Loaded.Channels(emptyList()))

    private fun viewModel() = LiveTvSourcesViewModel(
        manager,
        ChannelRepository(tmp.root, { 0L }, io),
        GuideRepository(tmp.root, io),
        IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { 0L }, io, "http://127.0.0.1:1/"),
        HiddenChannelStore(tmp.root, io)
    )

    @Test
    fun `a passed check enables Add, and editing the URL drops it`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.check(any()) } returns LiveSourceManager.CheckResult.Ready(checked)
        val vm = viewModel()
        vm.checkAdd()
        assertEquals(AddCheck.Idle, vm.addCheck.value) // nothing typed: no check
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        assertEquals(AddCheck.Checking, vm.addCheck.value)
        advanceUntilIdle()
        assertEquals(AddCheck.Ready(checked), vm.addCheck.value)
        vm.onAddUrlChange(source.url + "x")
        assertEquals(AddCheck.Idle, vm.addCheck.value)
    }

    @Test
    fun `an add in progress survives edits and closing, then reports Added`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.AddResult>()
        coEvery { manager.check(any()) } returns LiveSourceManager.CheckResult.Ready(checked)
        coEvery { manager.add(checked) } coAnswers { gate.await() }
        val vm = viewModel()
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        advanceUntilIdle()
        vm.confirmAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Adding, vm.addCheck.value)
        vm.onAddUrlChange("https://other.example/")
        vm.closeAdd()
        vm.openAdd()
        assertEquals(AddCheck.Adding, vm.addCheck.value)
        assertEquals(source.url, vm.addUrl.value)

        gate.complete(LiveSourceManager.AddResult.Added(source))
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added("s1", "example.org"), vm.event.first())
        assertEquals(AddCheck.Idle, vm.addCheck.value)
        assertEquals("", vm.addUrl.value)
    }

    @Test
    fun `a refused check or add shows the reason and can be checked again`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.check(any()) } returns LiveSourceManager.CheckResult.Refused("HTTP 403") andThen
            LiveSourceManager.CheckResult.Ready(checked)
        coEvery { manager.add(checked) } returns LiveSourceManager.AddResult.Refused("Already added")
        val vm = viewModel()
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Refused("HTTP 403"), vm.addCheck.value)
        vm.confirmAdd() // Add is dimmed: nothing happens
        assertEquals(AddCheck.Refused("HTTP 403"), vm.addCheck.value)
        vm.checkAdd()
        advanceUntilIdle()
        vm.confirmAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Refused("Already added"), vm.addCheck.value)
    }

    @Test
    fun `Add guide stores the Pluto guide, reports Added, then refreshes it`() = runTest(mainDispatcherRule.dispatcher) {
        val guide = LiveSourceManager.PLUTO_GUIDE.copy(addedAtMs = 1L)
        coEvery { manager.addPlutoGuide() } returns guide
        coEvery { manager.refresh(guide.id) } returns guide
        val vm = viewModel()
        vm.addPlutoGuide()
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added(guide.id, guide.name), vm.event.first())
        coVerify(exactly = 1) { manager.refresh(guide.id) }
    }

    @Test
    fun `Add guides stores both India guides, reports the first as Added, then refreshes each`() = runTest(mainDispatcherRule.dispatcher) {
        val guides = LiveSourceManager.INDIA_GUIDES.map { it.copy(addedAtMs = 1L) }
        coEvery { manager.addIndiaGuides() } returns guides
        guides.forEach { coEvery { manager.refresh(it.id) } returns it }
        val vm = viewModel()
        vm.addIndiaGuides()
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added(guides[0].id, guides[0].name), vm.event.first())
        guides.forEach { coVerify(exactly = 1) { manager.refresh(it.id) } }
    }

    @Test
    fun `a failed India guide write reports it and refreshes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.addIndiaGuides() } returns null
        val vm = viewModel()
        vm.addIndiaGuides()
        advanceUntilIdle()
        assertEquals(SourcesEvent.IndiaAddFailed, vm.event.first())
        coVerify(exactly = 0) { manager.refresh(any()) }
    }

    @Test
    fun `a failed Pluto guide write reports it and refreshes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.addPlutoGuide() } returns null
        val vm = viewModel()
        vm.addPlutoGuide()
        advanceUntilIdle()
        assertEquals(SourcesEvent.PlutoAddFailed, vm.event.first())
        coVerify(exactly = 0) { manager.refresh(any()) }
    }

    @Test
    fun `closing during a check drops it`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.CheckResult>()
        coEvery { manager.check(any()) } coAnswers { gate.await() }
        val vm = viewModel()
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        advanceUntilIdle()
        vm.closeAdd()
        gate.complete(LiveSourceManager.CheckResult.Ready(checked))
        advanceUntilIdle()
        assertEquals(AddCheck.Idle, vm.addCheck.value)
        assertTrue(vm.addUrl.value.isNotEmpty())
    }
}
