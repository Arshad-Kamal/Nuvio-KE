package com.nuvio.tv.ui.screens.addon

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.AddonConfigServer
import com.nuvio.tv.core.server.AddonWebConfigMode
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.PageState
import com.nuvio.tv.core.server.PendingAddonChange
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * L5-09/30 / FR-25: confirming a QR proposal writes the synced disabled-key list once
 * (the proposal's addon-catalog keys, then its collection keys) and pushes once.
 * L5-b (QB2): removals are the served page's addons the proposal dropped.
 * Spec 31 P-Addons-2: Reload addon fetches the manifest once per press and reports the result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonManagerConfirmTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val disabledKey = stringPreferencesKey("disabled_home_catalog_keys")
    private val addonUrl = "https://addon.example"
    private val two = "addon_movie_two"
    private val collectionKey = "collection_c"

    /** Every distinct value the disabled-key list took, one entry per write that changed it. */
    private val disabledWrites = mutableListOf<String?>()
    private val store = object : TestPreferencesDataStore() {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val before = value[disabledKey]
            return super.updateData(transform).also { after ->
                if (after[disabledKey] != before) disabledWrites += after[disabledKey]
            }
        }
    }
    private val homeSync = mockk<HomeCatalogSettingsSyncService>(relaxed = true)
    private val installed = MutableStateFlow(listOf(addon()))
    private val addonRepository = mockk<AddonRepository>(relaxed = true)
    private val pageState = slot<() -> PageState>()
    private val context = mockk<Context>(relaxed = true).also {
        // loadLogoBytes swallows this; a relaxed InputStream would loop forever in readBytes.
        every { it.resources } throws IllegalStateException("no resources in unit tests")
        // Reload results, told apart by text.
        every { it.getString(R.string.addon_reload_failed, any()) } returns "failed"
        every { it.getString(R.string.addon_reload_done, any()) } returns "reloaded"
    }
    private val onChangeProposed = slot<(PendingAddonChange) -> Unit>()

    private fun startServer() {
        mockkObject(DeviceIpAddress, QrCodeGenerator, AddonConfigServer.Companion)
        every { DeviceIpAddress.get(any()) } returns "192.0.2.1"
        every { QrCodeGenerator.generate(any(), any(), any()) } returns mockk(relaxed = true)
        every {
            AddonConfigServer.startOnAvailablePort(
                any(), any(), capture(pageState), capture(onChangeProposed), any(), any(), any(), any(), any()
            )
        } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `confirm writes the disabled keys once and pushes once`() = runTest(mainDispatcherRule.dispatcher) {
        startServer()
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startQrMode(AddonWebConfigMode.FULL)
        onChangeProposed.captured(
            PendingAddonChange(
                proposedUrls = listOf(addonUrl),
                proposedDisabledCatalogKeys = listOf(two),
                proposedDisabledCollectionKeys = listOf(collectionKey)
            )
        )

        viewModel.confirmPendingChange()
        advanceUntilIdle()

        val expected = """["$two","$collectionKey"]"""
        assertEquals(listOf<String?>(expected), disabledWrites)
        assertEquals(expected, store.value[disabledKey])
        verify(exactly = 1) { homeSync.triggerPush() }
    }

    @Test
    fun `an addon that loaded after the page was served is not removed`() = runTest(mainDispatcherRule.dispatcher) {
        val late = "https://late.example"
        val other = "https://other.example"
        installed.value = listOf(addon(), addon(other))
        startServer()
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startQrMode(AddonWebConfigMode.FULL)
        pageState.captured() // the phone loads the page: addon + other; late still loading
        installed.value = listOf(addon(), addon(other), addon(late))
        advanceUntilIdle()

        onChangeProposed.captured(PendingAddonChange(proposedUrls = listOf(addonUrl)))
        assertEquals(listOf(other), viewModel.uiState.value.pendingChange?.removedUrls)
        viewModel.confirmPendingChange()
        advanceUntilIdle()

        coVerify(exactly = 1) { addonRepository.spliceAddonOrder(listOf(addonUrl), listOf(other)) }
    }

    @Test
    fun `reload fetches once, ignores a second press while running, and reports`() =
        runTest(mainDispatcherRule.dispatcher) {
            val gate = CompletableDeferred<NetworkResult<Addon>>()
            coEvery { addonRepository.fetchAddon(addonUrl) } coAnswers { gate.await() }
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.reloadAddon(addon())
            viewModel.reloadAddon(addon())
            runCurrent() // advanceUntilIdle would run the clock past the reload timeout
            assertNull(viewModel.uiState.value.transientMessage)
            gate.complete(NetworkResult.Error("down"))
            advanceUntilIdle()

            coVerify(exactly = 1) { addonRepository.fetchAddon(addonUrl) }
            verify { context.getString(R.string.addon_reload_failed, "Addon") }
            assertEquals("failed", viewModel.uiState.value.transientMessage)
        }

    @Test
    fun `a successful reload reports reloaded`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { addonRepository.fetchAddon(addonUrl) } returns NetworkResult.Success(addon())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.reloadAddon(addon())
        advanceUntilIdle()

        assertEquals("reloaded", viewModel.uiState.value.transientMessage)
    }

    private fun viewModel(): AddonManagerViewModel {
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        every { profileManager.profiles } returns MutableStateFlow(
            listOf(UserProfile(id = 1, name = "Parent", avatarColorHex = "#000000"))
        )
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store

        every { addonRepository.getInstalledAddons() } returns installed
        val collections = mockk<CollectionsDataStore>(relaxed = true)
        every { collections.collections } returns flowOf(emptyList())
        val healthStore = mockk<AddonHealthStore>(relaxed = true)
        every { healthStore.levels } returns flowOf(emptyMap())

        return AddonManagerViewModel(
            addonRepository = addonRepository,
            layoutPreferenceDataStore = LayoutPreferenceDataStore(factory, profileManager),
            collectionsDataStore = collections,
            collectionSyncService = mockk(relaxed = true),
            homeCatalogSettingsSyncService = homeSync,
            startupSyncService = mockk(relaxed = true),
            profileManager = profileManager,
            tmdbCollectionSourceResolver = mockk(relaxed = true),
            context = context,
            healthStore = healthStore
        )
    }

    private fun addon(url: String = addonUrl) = Addon(
        id = if (url == addonUrl) "addon" else url,
        name = "Addon",
        version = "1",
        description = null,
        logo = null,
        baseUrl = url,
        catalogs = listOf("one", "two").map { id ->
            CatalogDescriptor(type = ContentType.MOVIE, id = id, name = id)
        },
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )
}
