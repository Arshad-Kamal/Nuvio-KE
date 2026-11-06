package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.profile.DpadCombo
import com.nuvio.tv.core.profile.DpadDirection
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.PinLockouts
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.local.DpadUnlockState
import com.nuvio.tv.data.local.MyPhotosStore
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.ProfileLookStore
import com.nuvio.tv.domain.model.ProfileLookChoice
import com.nuvio.tv.domain.model.ProfileLookEntry
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.livetv.data.LiveTvUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val profileSyncService = mockk<ProfileSyncService>(relaxed = true)
    private val avatarRepository = mockk<AvatarRepository>(relaxed = true)
    private val profileBackgroundRepository = mockk<ProfileBackgroundRepository>(relaxed = true)
    private val profileLockStateDataStore = mockk<ProfileLockStateDataStore>(relaxed = true)
    private val liveTvUserStore = mockk<LiveTvUserStore>(relaxed = true)
    private val profileLookStore = mockk<ProfileLookStore>(relaxed = true)
    private val myPhotosStore = mockk<MyPhotosStore>(relaxed = true)

    private val sequence = listOf(
        DpadDirection.UP, DpadDirection.DOWN, DpadDirection.LEFT,
        DpadDirection.RIGHT, DpadDirection.UP, DpadDirection.DOWN
    )

    private fun viewModelWith(
        state: DpadUnlockState,
        profiles: List<UserProfile> = emptyList()
    ): ProfileSelectionViewModel {
        every { profileLockStateDataStore.dpadUnlockState } returns flowOf(state)
        every { profileLockStateDataStore.pinEnabled } returns flowOf(emptyMap())
        every { profileManager.activeProfileId } returns MutableStateFlow(0)
        every { profileManager.profiles } returns MutableStateFlow(profiles)
        return ProfileSelectionViewModel(
            profileManager = profileManager,
            profileSyncService = profileSyncService,
            avatarRepository = avatarRepository,
            profileBackgroundRepository = profileBackgroundRepository,
            profileLockStateDataStore = profileLockStateDataStore,
            pinLockouts = PinLockouts(),
            liveTvUserStore = liveTvUserStore,
            profileLookStore = profileLookStore,
            myPhotosStore = myPhotosStore,
            photoUploadStarter = mockk(relaxed = true)
        )
    }

    /** Drives verifyDpadCombo to completion; null means the callback never fired. */
    private fun TestScope.verdictFor(state: DpadUnlockState, sequence: List<DpadDirection>): Boolean? {
        var matched: Boolean? = null
        viewModelWith(state).verifyDpadCombo(sequence) { matched = it }
        advanceUntilIdle()
        return matched
    }

    private fun storedCombo(): DpadUnlockState = DpadUnlockState(
        comboHash = DpadCombo.hash(sequence, SALT),
        salt = SALT,
        comboLength = sequence.size
    )

    @Test
    fun `the saved sequence of the stored length matches`() = runTest {
        assertEquals(true, verdictFor(storedCombo(), sequence))
    }

    @Test
    fun `a wrong sequence of the same length fails`() = runTest {
        val wrong = sequence.dropLast(1) + DpadDirection.LEFT
        assertEquals(false, verdictFor(storedCombo(), wrong))
    }

    @Test
    fun `a sequence shorter than the stored length never matches`() = runTest {
        assertEquals(false, verdictFor(storedCombo(), sequence.dropLast(1)))
    }

    @Test
    fun `an unset combination never matches`() = runTest {
        assertEquals(false, verdictFor(DpadUnlockState(enabled = true), sequence))
    }

    @Test
    fun `verify hashes with the stored salt`() = runTest {
        // The same presses under any salt other than the stored one must not
        // unlock — this pins that verifyDpadCombo feeds the STORED salt (and
        // the argument order) into DpadCombo.verify.
        val hashUnderOtherSalt = DpadCombo.hash(sequence, DpadCombo.generateSalt())
        assertEquals(false, verdictFor(storedCombo().copy(comboHash = hashUnderOtherSalt), sequence))
    }

    @Test
    fun `create reports the local save and pushes only after one`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returnsMany listOf(created(2), null)
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        viewModel.createProfile("Amira", "#1E88E5", null) { results += it }
        advanceUntilIdle()
        viewModel.createProfile("Guest", "#1E88E5", null) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true, false), results)
        coVerify(exactly = 1) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `update reports the local save, false once the profile is gone, and pushes only after one`() = runTest {
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
        coEvery { profileManager.updateProfile(sara) } returnsMany listOf(true, false)
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        viewModel.updateProfile(sara) { results += it }
        advanceUntilIdle()
        viewModel.updateProfile(sara) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true, false), results)
        coVerify(exactly = 1) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `create always writes the live tv switch for the new id, an edit only when passed after a save (D17)`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returns created(4)
        coEvery { liveTvUserStore.setEnabled(any(), any()) } returns true
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
        coEvery { profileManager.updateProfile(sara) } returnsMany listOf(true, true, false)
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        viewModel.createProfile("Kids", "#1E88E5", null, liveTvEnabled = true) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 1) { liveTvUserStore.setEnabled(4, true) }
        viewModel.createProfile("Kids", "#1E88E5", null, liveTvEnabled = false) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 1) { liveTvUserStore.setEnabled(4, false) }
        viewModel.updateProfile(sara) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 0) { liveTvUserStore.setEnabled(2, any()) }
        viewModel.updateProfile(sara, liveTvEnabled = false) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 1) { liveTvUserStore.setEnabled(2, false) }
        // The profile is gone: no switch write, the save fails.
        viewModel.updateProfile(sara, liveTvEnabled = true) { results += it }
        advanceUntilIdle()
        coVerify(exactly = 0) { liveTvUserStore.setEnabled(2, true) }
        assertEquals(listOf(true, true, true, true, false), results)
    }

    @Test
    fun `a failed live tv write keeps the profile saved, reports it apart and still pushes`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returns created(4)
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
        coEvery { profileManager.updateProfile(sara) } returns true
        coEvery { liveTvUserStore.setEnabled(any(), any()) } returns false
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        var notSaved = 0
        viewModel.createProfile("Kids", "#1E88E5", null, false, { notSaved++ }) { results += it }
        advanceUntilIdle()
        viewModel.updateProfile(sara, false, { notSaved++ }) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true, true), results)
        assertEquals(2, notSaved)
        coVerify(exactly = 2) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `the live tv map holds every profile's switch and follows a change`() = runTest {
        val kidsSwitch = MutableStateFlow(false)
        every { liveTvUserStore.enabledFor(1) } returns flowOf(true)
        every { liveTvUserStore.enabledFor(2) } returns kidsSwitch
        val viewModel = viewModelWith(
            DpadUnlockState(),
            profiles = listOf(
                UserProfile(id = 1, name = "Arshad", avatarColorHex = "#1E88E5"),
                UserProfile(id = 2, name = "Kids", avatarColorHex = "#43A047")
            )
        )
        advanceUntilIdle()
        assertEquals(mapOf(1 to true, 2 to false), viewModel.liveTvEnabledById.value)
        kidsSwitch.value = true
        advanceUntilIdle()
        assertEquals(mapOf(1 to true, 2 to true), viewModel.liveTvEnabledById.value)
    }

    private fun created(id: Int) = UserProfile(id = id, name = "Saved $id", avatarColorHex = "#1E88E5")

    @Test
    fun `create writes the look entry for the new id before the result, keyed on the saved profile (Spec 44)`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returnsMany listOf(created(4), created(5), null)
        val events = mutableListOf<String>()
        coEvery { profileLookStore.write(any(), any()) } coAnswers { events += "write ${firstArg<Int>()}"; true }
        val viewModel = viewModelWith(DpadUnlockState())
        viewModel.createProfile("Kids", "#1E88E5", null) { events += "result $it" }
        advanceUntilIdle()
        coVerify(exactly = 1) { profileLookStore.write(4, null) }
        val fox = ProfileLookPick.Bundled("fox")
        viewModel.createProfile("Kids", "#1E88E5", null, localAvatar = fox) { events += "result $it" }
        advanceUntilIdle()
        // The key is the profile as saved (ProfileManager may rename it), not the typed name.
        coVerify(exactly = 1) {
            profileLookStore.write(5, ProfileLookEntry(avatar = ProfileLookChoice(fox, "Saved 5", null)))
        }
        // At the cap nothing is written.
        viewModel.createProfile("Kids", "#1E88E5", null, localAvatar = fox) { events += "result $it" }
        advanceUntilIdle()
        assertEquals(listOf("write 4", "result true", "write 5", "result true", "result false"), events)
    }

    @Test
    fun `update writes the look entry before the result and the push, never for a vanished profile`() = runTest {
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B", avatarId = "kira")
        coEvery { profileManager.updateProfile(sara) } returnsMany listOf(true, true, false)
        val events = mutableListOf<String>()
        coEvery { profileLookStore.write(any(), any()) } coAnswers { events += "write"; true }
        coEvery { profileSyncService.pushToRemote() } coAnswers { events += "push"; Result.success(Unit) }
        val viewModel = viewModelWith(DpadUnlockState())
        val photo = ProfileLookPick.Photo("p1")
        viewModel.updateProfile(sara, localAvatar = photo) { events += "result $it" }
        advanceUntilIdle()
        assertEquals(listOf("write", "result true", "push"), events)
        coVerify { profileLookStore.write(2, ProfileLookEntry(avatar = ProfileLookChoice(photo, "Sara", "kira"))) }
        viewModel.updateProfile(sara) {}
        advanceUntilIdle()
        coVerify(exactly = 1) { profileLookStore.write(2, null) }
        viewModel.updateProfile(sara, localAvatar = photo) {}
        advanceUntilIdle()
        coVerify(exactly = 2) { profileLookStore.write(any(), any()) }
    }

    @Test
    fun `a failed look write keeps the profile saved and is reported apart`() = runTest {
        coEvery { profileManager.createProfile(any(), any(), any(), any()) } returns created(4)
        val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B")
        coEvery { profileManager.updateProfile(sara) } returns true
        coEvery { profileLookStore.write(any(), any()) } returns false
        val viewModel = viewModelWith(DpadUnlockState())
        val results = mutableListOf<Boolean>()
        var notSaved = 0
        viewModel.createProfile("Kids", "#1E88E5", null, onLookNotSaved = { notSaved++ }) { results += it }
        advanceUntilIdle()
        viewModel.updateProfile(sara, onLookNotSaved = { notSaved++ }) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(true, true), results)
        assertEquals(2, notSaved)
        coVerify(exactly = 2) { profileSyncService.pushToRemote() }
    }

    @Test
    fun `the look map is null until loaded, then holds only profiles with an entry`() = runTest {
        val kidsLook = ProfileLookEntry(avatar = ProfileLookChoice(ProfileLookPick.Bundled("fox"), "Kids", null))
        val kidsFlow = MutableStateFlow<ProfileLookEntry?>(kidsLook)
        every { profileLookStore.entryFor(1) } returns flowOf(null)
        every { profileLookStore.entryFor(2) } returns kidsFlow
        val viewModel = viewModelWith(
            DpadUnlockState(),
            profiles = listOf(
                UserProfile(id = 1, name = "Arshad", avatarColorHex = "#1E88E5"),
                UserProfile(id = 2, name = "Kids", avatarColorHex = "#43A047")
            )
        )
        assertEquals(null, viewModel.lookEntriesById.value)
        advanceUntilIdle()
        assertEquals(mapOf(2 to kidsLook), viewModel.lookEntriesById.value)
        kidsFlow.value = null
        advanceUntilIdle()
        assertEquals(emptyMap<Int, ProfileLookEntry>(), viewModel.lookEntriesById.value)
    }

    private companion object {
        const val SALT = "00112233445566778899aabbccddeeff"
    }
}
