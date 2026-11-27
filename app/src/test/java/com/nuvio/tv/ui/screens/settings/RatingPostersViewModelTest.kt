package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.posters.RatingPosterSaveCheck
import com.nuvio.tv.core.posters.RatingPosterServerStarter
import com.nuvio.tv.core.posters.RatingPosterSettings
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.nuvio.tv.core.posters.RatingPosterServer
import com.nuvio.tv.core.posters.StartedPosterServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Spec 114 S3: the panel binds to the profile active when it opened; Turn off asks first and clears only that one. */
@OptIn(ExperimentalCoroutinesApi::class)
class RatingPostersViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val profileId = MutableStateFlow<Int?>(2)
    private val settings = mockk<RatingPosterSettings>(relaxed = true)
    private val servers = mutableListOf<RatingPosterServer>()
    private val starter = mockk<RatingPosterServerStarter> {
        every { start(any()) } answers {
            val server = RatingPosterServer.startOnAvailablePort({ "" }, firstArg(), startPort = 0, maxAttempts = 1)
                ?: error("no server")
            servers += server
            StartedPosterServer(server, server.url("10.0.0.2"))
        }
    }
    // Lazy: built once the rule has set Main, so viewModelScope runs on the test dispatcher. Tests run only
    // current tasks (runCurrent): advancing virtual time would expire the phone page's 10-minute lifetime.
    private val viewModel by lazy {
        RatingPostersViewModel(
        settings = settings,
        profileManager = mockk<ProfileManager> { every { storedActiveProfileId } returns profileId },
        saveCheck = mockk<RatingPosterSaveCheck>(),
        starter = starter,
        )
    }

    @After
    fun stopServers() = servers.forEach { it.stop() }

    @Test
    fun `turn off asks first, then clears the profile the panel opened for`() = runTest(mainRule.dispatcher) {
        viewModel.open()
        verify(exactly = 1) { starter.start(any()) }
        profileId.value = 5
        viewModel.confirmTurnOff()
        runCurrent()
        coVerify(exactly = 0) { settings.clear(any()) }

        viewModel.askTurnOff()
        assertEquals(RatingPostersPanelState.CONFIRM_OFF, viewModel.panel.value)
        viewModel.cancelTurnOff()
        assertEquals(RatingPostersPanelState.OPEN, viewModel.panel.value)

        viewModel.askTurnOff()
        viewModel.confirmTurnOff()
        runCurrent()
        assertEquals(RatingPostersPanelState.OPEN, viewModel.panel.value)
        coVerify(exactly = 1) { settings.clear(2) }
        // A new phone page: a send still being checked on the old one can't save the address back.
        assertEquals(2, servers.size)
        assertTrue(servers[0].isClosed)
        assertFalse(servers[1].isClosed)
        assertTrue(viewModel.phoneUrl.value!!.startsWith("http://10.0.0.2:"))
        viewModel.close()
    }

    @Test
    fun `a failed delete keeps the panel open, and a reopened panel binds the new profile`() = runTest(mainRule.dispatcher) {
        coEvery { settings.clear(any()) } throws IOException("disk")
        viewModel.open()
        viewModel.askTurnOff()
        viewModel.confirmTurnOff()
        runCurrent()
        assertEquals(RatingPostersPanelState.OPEN, viewModel.panel.value)

        assertNotNull(viewModel.phoneUrl.value)
        viewModel.close()
        assertNull(viewModel.panel.value)
        assertNull(viewModel.phoneUrl.value)
        assertTrue(servers.all { it.isClosed })

        coEvery { settings.clear(any()) } returns Unit
        profileId.value = 5
        viewModel.open()
        viewModel.askTurnOff()
        viewModel.confirmTurnOff()
        runCurrent()
        coVerify(exactly = 1) { settings.clear(5) }
        viewModel.close()
    }

    @Test
    fun `before the profiles load the panel opens without a phone page`() {
        profileId.value = null
        viewModel.open()
        assertEquals(RatingPostersPanelState.OPEN, viewModel.panel.value)
        verify(exactly = 0) { starter.start(any()) }
        assertNull(viewModel.phoneUrl.value)
    }
}
