package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.source.PackScanStore
import com.nuvio.tv.livetv.source.PackScanner
import com.nuvio.tv.livetv.source.PackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceFetcher
import com.nuvio.tv.livetv.source.SourceKind
import com.nuvio.tv.livetv.source.ChannelPack
import com.nuvio.tv.livetv.source.PackSummary
import com.nuvio.tv.livetv.source.PackChannel
import com.nuvio.tv.livetv.source.PackState
import com.nuvio.tv.livetv.source.PackStream
import kotlinx.coroutines.launch
import org.junit.Assert.assertNull
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
import kotlinx.coroutines.test.TestScope
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
    private val sourceList = MutableStateFlow<List<LiveSource>>(emptyList())
    private val packSaves = MutableStateFlow(0)
    private val manager = mockk<LiveSourceManager>().also {
        every { it.sources } returns sourceList
        every { it.packSaves } returns packSaves
        coEvery { it.load() } just runs
        coEvery { it.packSummary() } returns null
    }
    private val source = LiveSource("s1", SourceKind.M3U, "example.org", "https://example.org/list.m3u", 0L, 0L, channelCount = 3)
    private val checked = LiveSourceManager.Checked(source, 3, LiveSourceManager.Loaded.Channels(emptyList()))

    /** A real scanner over this test's folder that never probes (S3b's tests drive the scan UI). */
    private fun scanner() = PackScanner(
        PackStore(tmp.root, io),
        PackScanStore(tmp.root, io),
        { _, _, _ -> true },
        { _, _ -> false },
        flowOf(false),
        CoroutineScope(SupervisorJob() + io),
        { 0L }
    )

    private fun viewModel() = LiveTvSourcesViewModel(
        manager,
        ChannelRepository(tmp.root, { 0L }, io),
        GuideRepository(tmp.root, io),
        IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { 0L }, io, "http://127.0.0.1:1/"),
        HiddenChannelStore(tmp.root, io),
        scanner(),
        PackStore(tmp.root, io),
        PackScanStore(tmp.root, io)
    )

    @Test
    fun `a passed check enables Add, and editing the URL drops it`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.checkLink(any()) } returns LiveSourceManager.CheckResult.Ready(checked)
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
        coEvery { manager.checkLink(any()) } returns LiveSourceManager.CheckResult.Ready(checked)
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
        // A playlist's add listed everything already: no refresh follows.
        coVerify(exactly = 0) { manager.refresh(any()) }
        coVerify(exactly = 0) { manager.refreshGuide(any()) }
    }

    @Test
    fun `closing the panel during an Xtream add clears the typed login, the add finishes and reads the guide`() =
        runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.AddResult>()
        val login = XtreamFields("tv.example.net:8080", "kamal.home", "s3cr3t-Pa55")
        val xtream = LiveSource("x1", SourceKind.XTREAM, "tv.example.net", "http://tv.example.net:8080", 0L, 0L, channelCount = 3)
        val checked = LiveSourceManager.Checked(xtream, 3, LiveSourceManager.Loaded.Channels(emptyList()))
        coEvery { manager.checkXtream(login.server, login.username, login.password) } returns
            LiveSourceManager.CheckResult.Ready(checked)
        coEvery { manager.add(checked) } coAnswers { gate.await() }
        val guideGate = CompletableDeferred<LiveSource?>()
        coEvery { manager.refreshGuide("x1") } coAnswers { guideGate.await() }
        val vm = viewModel()
        vm.selectAddTab(AddTab.XTREAM)
        vm.onXtreamChange(login)
        vm.checkXtream()
        advanceUntilIdle()
        vm.confirmAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Adding, vm.addCheck.value)
        vm.closeAdd()
        // The password is not kept past the panel, while the add still saves its own copy.
        assertEquals(XtreamFields(), vm.xtreamFields.value)
        assertEquals(AddCheck.Adding, vm.addCheck.value)

        sourceList.value = listOf(xtream)
        gate.complete(LiveSourceManager.AddResult.Added(xtream))
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added("x1", "tv.example.net"), vm.event.first())
        coVerify(exactly = 1) { manager.add(checked) }
        // The check listed the channels: only the guide is read, once, shown as Refreshing meanwhile.
        vm.uiState.first { state -> state.rows.any { it.source.id == "x1" && it.health == SourceHealth.Refreshing } }
        vm.refresh("x1") // a Refresh press while it runs does nothing
        guideGate.complete(xtream)
        advanceUntilIdle()
        vm.uiState.first { state -> state.rows.any { it.source.id == "x1" && it.health != SourceHealth.Refreshing } }
        coVerify(exactly = 1) { manager.refreshGuide("x1") }
        coVerify(exactly = 0) { manager.refresh(any()) }
    }

    @Test
    fun `a refused check or add shows the reason and can be checked again`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.checkLink(any()) } returns LiveSourceManager.CheckResult.Refused("HTTP 403") andThen
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

    private val packSource = LiveSource(ChannelPack.SOURCE_ID, SourceKind.PACK, ChannelPack.NAME, ChannelPack.BASE_URL, 0L, 0L)

    @Test
    fun `the pack is offered with its figures until it is added`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.packSummary() } returns PackSummary(21037, 177, 1_600_000)
        val vm = viewModel()
        backgroundScope.launch { vm.packOffer.collect {} }
        advanceUntilIdle()
        assertEquals(PackOffer(PackSummary(21037, 177, 1_600_000)), vm.packOffer.value)
        sourceList.value = listOf(packSource)
        advanceUntilIdle()
        assertNull(vm.packOffer.value)
        // Removed here: offered again, figures already read.
        sourceList.value = emptyList()
        advanceUntilIdle()
        assertEquals(PackOffer(PackSummary(21037, 177, 1_600_000)), vm.packOffer.value)
        coVerify(exactly = 1) { manager.packSummary() }
    }

    @Test
    fun `the pack already added is not offered and its figures are not fetched`() = runTest(mainDispatcherRule.dispatcher) {
        sourceList.value = listOf(packSource)
        val vm = viewModel()
        backgroundScope.launch { vm.packOffer.collect {} }
        advanceUntilIdle()
        assertNull(vm.packOffer.value)
        coVerify(exactly = 0) { manager.packSummary() }
    }

    @Test
    fun `Add pack reports Added or the refusal, one download at a time`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.AddResult>()
        coEvery { manager.addPack() } coAnswers { gate.await() } andThen LiveSourceManager.AddResult.Refused("HTTP 404")
        val vm = viewModel()
        backgroundScope.launch { vm.packOffer.collect {} }
        advanceUntilIdle()
        vm.addPack()
        advanceUntilIdle()
        assertTrue(vm.packOffer.value!!.adding)
        vm.addPack() // ignored while the first runs
        gate.complete(LiveSourceManager.AddResult.Added(packSource))
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added(ChannelPack.SOURCE_ID, ChannelPack.NAME), vm.event.first())
        assertEquals(false, vm.packOffer.value!!.adding)
        vm.addPack()
        advanceUntilIdle()
        assertEquals(SourcesEvent.PackAddFailed("HTTP 404"), vm.event.first())
        coVerify(exactly = 2) { manager.addPack() }
    }

    @Test
    fun `the scan facts wait for the pack's file, not its record, and follow each download`() = runTest(mainDispatcherRule.dispatcher) {
        val saves = MutableStateFlow(0)
        val running = MutableStateFlow(false)
        var onDisk = emptyList<PackChannel>() // the pack file as the read sees it
        val reads = mutableListOf<Int>()
        val facts = MutableStateFlow<PackScanFacts?>(null)
        // Not backgroundScope: advanceUntilIdle does not run a queue of background tasks alone.
        val collecting = launch {
            packScanFacts(sourceList, saves, running) {
                reads += saves.value
                PackScanModel.facts(onDisk, emptyMap(), null, null)
            }.collect { facts.value = it }
        }
        advanceUntilIdle()
        assertNull(facts.value)
        // An add writes the record, then the file, then counts the download (S3c: keyed on the
        // record alone, the list read the missing file once and opened empty for good).
        sourceList.value = listOf(packSource)
        advanceUntilIdle()
        assertNull(facts.value)
        onDisk = listOf(packChannel("a", "ca"))
        saves.value = 1
        advanceUntilIdle()
        assertEquals(setOf<String?>("ca"), facts.value!!.world)
        // A refresh's completed download and a scan's start and end are read again; nothing else is.
        onDisk = onDisk + packChannel("b", "us")
        saves.value = 2
        advanceUntilIdle()
        assertEquals(setOf<String?>("ca", "us"), facts.value!!.world)
        running.value = true
        advanceUntilIdle()
        running.value = false
        advanceUntilIdle()
        sourceList.value = listOf(packSource.copy(lastRefreshMs = 5L))
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 2, 2), reads)
        // Removed: no facts.
        sourceList.value = emptyList()
        advanceUntilIdle()
        assertNull(facts.value)
        collecting.cancel()
    }

    private fun packChannel(key: String, country: String) =
        PackChannel("pack#ch:$key", key, country = country, streams = listOf(PackStream("https://x/$key", PackState.ALIVE)))

    @Test
    fun `a scan that ends while Sources is open is reported for its result panel`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.startScan(setOf("ca")))
        advanceUntilIdle()
        val event = vm.event.first()
        assertTrue("$event", event is SourcesEvent.ScanEnded && event.record.countries == listOf("ca") && !event.record.cancelled)
    }

    private val pluto = LiveSourceManager.GUIDE_PRESETS.single { it.key == "pluto" }
    private val india = LiveSourceManager.GUIDE_PRESETS.single { it.key == "in" }

    /** A view model whose playlists offer the Pluto and India presets. */
    private suspend fun offering(): LiveTvSourcesViewModel {
        val playlist = LiveSource("p", SourceKind.M3U, "List", "https://example.org/p.m3u", 0L, 1L)
        every { manager.sources } returns MutableStateFlow(listOf(playlist))
        val repo = ChannelRepository(tmp.root, { 0L }, io)
        repo.replaceSource(
            "p",
            listOf(
                Channel("p#a", "p", "A", streams = listOf(LiveStream("https://jmp2.uk/plu-5ee92e72fb286e0007285fec.m3u8"))),
                Channel("p#b", "p", "B", country = "in", streams = listOf(LiveStream("https://example.org/b.m3u8")))
            )
        )
        return LiveTvSourcesViewModel(
            manager,
            repo,
            GuideRepository(tmp.root, io),
            IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { 0L }, io, "http://127.0.0.1:1/"),
            HiddenChannelStore(tmp.root, io),
            scanner(),
            PackStore(tmp.root, io),
            PackScanStore(tmp.root, io)
        )
    }

    @Test
    fun `Add guides stores the chosen offered presets, reports the first as Added, then refreshes each`() =
        runTest(mainDispatcherRule.dispatcher) {
            val guides = (pluto.guides + india.guides).map { it.copy(addedAtMs = 1L) }
            coEvery { manager.addPresetGuides(listOf(pluto, india)) } returns guides
            guides.forEach { coEvery { manager.refresh(it.id) } returns it }
            val vm = offering()
            // uiState derives on Dispatchers.Default: wait for the offer, not for virtual time.
            assertEquals(listOf("pluto", "in"), vm.uiState.first { it.presets.isNotEmpty() }.presets.map { it.preset.key })
            // A key not on offer is ignored.
            vm.addPresets(listOf("in", "pluto", "plex"))
            advanceUntilIdle()
            assertEquals(SourcesEvent.Added(guides[0].id, guides[0].name), vm.event.first())
            guides.forEach { coVerify(exactly = 1) { manager.refresh(it.id) } }
        }

    @Test
    fun `nothing on offer adds nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.addPresets(listOf("pluto"))
        advanceUntilIdle()
        coVerify(exactly = 0) { manager.addPresetGuides(any()) }
    }

    @Test
    fun `a failed preset guide write reports it and refreshes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.addPresetGuides(any()) } returns null
        val vm = offering()
        vm.uiState.first { it.presets.isNotEmpty() }
        vm.addPresets(listOf("pluto"))
        advanceUntilIdle()
        assertEquals(SourcesEvent.PresetAddFailed, vm.event.first())
        coVerify(exactly = 0) { manager.refresh(any()) }
    }

    private val login = LiveSource("x1", SourceKind.XTREAM, "Living room", "http://tv.example.net:8080", 0L, 1L, channelCount = 3)
    private val edited = LiveSourceManager.Checked(login, 3, LiveSourceManager.Loaded.Channels(emptyList()))

    /** A view model with the Xtream [login] listed and its Edit login open (username [stored], null = none on this TV). */
    private fun TestScope.editing(stored: String?): LiveTvSourcesViewModel {
        sourceList.value = listOf(login)
        coEvery { manager.xtreamUsername("x1") } returns stored
        coEvery { manager.refreshGuide("x1") } returns login
        val vm = viewModel()
        vm.openEdit("x1")
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `Edit login opens on the server and stored username, the password unchanged, or both empty with none stored`() =
        runTest(mainDispatcherRule.dispatcher) {
            val kept = XtreamFields("http://tv.example.net:8080", "kamal.home", "")
            assertEquals(EditLogin("x1", kept, kept, stored = true), editing("kamal.home").editLogin.value)
            val none = XtreamFields("http://tv.example.net:8080", "", "")
            assertEquals(EditLogin("x1", none, none, stored = false), editing(null).editLogin.value)
        }

    @Test
    fun `Save with nothing changed closes without a check`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = editing("kamal.home")
        vm.saveEdit()
        advanceUntilIdle()
        assertNull(vm.editLogin.value)
        coVerify(exactly = 0) { manager.checkXtreamEdit(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.saveEdit(any()) }
    }

    @Test
    fun `Save before a check checks then saves in one press, keeping an empty password as the stored one`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { manager.checkXtreamEdit("x1", "http://tv.example.net:8080", "kamal.new", null) } returns
                LiveSourceManager.CheckResult.Ready(edited)
            coEvery { manager.saveEdit(edited) } returns LiveSourceManager.AddResult.Added(login)
            val vm = editing("kamal.home")
            vm.onEditChange(vm.editLogin.value!!.fields.copy(username = "kamal.new"))
            vm.saveEdit()
            advanceUntilIdle()
            assertEquals(SourcesEvent.Edited("x1"), vm.event.first())
            assertNull(vm.editLogin.value)
            assertEquals(AddCheck.Idle, vm.editCheck.value)
            coVerify(exactly = 1) { manager.saveEdit(edited) }
            // The guide is read after the save (the server may have moved).
            coVerify(exactly = 1) { manager.refreshGuide("x1") }
        }

    @Test
    fun `a refused edit check or save stays on the line and the panel stays open`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.checkXtreamEdit("x1", any(), "kamal.home", "wrong") } returns
            LiveSourceManager.CheckResult.Refused("The server turned down this username and password")
        coEvery { manager.checkXtreamEdit("x1", any(), "kamal.home", "n3w") } returns LiveSourceManager.CheckResult.Ready(edited)
        coEvery { manager.saveEdit(edited) } returns LiveSourceManager.AddResult.Refused("Already added")
        val vm = editing("kamal.home")
        vm.onEditChange(vm.editLogin.value!!.fields.copy(password = "wrong"))
        vm.checkEdit()
        advanceUntilIdle()
        assertEquals(AddCheck.Refused("The server turned down this username and password"), vm.editCheck.value)
        vm.saveEdit() // checks again, refused again: nothing saved
        advanceUntilIdle()
        coVerify(exactly = 2) { manager.checkXtreamEdit("x1", any(), "kamal.home", "wrong") }
        coVerify(exactly = 0) { manager.saveEdit(any()) }
        vm.onEditChange(vm.editLogin.value!!.fields.copy(password = "n3w"))
        vm.checkEdit()
        advanceUntilIdle()
        assertEquals(AddCheck.Ready(edited), vm.editCheck.value)
        vm.saveEdit() // a passed check saves without checking again
        advanceUntilIdle()
        assertEquals(AddCheck.Refused("Already added"), vm.editCheck.value)
        assertTrue(vm.editLogin.value != null)
        coVerify(exactly = 1) { manager.checkXtreamEdit("x1", any(), "kamal.home", "n3w") }
        coVerify(exactly = 0) { manager.refreshGuide(any()) }
    }

    @Test
    fun `closing Edit login drops the typed password, reopening starts from the stored login`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = editing("kamal.home")
            vm.onEditChange(vm.editLogin.value!!.fields.copy(password = "n3w"))
            vm.closeEdit()
            assertNull(vm.editLogin.value)
            vm.openEdit("x1")
            advanceUntilIdle()
            assertEquals("", vm.editLogin.value!!.fields.password)
            assertEquals(AddCheck.Idle, vm.editCheck.value)
        }

    @Test
    fun `Save while its own check or Done's check runs waits for it, one check, one save`() =
        runTest(mainDispatcherRule.dispatcher) {
            val gate = CompletableDeferred<LiveSourceManager.CheckResult>()
            coEvery { manager.checkXtreamEdit("x1", any(), any(), "n3w") } coAnswers { gate.await() }
            coEvery { manager.saveEdit(edited) } returns LiveSourceManager.AddResult.Added(login)
            val vm = editing("kamal.home")
            vm.onEditChange(vm.editLogin.value!!.fields.copy(password = "n3w"))
            vm.checkEdit() // Done
            vm.saveEdit()
            vm.saveEdit() // an impatient second press
            advanceUntilIdle()
            assertEquals(AddCheck.Checking, vm.editCheck.value)
            gate.complete(LiveSourceManager.CheckResult.Ready(edited))
            advanceUntilIdle()
            assertEquals(SourcesEvent.Edited("x1"), vm.event.first())
            coVerify(exactly = 1) { manager.checkXtreamEdit("x1", any(), any(), "n3w") }
            coVerify(exactly = 1) { manager.saveEdit(edited) }
        }

    @Test
    fun `a save outliving its panel finishes without touching Add source`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.AddResult>()
        coEvery { manager.checkXtreamEdit("x1", any(), any(), "n3w") } returns LiveSourceManager.CheckResult.Ready(edited)
        coEvery { manager.saveEdit(edited) } coAnswers { gate.await() }
        coEvery { manager.checkLink(any()) } returns LiveSourceManager.CheckResult.Ready(checked)
        val vm = editing("kamal.home")
        vm.onEditChange(vm.editLogin.value!!.fields.copy(password = "n3w"))
        vm.saveEdit()
        advanceUntilIdle()
        assertEquals(AddCheck.Adding, vm.editCheck.value)
        vm.closeEdit() // Cancel while it stores
        vm.saveEdit() // no panel: nothing
        // Add source opens and checks as usual meanwhile.
        vm.openAdd()
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Ready(checked), vm.addCheck.value)
        gate.complete(LiveSourceManager.AddResult.Refused("Already added"))
        advanceUntilIdle()
        assertEquals(AddCheck.Ready(checked), vm.addCheck.value)
        assertEquals(AddCheck.Idle, vm.editCheck.value)
        assertNull(vm.editLogin.value)
        coVerify(exactly = 1) { manager.saveEdit(edited) }
    }

    @Test
    fun `another panel opening drops an Edit login still loading`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<String?>()
        sourceList.value = listOf(login)
        coEvery { manager.xtreamUsername("x1") } coAnswers { gate.await() }
        val vm = viewModel()
        vm.openEdit("x1")
        advanceUntilIdle()
        vm.closeEdit() // Rename, Remove or Add source pressed first
        gate.complete("kamal.home")
        advanceUntilIdle()
        assertNull(vm.editLogin.value)
    }

    @Test
    fun `closing during a check drops it`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.CheckResult>()
        coEvery { manager.checkLink(any()) } coAnswers { gate.await() }
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

    private val pasted = "http://tv.example.net:8080/get.php?username=kamal.home&password=s3cr3t-Pa55&type=m3u_plus"
    private val upgraded = LiveSourceManager.Checked(
        LiveSource("x1", SourceKind.XTREAM, "tv.example.net", "http://tv.example.net:8080", 0L, 0L, channelCount = 3),
        3,
        LiveSourceManager.Loaded.Channels(emptyList())
    )

    @Test
    fun `a pasted link that signed in as Xtream adds as a login and reads its guide`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.checkLink(pasted) } returns LiveSourceManager.CheckResult.Ready(upgraded)
        coEvery { manager.add(upgraded) } returns LiveSourceManager.AddResult.Added(upgraded.source)
        coEvery { manager.refreshGuide("x1") } returns upgraded.source
        val vm = viewModel()
        vm.onAddUrlChange(pasted)
        vm.checkAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Ready(upgraded), vm.addCheck.value)
        vm.confirmAdd() // Add as Xtream login
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added("x1", "tv.example.net"), vm.event.first())
        assertEquals("", vm.addUrl.value) // the pasted link carries the password: gone with the add
        coVerify(exactly = 1) { manager.refreshGuide("x1") }
        coVerify(exactly = 0) { manager.check(any()) }
    }

    @Test
    fun `Add as playlist checks the link as a playlist and adds it in one press`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.CheckResult>()
        coEvery { manager.checkLink(pasted) } returns LiveSourceManager.CheckResult.Ready(upgraded)
        coEvery { manager.check(pasted) } coAnswers { gate.await() }
        coEvery { manager.add(checked) } returns LiveSourceManager.AddResult.Added(source)
        val vm = viewModel()
        vm.addAsPlaylist() // nothing checked: nothing
        vm.onAddUrlChange(pasted)
        vm.checkAdd()
        advanceUntilIdle()
        vm.addAsPlaylist()
        advanceUntilIdle()
        assertEquals(AddCheck.Checking, vm.addCheck.value)
        vm.addAsPlaylist() // a second press while it checks: nothing
        gate.complete(LiveSourceManager.CheckResult.Ready(checked))
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added("s1", "example.org"), vm.event.first())
        assertEquals(AddCheck.Idle, vm.addCheck.value)
        coVerify(exactly = 1) { manager.check(pasted) }
        coVerify(exactly = 0) { manager.add(upgraded) }
        coVerify(exactly = 0) { manager.refreshGuide(any()) }
    }

    @Test
    fun `a typed link already stored is marked so the offer drops Add as playlist`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.linkStored.collect {} }
        vm.onAddUrlChange(" $pasted ")
        advanceUntilIdle()
        assertEquals(false, vm.linkStored.value)
        sourceList.value = listOf(source.copy(url = pasted))
        advanceUntilIdle()
        assertEquals(true, vm.linkStored.value) // compared as check() stores it: trimmed
        vm.onAddUrlChange("$pasted&output=ts")
        advanceUntilIdle()
        assertEquals(false, vm.linkStored.value)
        vm.onAddUrlChange("")
        sourceList.value = listOf(source.copy(url = ""))
        advanceUntilIdle()
        assertEquals(false, vm.linkStored.value) // nothing typed is never stored
    }

    @Test
    fun `Add as playlist refused stays on the line, and is not offered for a playlist`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.checkLink(pasted) } returns LiveSourceManager.CheckResult.Ready(upgraded)
        coEvery { manager.checkLink(source.url) } returns LiveSourceManager.CheckResult.Ready(checked)
        coEvery { manager.check(pasted) } returns LiveSourceManager.CheckResult.Refused("Already added")
        val vm = viewModel()
        vm.onAddUrlChange(pasted)
        vm.checkAdd()
        advanceUntilIdle()
        vm.addAsPlaylist()
        advanceUntilIdle()
        assertEquals(AddCheck.Refused("Already added"), vm.addCheck.value)
        // A plain playlist's Ready has no playlist offer.
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        advanceUntilIdle()
        vm.addAsPlaylist()
        assertEquals(AddCheck.Ready(checked), vm.addCheck.value)
        // The Xtream tab's own Ready is no pasted link: no playlist check of the Link field.
        val login = XtreamFields("tv.example.net:8080", "kamal.home", "s3cr3t-Pa55")
        coEvery { manager.checkXtream(login.server, login.username, login.password) } returns
            LiveSourceManager.CheckResult.Ready(upgraded)
        vm.selectAddTab(AddTab.XTREAM)
        vm.onXtreamChange(login)
        vm.checkXtream()
        advanceUntilIdle()
        vm.addAsPlaylist()
        assertEquals(AddCheck.Ready(upgraded), vm.addCheck.value)
        coVerify(exactly = 1) { manager.check(any()) }
        coVerify(exactly = 0) { manager.add(any()) }
    }

    @Test
    fun `closing, editing the link or switching tab during Add as playlist's check drops it, nothing is added`() =
        runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.CheckResult>()
        coEvery { manager.checkLink(pasted) } returns LiveSourceManager.CheckResult.Ready(upgraded)
        coEvery { manager.check(pasted) } coAnswers { gate.await() }
        val vm = viewModel()
        val drops = listOf<() -> Unit>(
            { vm.closeAdd() },
            { vm.onAddUrlChange(pasted + "&x=1") },
            { vm.selectAddTab(AddTab.XTREAM) }
        )
        for (drop in drops) {
            vm.selectAddTab(AddTab.LINK)
            vm.onAddUrlChange(pasted)
            vm.checkAdd()
            advanceUntilIdle()
            vm.addAsPlaylist()
            advanceUntilIdle()
            assertEquals(AddCheck.Checking, vm.addCheck.value)
            drop()
            assertEquals(AddCheck.Idle, vm.addCheck.value)
        }
        gate.complete(LiveSourceManager.CheckResult.Ready(checked))
        advanceUntilIdle()
        assertEquals(AddCheck.Idle, vm.addCheck.value)
        coVerify(exactly = 0) { manager.add(any()) }
    }
}
