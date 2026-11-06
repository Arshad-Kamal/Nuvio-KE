package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.server.TmdbKeyAnswer
import com.nuvio.tv.core.server.TmdbKeyServer
import com.nuvio.tv.core.server.newSessionToken
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyCheck
import com.nuvio.tv.core.tmdb.TmdbKeyEntry
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Spec 52 T1: the key panel's states, that a check's answer reaches only its own panel, and (S3) that
 * the phone page runs only while its panel is open and a phone key closes only its own panel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TmdbKeyPanelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val keyEntry = mockk<TmdbKeyEntry>(relaxed = true)

    /** Each phone page started: its (unstarted) server and the send handler the ViewModel wired to it. */
    private val phonePages = mutableListOf<Pair<TmdbKeyServer, (TmdbKeyServer, String) -> TmdbKeyAnswer>>()
    private var phoneAvailable = false
    private val starter = mockk<TmdbKeyServerStarter> {
        every { start(any()) } answers {
            if (!phoneAvailable) return@answers null
            val submit = firstArg<(TmdbKeyServer, String) -> TmdbKeyAnswer>()
            val server = TmdbKeyServer(newSessionToken(), { "" }, submit, "127.0.0.1", 0, stopGraceMs = 0)
            phonePages += server to submit
            StartedKeyServer(server, "http://10.0.0.2:8120/k/${phonePages.size}/")
        }
    }

    private fun viewModel(): TmdbSettingsViewModel {
        val dataStore = mockk<TmdbSettingsDataStore> { every { storedSettings } returns flowOf(TmdbSettings()) }
        val provider = mockk<TmdbApiKeyProvider> {
            // A key saved on this TV, so Remove key has one to remove.
            every { state } returns MutableStateFlow(TmdbKeyState(userKey = SAVED_KEY, effectiveKey = SAVED_KEY))
        }
        return TmdbSettingsViewModel(dataStore, mockk(relaxed = true), provider, keyEntry, starter)
    }

    /** A send from phone page [n] (1-based), as its server's handler thread makes it. */
    private fun phoneSend(n: Int): TmdbKeyAnswer = phonePages[n - 1].let { (server, submit) -> submit(server, KEY) }

    // runCurrent, never advanceUntilIdle, in these tests: the latter also runs out the page's 10-minute lifetime.

    @Test
    fun `the phone page runs only while its panel is open`() = runTest(mainDispatcherRule.dispatcher) {
        phoneAvailable = true
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } returns TmdbKeyCheck.ACCEPTED
        val vm = viewModel()
        assertNull(vm.phoneUrl.value)

        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        assertEquals("http://10.0.0.2:8120/k/1/", vm.phoneUrl.value)
        vm.onEvent(TmdbSettingsEvent.CloseKeyPanel)
        assertNull(vm.phoneUrl.value)
        assertTrue(phonePages[0].first.isClosed)

        // Reopening starts a new page (new token); a key saved from the TV field closes it.
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        assertEquals("http://10.0.0.2:8120/k/2/", vm.phoneUrl.value)
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        runCurrent()
        assertNull(vm.phoneUrl.value)
        assertTrue(phonePages[1].first.isClosed)
    }

    @Test
    fun `clearing the ViewModel closes the phone page`() = runTest(mainDispatcherRule.dispatcher) {
        phoneAvailable = true
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        val store = ViewModelStore()
        store.put("tmdb", vm)

        store.clear()

        assertTrue(phonePages[0].first.isClosed)
    }

    @Test
    fun `remove names the key the panel showed`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { keyEntry.remove(any(), any()) } returns true
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        vm.onEvent(TmdbSettingsEvent.RemoveKey)
        advanceUntilIdle()
        coVerify { keyEntry.remove(any(), SAVED_KEY) }
    }

    @Test
    fun `no network or no free port leaves the panel without a phone block`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        assertEquals(TmdbKeyPanelState.OPEN, vm.keyPanel.value)
        assertNull(vm.phoneUrl.value)
    }

    @Test
    fun `a key accepted from the phone closes its panel with the toast`() = runTest(mainDispatcherRule.dispatcher) {
        phoneAvailable = true
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } returns TmdbKeyCheck.ACCEPTED
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)

        assertEquals(TmdbKeyAnswer.SAVED, phoneSend(1))
        runCurrent()

        assertNull(vm.keyPanel.value)
        assertTrue(vm.keySavedNotice.value)
        assertTrue(phonePages[0].first.isClosed)
    }

    @Test
    fun `a phone key accepted for a closed panel never closes the reopened one`() = runTest(mainDispatcherRule.dispatcher) {
        phoneAvailable = true
        // As the real entry for a check that began before the close: the key is valid, so it is saved.
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } returns TmdbKeyCheck.ACCEPTED
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        vm.onEvent(TmdbSettingsEvent.CloseKeyPanel)
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)

        assertEquals(TmdbKeyAnswer.SAVED, phoneSend(1))
        runCurrent()

        assertEquals(TmdbKeyPanelState.OPEN, vm.keyPanel.value)
        assertFalse(vm.keySavedNotice.value)
        assertFalse(phonePages[1].first.isClosed)
    }

    @Test
    fun `a TV check skipped because the phone saved first leaves the closing to the phone`() = runTest(mainDispatcherRule.dispatcher) {
        phoneAvailable = true
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } returns null
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        runCurrent()
        assertEquals(TmdbKeyPanelState.CHECKING, vm.keyPanel.value)

        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } returns TmdbKeyCheck.ACCEPTED
        phoneSend(1)
        runCurrent()
        assertNull(vm.keyPanel.value)
        assertTrue(vm.keySavedNotice.value)
    }

    @Test
    fun `an accepted key closes the panel and asks for the saved toast once`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } returns TmdbKeyCheck.ACCEPTED
        val vm = viewModel()

        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        assertEquals(TmdbKeyPanelState.CHECKING, vm.keyPanel.value)
        advanceUntilIdle()

        assertNull(vm.keyPanel.value)
        assertTrue(vm.keySavedNotice.value)
        vm.onEvent(TmdbSettingsEvent.KeySavedNoticeShown)
        assertFalse(vm.keySavedNotice.value)
    }

    @Test
    fun `a rejected or unanswered check keeps the panel open with the reason`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)

        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } returns TmdbKeyCheck.REJECTED
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        advanceUntilIdle()
        assertEquals(TmdbKeyPanelState.REJECTED, vm.keyPanel.value)

        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } returns TmdbKeyCheck.UNREACHABLE
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        advanceUntilIdle()
        assertEquals(TmdbKeyPanelState.UNREACHABLE, vm.keyPanel.value)
        assertFalse(vm.keySavedNotice.value)
    }

    @Test
    fun `blank input and a second submit while checking send nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val answer = CompletableDeferred<TmdbKeyCheck>()
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } coAnswers { answer.await() }
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)

        vm.onEvent(TmdbSettingsEvent.SubmitKey("   "))
        assertEquals(TmdbKeyPanelState.OPEN, vm.keyPanel.value)

        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        advanceUntilIdle()
        coVerify(exactly = 1) { keyEntry.checkAndSave(any(), any(), any()) }

        answer.complete(TmdbKeyCheck.REJECTED)
        advanceUntilIdle()
        assertEquals(TmdbKeyPanelState.REJECTED, vm.keyPanel.value)
    }

    @Test
    fun `an answer for a closed panel never reaches the reopened one`() = runTest(mainDispatcherRule.dispatcher) {
        val answer = CompletableDeferred<TmdbKeyCheck>()
        // As the real entry: once TMDB has answered, the save finishes even after a cancel.
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } coAnswers { withContext(NonCancellable) { answer.await() } }
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        advanceUntilIdle()

        vm.onEvent(TmdbSettingsEvent.CloseKeyPanel)
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        answer.complete(TmdbKeyCheck.ACCEPTED)
        advanceUntilIdle()

        assertEquals(TmdbKeyPanelState.OPEN, vm.keyPanel.value)
        assertFalse(vm.keySavedNotice.value)
    }

    @Test
    fun `closing the panel cancels a running check and its answer changes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val answer = CompletableDeferred<TmdbKeyCheck>()
        var cancelled = false
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } coAnswers {
            try {
                answer.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        advanceUntilIdle()

        vm.onEvent(TmdbSettingsEvent.CloseKeyPanel)
        advanceUntilIdle()
        answer.complete(TmdbKeyCheck.ACCEPTED)
        advanceUntilIdle()

        assertTrue(cancelled)
        assertNull(vm.keyPanel.value)
        assertFalse(vm.keySavedNotice.value)
    }

    @Test
    fun `remove closes the panel, a failed remove keeps it open`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)

        coEvery { keyEntry.remove(any(), any()) } returns false
        vm.onEvent(TmdbSettingsEvent.RemoveKey)
        advanceUntilIdle()
        assertEquals(TmdbKeyPanelState.OPEN, vm.keyPanel.value)

        coEvery { keyEntry.remove(any(), any()) } returns true
        vm.onEvent(TmdbSettingsEvent.RemoveKey)
        advanceUntilIdle()
        coVerify(exactly = 2) { keyEntry.remove(any(), any()) }
        assertNull(vm.keyPanel.value)
    }

    @Test
    fun `remove and submit never overlap`() = runTest(mainDispatcherRule.dispatcher) {
        val check = CompletableDeferred<TmdbKeyCheck>()
        coEvery { keyEntry.checkAndSave(KEY, any(), any()) } coAnswers { check.await() }
        val removal = CompletableDeferred<Boolean>()
        coEvery { keyEntry.remove(any(), any()) } coAnswers { removal.await() }
        val vm = viewModel()
        vm.onEvent(TmdbSettingsEvent.OpenKeyPanel)

        // A remove while a check runs is ignored.
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        advanceUntilIdle()
        vm.onEvent(TmdbSettingsEvent.RemoveKey)
        advanceUntilIdle()
        coVerify(exactly = 0) { keyEntry.remove(any(), any()) }
        check.complete(TmdbKeyCheck.REJECTED)
        advanceUntilIdle()

        // A submit while a removal runs is ignored, so the removal's close cannot cut it off.
        vm.onEvent(TmdbSettingsEvent.RemoveKey)
        advanceUntilIdle()
        vm.onEvent(TmdbSettingsEvent.SubmitKey(KEY))
        vm.onEvent(TmdbSettingsEvent.RemoveKey)
        advanceUntilIdle()
        coVerify(exactly = 1) { keyEntry.checkAndSave(any(), any(), any()) }
        coVerify(exactly = 1) { keyEntry.remove(any(), any()) }
        removal.complete(true)
        advanceUntilIdle()
        assertNull(vm.keyPanel.value)
    }

    @Test
    fun `the submit event never prints the key`() {
        assertFalse(TmdbSettingsEvent.SubmitKey(KEY).toString().contains(KEY))
    }

    private companion object {
        const val KEY = "4c1e9a7b2f6d08e35a9c1b7f42d6e0a8"
        const val SAVED_KEY = "0123456789abcdef0123456789abcdef"
    }
}
