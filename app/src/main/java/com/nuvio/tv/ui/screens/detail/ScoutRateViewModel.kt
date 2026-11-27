package com.nuvio.tv.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.ScoutPicksRepository
import com.nuvio.tv.core.scout.ScoutRating
import com.nuvio.tv.core.scout.scoutBaseId
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.getOrNullUnlessCancelled
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** The title the detail Rate control is about: the page's id and the meta's IMDb id (D6). */
data class RateTitle(val contentId: String, val imdbId: String?, val tv: Boolean, val name: String)

/**
 * The detail Rate control's state (Spec 100 S4, D3; mockup `s100-scout-lite` b1-b3): the active profile's
 * rating of the [bind]-ed title. Its own ViewModel so detail's MetaDetailsViewModel stays untouched.
 */
@HiltViewModel
class ScoutRateViewModel @Inject constructor(
    private val repository: ScoutPicksRepository,
    private val profileManager: ProfileManager,
    private val tmdbService: TmdbService,
    tmdbKeys: TmdbApiKeyProvider
) : ViewModel() {

    private val title = MutableStateFlow<RateTitle?>(null)

    // Saves run one at a time in the order chosen (Mutex is fair): a slow lookup never lets an older choice land last.
    private val saves = Mutex()

    /**
     * Whether detail shows Rate: only while Picked for you can use it, a TMDB key set and the profile's switch
     * On (Arshad, escalation 00c27c58: Spec 100 D5). Hidden until both are known.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val visible: StateFlow<Boolean> = combine(
        tmdbKeys.state.map { it?.hasKey == true },
        profileManager.activeProfileId.flatMapLatest { repository.enabled(it) }
    ) { hasKey, enabled -> hasKey && enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val rating: StateFlow<ScoutRating?> = combine(profileManager.activeProfileId, title) { profileId, title ->
        profileId to title
    }
        .flatMapLatest { (profileId, title) ->
            if (title == null) flowOf(null) else repository.rating(profileId, title.contentId, title.imdbId, title.tv)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun bind(contentId: String, imdbId: String?, tv: Boolean, name: String) {
        val imdb = imdbId?.trim()?.takeIf { it.startsWith("tt") }
        title.value = RateTitle(contentId, imdb, tv, name).takeIf { contentId.isNotBlank() }
    }

    /**
     * Saves [choice]; the current choice again clears the rating (b3). The title's TMDB id is resolved here,
     * once per rating (a tmdb: page id or a cached id costs nothing, else one TMDB find), so the daily build
     * never has to (D6, D4). A failed lookup saves nothing (D7): Rate stays as it was and OK can be tried again.
     * A clear needs no lookup: the rating shown here meets this page's own ids.
     */
    fun choose(choice: ScoutRating) {
        val target = title.value ?: return
        val next = choice.takeIf { it != rating.value }
        val profileId = profileManager.activeProfileId.value
        viewModelScope.launch {
            saves.withLock {
                // A tmdb: or numeric page id is its own TMDB id (no request); else the IMDb id, else the page id.
                val local = target.contentId.takeIf { scoutBaseId(it).let { base -> base.isNotEmpty() && base.all(Char::isDigit) } }
                val tmdbId = if (next == null) null else runCatching {
                    tmdbService.ensureTmdbId(local ?: target.imdbId ?: target.contentId, if (target.tv) "tv" else "movie")
                }.getOrNullUnlessCancelled()?.toIntOrNull()
                repository.setRating(profileId, target.contentId, target.tv, target.name, next, target.imdbId, tmdbId)
            }
        }
    }
}
