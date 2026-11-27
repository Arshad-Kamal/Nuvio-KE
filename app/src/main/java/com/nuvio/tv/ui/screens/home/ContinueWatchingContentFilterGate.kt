package com.nuvio.tv.ui.screens.home

import android.util.Log
import com.nuvio.tv.core.contentfilter.ActiveFilter
import com.nuvio.tv.core.contentfilter.TagPriority
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.core.contentfilter.TitleVerdict
import com.nuvio.tv.core.contentfilter.titleRef
import com.nuvio.tv.core.contentfilter.verdictOf
import com.nuvio.tv.core.contentfilter.waitsForTags
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Spec 117 P7: Continue Watching (main row and upcoming) as the active profile's content filters may see
 * it, applied after [ContinueWatchingRatingGate] (a title the cap removed is never judged) on the state
 * HomeViewModel exposes, so every CW publish path passes through it.
 *
 * The filter is read once, at creation, as the cap is; until it is read CW is hidden, and a read that
 * fails (a broken pack file) keeps it hidden. No filter: the state passes through as the same instance.
 * Otherwise each title's verdict is worked out off Main ([verdictDispatcher]) into [verdicts], again on
 * every lookup tick and whenever the CW titles (their names, genres) change; a title waiting for its tags is looked up at
 * [TagPriority.NOW]. The exposed state only reads the map: a title with no verdict yet, or undecided, is
 * hidden (117/D10); a hit is hidden, or kept and marked while previewing (Spec 122 V2/V3).
 */
internal class ContinueWatchingContentFilterGate(
    private val scope: CoroutineScope,
    readFilter: suspend () -> ActiveFilter?,
    private val lookup: TitleTagLookup,
    private val verdictDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private sealed interface FilterState {
        data object Loading : FilterState
        data class Ready(val active: ActiveFilter?) : FilterState
    }

    private val filterState = MutableStateFlow<FilterState>(FilterState.Loading)

    /** The CW titles of the latest state, as the matcher sees them. */
    private val subjects = MutableStateFlow<Set<MetaPreview>>(emptySet())

    /**
     * Verdicts by subject: a title whose name or genres changed since counts as not worked out yet,
     * never as its old verdict; absent = not worked out yet.
     */
    private val verdicts = MutableStateFlow<Map<MetaPreview, TitleVerdict>>(emptyMap())

    init {
        scope.launch {
            val active = try {
                readFilter()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fails closed: Loading hides CW for this view model's life.
                Log.w(TAG, "Content filter read failed: ${e::class.simpleName}")
                return@launch
            }
            filterState.value = FilterState.Ready(active)
        }
        scope.launch(verdictDispatcher) {
            combine(filterState, subjects, lookup.changes.onStart { emit(Unit) }) { state, titles, _ -> state to titles }
                .collect { (state, titles) ->
                    val active = (state as? FilterState.Ready)?.active ?: return@collect
                    val filter = active.filter ?: return@collect
                    val preview = active.preview
                    verdicts.value = titles.associateWith { subject ->
                        val verdict = lookup.verdictOf(filter, subject)
                        if (lookup.waitsForTags(filter, subject, preview, verdict)) {
                            subject.titleRef()?.let { lookup.request(it, TagPriority.NOW) }
                        }
                        verdict
                    }
                }
        }
    }

    /** [states] with CW filtered; shared on [scope] for its lifetime. */
    fun gate(states: StateFlow<HomeUiState>): StateFlow<HomeUiState> =
        combine(states, filterState, verdicts, ::visible)
            .stateIn(scope, SharingStarted.Eagerly, visible(states.value, filterState.value, verdicts.value))

    private fun visible(state: HomeUiState, filterState: FilterState, known: Map<MetaPreview, TitleVerdict>): HomeUiState {
        val active = when (filterState) {
            FilterState.Loading -> return state.withContinueWatching(emptyList(), emptyList())
            is FilterState.Ready -> filterState.active?.takeIf { it.filter != null } ?: return state
        }
        subjects.value = (state.continueWatchingItems + state.upcomingItems).mapTo(HashSet(), ::subjectOf)
        fun shown(item: ContinueWatchingItem): ContinueWatchingItem? = when (val verdict = known[subjectOf(item)]) {
            null, TitleVerdict.Undecided -> null
            TitleVerdict.Clean -> item.withFilterHits(emptyList())
            is TitleVerdict.Hit -> if (active.preview) item.withFilterHits(verdict.packIds) else null
        }
        return state.withContinueWatching(
            state.continueWatchingItems.mapNotNull(::shown),
            state.upcomingItems.mapNotNull(::shown)
        )
    }

    /** The same instance when nothing was dropped or re-marked, so an unchanged state is not re-emitted. */
    private fun HomeUiState.withContinueWatching(
        main: List<ContinueWatchingItem>,
        upcoming: List<ContinueWatchingItem>
    ): HomeUiState =
        if (main == continueWatchingItems && upcoming == upcomingItems) this
        else copy(continueWatchingItems = main, upcomingItems = upcoming)

    internal companion object {
        private const val TAG = "CwContentFilterGate"

        /** What the matcher sees of a CW title: its name and genres (an episode's description is not the title's). */
        fun subjectOf(item: ContinueWatchingItem): MetaPreview {
            val type = item.contentType()
            return MetaPreview(
                id = item.contentId(),
                type = ContentType.fromString(type),
                rawType = type,
                name = when (item) {
                    is ContinueWatchingItem.InProgress -> item.progress.name
                    is ContinueWatchingItem.NextUp -> item.info.name
                },
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = when (item) {
                    is ContinueWatchingItem.InProgress -> item.genres
                    is ContinueWatchingItem.NextUp -> item.info.genres
                }
            )
        }

        private fun ContinueWatchingItem.withFilterHits(hits: List<String>): ContinueWatchingItem = when {
            filterHits == hits -> this
            this is ContinueWatchingItem.InProgress -> copy(filterHits = hits)
            this is ContinueWatchingItem.NextUp -> copy(filterHits = hits)
            else -> this
        }
    }
}
