package com.nuvio.tv.ui.screens.settings

import android.graphics.Typeface
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.server.SubtitleFontServer
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.local.UserSubtitleFont
import com.nuvio.tv.data.local.UserSubtitleFontAddResult
import com.nuvio.tv.data.local.UserSubtitleFontStore
import com.nuvio.tv.data.local.playerSettingsOver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Spec 132 S4: when the font page runs, what pick and remove store, and the Font row's state. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleFontsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun font(n: Int) = UserSubtitleFont(
        "font-$n-uuid", "Family $n", "Family-$n.ttf", 1000L * n, Instant.fromEpochMilliseconds(n.toLong()), File("f$n")
    )

    private val listed = MutableStateFlow<List<UserSubtitleFont>?>(listOf(font(1), font(2)))
    private val removed = mutableListOf<String>()
    private val store = mockk<UserSubtitleFontStore>(relaxed = true).also {
        every { it.fonts } returns listed
        coEvery { it.remove(any()) } answers {
            val id = firstArg<String>()
            removed += id
            listed.value = listed.value.orEmpty().filterNot { f -> f.id == id }
            true
        }
        coEvery { it.add(any(), any()) } returns UserSubtitleFontAddResult.Failed
    }
    private val profile = TestPreferencesDataStore()
    private val otherProfile = TestPreferencesDataStore()
    private val activeProfile = MutableStateFlow(1)
    private val settings = playerSettingsOver(profile, activeProfileId = activeProfile) { if (it == 2) otherProfile else profile }

    private val servers = mutableListOf<SubtitleFontServer>()
    private var fontCount: (() -> Int)? = null
    private var network = true
    private val loadedFiles = mutableListOf<File>()
    private val face = mockk<Typeface>()
    /** Runs inside each face load, on the io dispatcher. */
    private var duringLoad: () -> Unit = {}

    private fun viewModel(io: CoroutineDispatcher = main.dispatcher) = SubtitleFontsViewModel(
        store = store,
        settings = settings,
        startPhone = { count, _ ->
            if (!network) null else {
                fontCount = count
                val server = mockk<SubtitleFontServer>(relaxed = true)
                servers += server
                StartedFontServer(server, "http://192.0.2.119:8170/f/token${servers.size}/")
            }
        },
        loadTypeface = { file -> loadedFiles += file; duringLoad(); face.takeUnless { file.name == "f2" } },
        io = io,
        phoneLifetime = 10.minutes
    )

    private fun idle() = main.dispatcher.scheduler.runCurrent()
    private fun chosenId() = runBlocking { settings.playerSettings.first().subtitleStyle.fontId }

    @Test
    fun `the phone page runs only while the panel is open, and ends with its lifetime`() {
        val vm = viewModel()
        idle()
        assertTrue(servers.isEmpty())

        vm.openPanel()
        idle()
        assertEquals("http://192.0.2.119:8170/f/token1/", vm.phoneUrl.value)
        assertEquals(2, fontCount!!())

        vm.closePanel()
        assertNull(vm.phoneUrl.value)
        verify { servers.single().stop() }

        vm.openPanel()
        idle()
        main.dispatcher.scheduler.advanceTimeBy(10.minutes.inWholeMilliseconds + 1)
        idle()
        assertNull("the page ends after its lifetime", vm.phoneUrl.value)
        verify { servers[1].stop() }
        assertTrue("the panel stays open", vm.panelOpen.value)
    }

    @Test
    fun `no network shows no QR and the panel still works`() {
        network = false
        val vm = viewModel()
        vm.openPanel()
        idle()
        assertNull(vm.phoneUrl.value)
        assertTrue(vm.panelOpen.value)
    }

    @Test
    fun `a full TV keeps the page running, so a phone that loaded it is told why`() {
        listed.value = (1..UserSubtitleFontStore.CAP).map(::font)
        val vm = viewModel()
        vm.openPanel()
        idle()
        assertTrue(vm.phoneUrl.value != null)
        assertEquals(UserSubtitleFontStore.CAP, fontCount!!())
    }

    @Test
    fun `faces load only while the panel is open, try an unloadable font once, and drop on close`() {
        val vm = viewModel()
        idle()
        assertTrue(loadedFiles.isEmpty())

        vm.openPanel()
        idle()
        assertEquals(setOf("font-1-uuid"), vm.typefaces.value.keys)

        // A sent font joins the list: only its face is loaded.
        loadedFiles.clear()
        listed.value = listed.value!! + font(3)
        idle()
        assertEquals(setOf("font-1-uuid", "font-3-uuid"), vm.typefaces.value.keys)
        assertEquals(listOf("f3"), loadedFiles.map { it.name })

        vm.closePanel()
        assertTrue(vm.typefaces.value.isEmpty())
        loadedFiles.clear()
        listed.value = listed.value!! + font(4)
        idle()
        assertTrue("nothing loads with the panel closed", loadedFiles.isEmpty())

        // A reopened panel tries every font again.
        vm.openPanel()
        idle()
        assertEquals(listOf("f1", "f2", "f3", "f4"), loadedFiles.map { it.name })
    }

    @Test
    fun `a close while faces load publishes nothing, and a reopen loads them`() {
        lateinit var vm: SubtitleFontsViewModel
        // The panel closes while the first face is being loaded.
        duringLoad = { duringLoad = {}; vm.closePanel() }
        vm = viewModel()
        vm.openPanel()
        idle()
        assertTrue(loadedFiles.isNotEmpty())
        assertTrue(vm.typefaces.value.isEmpty())

        vm.openPanel()
        idle()
        assertEquals(setOf("font-1-uuid"), vm.typefaces.value.keys)
    }

    @Test
    fun `clearing the model stops the page`() {
        val vm = viewModel()
        vm.openPanel()
        idle()
        val owner = ViewModelStore().apply { put("fonts", vm) }
        owner.clear()
        verify { servers.single().stop() }
    }

    @Test
    fun `a pick stores the id and family for this profile and closes the panel`() {
        val vm = viewModel()
        vm.openPanel()
        idle()
        vm.pick(font(2))
        idle()
        assertFalse(vm.panelOpen.value)
        assertNull(vm.phoneUrl.value)
        val style = runBlocking { settings.playerSettings.first().subtitleStyle }
        assertEquals("font-2-uuid", style.fontId)
        assertEquals("Family 2", style.fontName)

        vm.openPanel()
        vm.pick(null)
        idle()
        assertNull(chosenId())
    }

    @Test
    fun `removing the chosen font returns this profile to Default, before the file goes`() {
        runBlocking { settings.setSubtitleFont("font-1-uuid", "Family 1") }
        var chosenAtRemove: String? = "unset"
        coEvery { store.remove(any()) } answers {
            chosenAtRemove = chosenId()
            removed += firstArg<String>()
            true
        }
        val vm = viewModel()
        vm.openPanel()
        vm.askRemove(font(1))
        assertEquals(font(1), vm.confirm.value)
        vm.confirmRemove()
        idle()
        assertEquals(listOf("font-1-uuid"), removed)
        assertNull("the row never shows the font as gone", chosenAtRemove)
        assertNull(chosenId())
        assertNull(vm.confirm.value)
        assertTrue(vm.panelOpen.value)
    }

    @Test
    fun `another profile's choice of a removed font stays, for its row to say the font is gone`() {
        runBlocking {
            activeProfile.value = 2
            settings.setSubtitleFont("font-1-uuid", "Family 1")
            activeProfile.value = 1
        }
        val vm = viewModel()
        vm.openPanel()
        vm.askRemove(font(1))
        vm.confirmRemove()
        idle()
        assertEquals(listOf("font-1-uuid"), removed)
        activeProfile.value = 2
        assertEquals("font-1-uuid", chosenId())
    }

    @Test
    fun `a second Remove while the first runs removes once`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { store.remove(any()) } coAnswers {
            gate.await()
            removed += firstArg<String>()
            true
        }
        val vm = viewModel()
        vm.openPanel()
        vm.askRemove(font(2))
        vm.confirmRemove()
        idle()
        vm.confirmRemove()
        idle()
        gate.complete(Unit)
        idle()
        assertEquals(listOf("font-2-uuid"), removed)
    }

    @Test
    fun `removing another font keeps the choice, and cancel removes nothing`() {
        runBlocking { settings.setSubtitleFont("font-1-uuid", "Family 1") }
        val vm = viewModel()
        vm.openPanel()
        vm.askRemove(font(2))
        vm.cancelConfirm()
        vm.confirmRemove()
        idle()
        assertTrue(removed.isEmpty())

        vm.askRemove(font(2))
        vm.confirmRemove()
        idle()
        assertEquals(listOf("font-2-uuid"), removed)
        assertEquals("font-1-uuid", chosenId())
    }

    @Test
    fun `a failed removal puts the choice back`() {
        coEvery { store.remove(any()) } returns false
        runBlocking { settings.setSubtitleFont("font-1-uuid", "Family 1") }
        val vm = viewModel()
        vm.openPanel()
        vm.askRemove(font(1))
        vm.confirmRemove()
        idle()
        assertEquals("font-1-uuid", chosenId())
        assertEquals("Family 1", runBlocking { settings.playerSettings.first().subtitleStyle.fontName })
        assertNull(vm.confirm.value)
    }

    @Test
    fun `a failed removal keeps a pick made meanwhile`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { store.remove(any()) } coAnswers { gate.await(); false }
        runBlocking { settings.setSubtitleFont("font-1-uuid", "Family 1") }
        val vm = viewModel()
        vm.openPanel()
        vm.askRemove(font(1))
        vm.confirmRemove()
        idle()
        runBlocking { settings.setSubtitleFont("font-2-uuid", "Family 2") }
        gate.complete(Unit)
        idle()
        assertEquals("font-2-uuid", chosenId())
    }

    @Test
    fun `remove asks only while the panel is open`() {
        val vm = viewModel()
        vm.askRemove(font(1))
        assertNull(vm.confirm.value)
        vm.confirmRemove()
        idle()
        coVerify(exactly = 0) { store.remove(any()) }
    }

    @Test
    fun `the Font row state follows the stored id and the read list`() {
        val fonts = listOf(font(1))
        assertEquals(SubtitleFontChoice.Default, subtitleFontChoice(null, fonts))
        assertEquals(SubtitleFontChoice.Unknown, subtitleFontChoice("font-1-uuid", null))
        assertEquals(SubtitleFontChoice.Chosen(font(1)), subtitleFontChoice("font-1-uuid", fonts))
        assertEquals(SubtitleFontChoice.Missing, subtitleFontChoice("font-9-uuid", fonts))
        assertEquals(SubtitleFontChoice.Missing, subtitleFontChoice("font-1-uuid", emptyList()))
    }
}
