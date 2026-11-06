package com.nuvio.tv.ui.screens.cast

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.awaitKey
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.ui.screens.detail.formatShortDate
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CastDetailViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbApiKeys: TmdbApiKeyProvider,
    layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val personId: Int = savedStateHandle.get<String>("personId")?.toIntOrNull() ?: 0
    // Navigation already decoded the arg once; decoding again would turn '+' into a space.
    val personName: String = savedStateHandle.get<String>("personName").orEmpty()
    // The department tab to open first (TMDB name; blank = most credits) and the breadcrumb's
    // source title + section (Spec 29b).
    val department: String = savedStateHandle.get<String>("department").orEmpty()
    val sourceTitle: String = savedStateHandle.get<String>("sourceTitle").orEmpty()
    val sectionLabel: String = savedStateHandle.get<String>("sectionLabel").orEmpty()
    /** Source title › section ("Tears of Steel › Cast"); blank parts dropped. */
    val breadcrumb: List<String> = listOf(sourceTitle, sectionLabel).filter { it.isNotBlank() }

    private val load = MutableStateFlow<PersonLoad>(PersonLoad.Loading)

    // Declared before [uiState]: viewModelScope starts its upstream during construction.
    private val resourceCopy = object : PersonCopy {
        private val res get() = context.resources
        override fun credits(count: Int) = res.getQuantityString(R.plurals.cast_credit_count, count, count)
        override fun born(date: String, age: Int) = res.getString(R.string.cast_born_age, date, age)
        override fun bornDied(born: String, died: String, aged: Int) =
            res.getString(R.string.cast_born_died_aged, born, died, aged)
        override fun died(date: String) = res.getString(R.string.cast_died, date)
        override fun dateWithAge(date: String, age: Int) = res.getString(R.string.cast_fact_date_age, date, age)
        override fun dateWithAged(date: String, aged: Int) = res.getString(R.string.cast_fact_date_aged, date, aged)
        override fun asRole(roles: String) = res.getString(R.string.cast_role_as, roles)
        override fun episodes(count: Int) = res.getQuantityString(R.plurals.cast_episode_count_short, count, count)
        override fun type(series: Boolean) = res.getString(if (series) R.string.type_series else R.string.type_movie)
    }

    // The page rebuilds when Card shape changes (the cards carry their art); off the main thread,
    // since a long filmography is hundreds of cards.
    val uiState: StateFlow<CastDetailUiState> = combine(
        load,
        layoutPreferenceDataStore.modernLandscapePostersEnabled.distinctUntilChanged()
    ) { result, landscape ->
        when (result) {
            PersonLoad.Loading -> CastDetailUiState.Loading(personName)
            PersonLoad.NoKey -> CastDetailUiState.NeedsTmdbKey
            is PersonLoad.Failed -> CastDetailUiState.Error(result.message)
            is PersonLoad.Loaded -> CastDetailUiState.Success(buildPage(result.detail, landscape))
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CastDetailUiState.Loading(personName))

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    init {
        posterOptions.bind(viewModelScope)
        loadPersonDetail()
        observeWatchedStatus()
    }

    private fun observeWatchedStatus() {
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
    }

    // The load shows Loading itself.
    fun retry() = loadPersonDetail()

    // With no TMDB key the page waits for one (Spec 52 T1), then loads by itself.
    private fun loadPersonDetail() {
        viewModelScope.launch {
            tmdbApiKeys.awaitKey { load.value = PersonLoad.NoKey }
            load.value = PersonLoad.Loading
            load.value = try {
                val detail = tmdbMetadataService.fetchPersonDetail(
                    personId = personId,
                    language = tmdbSettingsDataStore.settings.first().language
                )
                if (detail != null) {
                    PersonLoad.Loaded(detail)
                } else {
                    PersonLoad.Failed(context.getString(R.string.cast_error_load_details_for, personName))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PersonLoad.Failed(e.message ?: context.getString(R.string.error_unknown))
            }
        }
    }

    private fun buildPage(detail: PersonDetail, landscape: Boolean): PersonPage = personPage(
        person = detail,
        department = department,
        landscape = landscape,
        today = LocalDate.now(),
        formatDate = ::formatShortDate,
        copy = resourceCopy
    )
}

/** The fetch's outcome; [CastDetailViewModel.uiState] turns it into a page with the current Card shape. */
private sealed interface PersonLoad {
    data object Loading : PersonLoad
    data object NoKey : PersonLoad
    data class Failed(val message: String) : PersonLoad
    data class Loaded(val detail: PersonDetail) : PersonLoad
}
