package com.nuvio.tv.ui.screens.tmdb

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.nuvio.tv.ui.screens.home.layoutPrefsStoreEmitting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TmdbEntityBrowseViewModelNavArgsTest {

    // StandardTestDispatcher as Main: the init loads are queued and never run here.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `entity name keeps plus signs and percent escapes`() {
        // Navigation has already decoded the arg once; a second decode would give "Disney  A".
        val viewModel = viewModel(mapOf("entityKind" to "company", "entityId" to 2, "entityName" to "Disney+ %41"))

        assertEquals("Disney+ %41", viewModel.entityName)
        assertEquals("", viewModel.sourceTitle)
        assertEquals("", viewModel.sectionLabel)
        assertEquals(null, viewModel.breadcrumb)
    }

    @Test
    fun `breadcrumb comes from the route`() {
        val viewModel = viewModel(
            mapOf(
                "entityKind" to "network", "entityId" to 49, "entityName" to "HBO",
                "sourceTitle" to "Cosmos+Laundromat", "sectionLabel" to "Network"
            )
        )

        assertEquals("Cosmos+Laundromat", viewModel.sourceTitle)
        assertEquals("Network", viewModel.sectionLabel)
        assertEquals(listOf("Cosmos+Laundromat", "Network"), viewModel.breadcrumb)
    }

    @Test
    fun `no TMDB key shows the key message without a request, then loads once a key is saved`() =
        runTest(mainDispatcherRule.dispatcher) {
            val keyState = MutableStateFlow<TmdbKeyState?>(TmdbKeyState(userKey = "", effectiveKey = ""))
            val metadata = mockk<TmdbMetadataService> {
                coEvery { fetchEntityBrowse(any(), any(), any(), any(), any()) } returns null
            }
            val viewModel = viewModel(
                mapOf("entityKind" to "company", "entityId" to 2, "entityName" to "Blender Studio"),
                metadata = metadata,
                keys = mockk { every { state } returns keyState }
            )

            assertEquals(
                TmdbEntityBrowseUiState.NeedsTmdbKey,
                viewModel.uiState.first { it !is TmdbEntityBrowseUiState.Loading }
            )
            coVerify(exactly = 0) { metadata.fetchEntityBrowse(any(), any(), any(), any(), any()) }

            keyState.value = TmdbKeyState(userKey = "user-key", effectiveKey = "user-key")
            // The stub finds nothing, so the load that reached TMDB settles on its error.
            viewModel.uiState.first { it is TmdbEntityBrowseUiState.Error }
            coVerify(exactly = 1) { metadata.fetchEntityBrowse(any(), any(), any(), any(), any()) }
        }

    private fun viewModel(
        args: Map<String, Any>,
        metadata: TmdbMetadataService = mockk(relaxed = true),
        keys: TmdbApiKeyProvider = mockk(relaxed = true)
    ) = TmdbEntityBrowseViewModel(
        context = mockk(relaxed = true),
        tmdbMetadataService = metadata,
        tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en"))
        },
        tmdbApiKeys = keys,
        layoutPreferenceDataStore = layoutPrefsStoreEmitting(),
        // Real flows: a test that runs init collects them (a relaxed StateFlow's collect returns, which
        // its Nothing type forbids).
        watchProgressRepository = mockk(relaxed = true) {
            every { observeWatchedMovieIds() } returns MutableStateFlow(emptySet())
        },
        watchedSeriesStateHolder = mockk(relaxed = true) {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        },
        posterOptions = mockk(relaxed = true),
        savedStateHandle = SavedStateHandle(args)
    )
}
