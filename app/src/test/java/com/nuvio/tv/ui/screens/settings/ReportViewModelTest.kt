package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.report.ReportFile
import com.nuvio.tv.core.report.ReportParts
import com.nuvio.tv.core.report.SentReport
import com.nuvio.tv.core.report.UploadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/** Spec 116 P5: the panel's state machine (building, ready, sending, sent, failed with and without Try again, cancel). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val file = ReportFile(ReportParts("tv", "app", "none", "none", "s", "log"), "Nuvio KE report v1\n".toByteArray())

    private class FakeSender(var reply: suspend (ByteArray, (Long, Long) -> Unit) -> UploadResult) : ReportSender {
        val sends = mutableListOf<ByteArray>()
        override suspend fun send(bytes: ByteArray, onProgress: (Long, Long) -> Unit): UploadResult {
            sends += bytes
            return reply(bytes, onProgress)
        }
    }

    private var builds = 0
    private var senders = 0
    private val recorded = mutableListOf<SentReport>()

    private fun viewModel(
        sender: FakeSender,
        build: suspend () -> ReportFile = { file },
    ) = ReportViewModel(
        build = { builds++; build() },
        newSender = { senders++; sender },
        clock = { 1_000L },
        lastReport = { recorded += it },
    )

    @Test
    fun `open builds once and shows the report ready to send`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeSender { _, _ -> UploadResult.Sent("KE-7F3QM2") })
        vm.open()
        assertEquals(ReportState.Building, vm.state.value)
        advanceUntilIdle()
        assertEquals(ReportState.Ready(file), vm.state.value)
        vm.open() // already open: no second build
        advanceUntilIdle()
        assertEquals(1, builds)
        assertEquals(1, senders)
    }

    @Test
    fun `a failed build leaves only Cancel`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeSender { _, _ -> UploadResult.Sent("KE-7F3QM2") }, build = { throw IOException("no store") })
        vm.open()
        advanceUntilIdle()
        assertEquals(ReportState.BuildFailed, vm.state.value)
        vm.send()
        assertEquals(ReportState.BuildFailed, vm.state.value)
    }

    @Test
    fun `send uploads the built bytes, reports progress, then shows and records the code`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val sender = FakeSender { bytes, progress ->
            progress(4L, bytes.size.toLong())
            gate.await()
            UploadResult.Sent("KE-7F3QM2")
        }
        val vm = viewModel(sender)
        vm.open()
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()
        val sending = vm.state.value as ReportState.Sending
        assertEquals(4L, sending.sent)
        assertSame(file, sending.file)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ReportState.Sent("KE-7F3QM2"), vm.state.value)
        assertSame(file.bytes, sender.sends.single())
        assertEquals(listOf(SentReport("KE-7F3QM2", 1_000L)), recorded)
    }

    @Test
    fun `a failure keeps the report and Try again sends the same bytes`() = runTest(main.dispatcher) {
        val sender = FakeSender { _, _ -> UploadResult.NoInternet }
        val vm = viewModel(sender)
        vm.open()
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()
        val failed = vm.state.value as ReportState.Ready
        assertEquals(UploadResult.NoInternet, failed.failure)
        assertTrue(failed.canSend)
        sender.reply = { _, _ -> UploadResult.Sent("KE-ABCDEF") }
        vm.send()
        advanceUntilIdle()
        assertEquals(ReportState.Sent("KE-ABCDEF"), vm.state.value)
        assertEquals(2, sender.sends.size)
        assertSame(sender.sends[0], sender.sends[1])
        assertEquals(1, builds)
    }

    @Test
    fun `server busy and rate limit offer Try again, a refusal does not`() = runTest(main.dispatcher) {
        for ((result, retry) in listOf(
            UploadResult.ServerUnavailable to true,
            UploadResult.RateLimited to true,
            UploadResult.Refused to false,
        )) {
            val sender = FakeSender { _, _ -> result }
            val vm = viewModel(sender)
            vm.open()
            advanceUntilIdle()
            vm.send()
            advanceUntilIdle()
            val failed = vm.state.value as ReportState.Ready
            assertEquals(result, failed.failure)
            assertEquals(retry, failed.canSend)
            vm.send()
            advanceUntilIdle()
            assertEquals(if (retry) 2 else 1, sender.sends.size)
            assertTrue(recorded.isEmpty())
        }
    }

    @Test
    fun `cancel stops the upload and keeps the report, and a late progress is ignored`() = runTest(main.dispatcher) {
        var late: ((Long, Long) -> Unit)? = null
        val sender = FakeSender { _, progress -> late = progress; awaitCancellation() }
        val vm = viewModel(sender)
        vm.open()
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.state.value is ReportState.Sending)
        vm.cancelSend()
        advanceUntilIdle()
        assertEquals(ReportState.Ready(file), vm.state.value)
        late?.invoke(9L, 9L)
        assertEquals(ReportState.Ready(file), vm.state.value)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a cancelled send's late progress never touches the next send`() = runTest(main.dispatcher) {
        val callbacks = mutableListOf<(Long, Long) -> Unit>()
        val vm = viewModel(FakeSender { _, progress -> callbacks += progress; awaitCancellation() })
        vm.open()
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()
        vm.cancelSend()
        vm.send() // Try again: attempt 2
        advanceUntilIdle()
        callbacks[1](3L, 9L)
        callbacks[0](8L, 9L) // attempt 1's OkHttp thread, late
        val sending = vm.state.value as ReportState.Sending
        assertEquals(2, sending.attempt)
        assertEquals(3L, sending.sent)
    }

    @Test
    fun `close during a build or an upload drops the report, and a reopen builds afresh`() = runTest(main.dispatcher) {
        val building = CompletableDeferred<ReportFile>()
        val vm = viewModel(FakeSender { _, _ -> awaitCancellation() }, build = { building.await() })
        vm.open()
        runCurrent() // the build is under way
        vm.close()
        building.complete(file)
        advanceUntilIdle()
        assertEquals(ReportState.Closed, vm.state.value)

        val vm2 = viewModel(FakeSender { _, _ -> awaitCancellation() })
        vm2.open()
        advanceUntilIdle()
        vm2.send()
        advanceUntilIdle()
        vm2.close()
        advanceUntilIdle()
        assertEquals(ReportState.Closed, vm2.state.value)
        vm2.open()
        advanceUntilIdle()
        assertEquals(ReportState.Ready(file), vm2.state.value)
        assertFalse(recorded.isNotEmpty())
        assertEquals(3, builds)
        assertEquals(3, senders)
    }

    @Test
    fun `the Help row shows only when the build has a report address`() {
        assertFalse(reportRowVisible(""))
        assertTrue(reportRowVisible("https://nuvio-reports.example.workers.dev"))
    }
}
