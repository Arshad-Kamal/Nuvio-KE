package com.nuvio.tv.ui.screens.cast

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonCredit
import com.nuvio.tv.domain.model.PersonDepartment
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The VM pipeline (Spec 29c): Loading(route name) -> Error -> retry -> Success, rebuilt on Card shape. */
class CastDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val landscape = MutableStateFlow(false)
    private val service = mockk<TmdbMetadataService>()
    private val context = mockk<Context>(relaxed = true) {
        every { getString(any(), *anyVararg()) } returns "Could not load"
    }

    @Test
    fun `loading names the route's person, a missing person is an error, retry loads the page`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { service.fetchPersonDetail(any(), any()) } returnsMany listOf(null, person)
            val viewModel = viewModel()

            assertEquals(CastDetailUiState.Loading("Mira"), viewModel.uiState.value)
            assertEquals(CastDetailUiState.Error("Could not load"), viewModel.uiState.first { it !is CastDetailUiState.Loading })

            viewModel.retry()
            val page = (viewModel.uiState.first { it is CastDetailUiState.Success } as CastDetailUiState.Success).page
            assertEquals("poster", page.tabs.single().cards.single().item.imageUrl)

            landscape.value = true
            val landscapePage = viewModel.uiState.first { (it as? CastDetailUiState.Success)?.page?.landscape == true }
            assertEquals("backdrop", (landscapePage as CastDetailUiState.Success).page.tabs.single().cards.single().item.imageUrl)
        }

    @Test
    fun `no TMDB key shows the key message without a request, then loads once a key is saved`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { service.fetchPersonDetail(any(), any()) } returns person
            keyState.value = TmdbKeyState(userKey = "", effectiveKey = "")
            val viewModel = viewModel()

            assertEquals(CastDetailUiState.NeedsTmdbKey, viewModel.uiState.first { it !is CastDetailUiState.Loading })
            coVerify(exactly = 0) { service.fetchPersonDetail(any(), any()) }

            keyState.value = TmdbKeyState(userKey = "user-key", effectiveKey = "user-key")
            viewModel.uiState.first { it is CastDetailUiState.Success }
            coVerify(exactly = 1) { service.fetchPersonDetail(any(), any()) }
        }

    private val keyState = MutableStateFlow<TmdbKeyState?>(TmdbKeyState(userKey = "", effectiveKey = "built-in"))

    private fun viewModel() = CastDetailViewModel(
        context = context,
        tmdbMetadataService = service,
        tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> { every { settings } returns MutableStateFlow(mockk<TmdbSettings>(relaxed = true)) },
        tmdbApiKeys = mockk<TmdbApiKeyProvider> { every { state } returns keyState },
        layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore> { every { modernLandscapePostersEnabled } returns landscape },
        watchProgressRepository = mockk(relaxed = true),
        watchedSeriesStateHolder = mockk(relaxed = true),
        posterOptions = mockk(relaxed = true),
        savedStateHandle = SavedStateHandle(mapOf("personId" to "7", "personName" to "Mira"))
    )

    // No dates: the life line needs Android's date skeletons, which plain JVM tests stub out.
    private val person = PersonDetail(
        tmdbId = 7, name = "Mira", biography = null, birthday = null, deathday = null, placeOfBirth = null,
        profilePhoto = null, knownFor = "Acting", alsoKnownAs = emptyList(), creditsWithoutPoster = 0,
        departments = listOf(
            PersonDepartment(
                "Acting",
                listOf(
                    PersonCredit(
                        preview = MetaPreview(
                            id = "tmdb:1", type = ContentType.MOVIE, name = "Spring", poster = "poster",
                            posterShape = PosterShape.POSTER, background = "backdrop", logo = null, description = null,
                            releaseInfo = "2019", imdbRating = null, genres = emptyList()
                        ),
                        roles = listOf("Celia"), episodeCount = null, date = "2019-04-04"
                    )
                )
            )
        )
    )
}
