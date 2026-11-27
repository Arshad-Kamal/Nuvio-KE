package com.nuvio.tv.core.scout

import android.util.Log
import com.nuvio.tv.core.contentfilter.ActiveContentFilter
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbImageSizes
import com.nuvio.tv.core.tmdb.tmdbImageUrl
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.CatalogContentFilter
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One card of the Picked for you row: the title as a catalog item and the watched title behind it (D2). */
data class ScoutRowItem(val meta: MetaPreview, val reason: String)

/**
 * The Picked for you row of the active profile (Spec 100 S3): its stored picks without titles watched since,
 * hidden with no TMDB key or the switch off, empty on a profile with a rating limit (TMDB picks carry no age
 * rating: Spec 64 D5, as TMDB list rows), then through the content filter (Spec 117) like any TMDB row.
 */
@Singleton
class ScoutHomeRow @Inject constructor(
    private val repository: ScoutPicksRepository,
    private val watchProgress: WatchProgressRepository,
    private val profileManager: ProfileManager,
    private val tmdbKeys: TmdbApiKeyProvider,
    private val tmdbSettings: TmdbSettingsDataStore,
    private val ratingCap: ActiveRatingCap,
    private val contentFilter: CatalogContentFilter,
    private val filterSettings: ActiveContentFilter,
    private val tagLookup: TitleTagLookup
) {
    // A build outlives Home: a cancelled one would still use up the day's attempt (ScoutPicksRepository).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Bumped by [refresh], so the cap and the filters are read again when Home comes back.
    private val recheck = MutableStateFlow(0)

    /**
     * Called when Home comes on top: re-reads the cap and filters, and builds the active profile's picks when
     * one is due. Never on a capped profile (no TMDB request, D5).
     */
    fun refresh() {
        recheck.update { it + 1 }
        scope.launch {
            try {
                profileManager.activeProfileReady.first { it }
                val profileId = profileManager.activeProfileId.value
                val watched = watchProgress.watchedItems.first()
                val progress = watchProgress.allProgress.first()
                // The stored settings, not the placeholder a cold StateFlow starts with (a wrong language
                // would cost a second build).
                val language = tmdbSettings.storedSettings.first().language
                if (ratingCap.current() != null) return@launch
                // A switch while reading: this history (or that cap) may be the other profile's.
                if (profileManager.activeProfileId.value != profileId) return@launch
                repository.refreshIfDue(profileId, watched, progress, language)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Picks not built: ${error.javaClass.simpleName}")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun items(): Flow<List<ScoutRowItem>> = profileManager.activeProfileId
        .flatMapLatest { profileId ->
            combine(
                repository.cached(profileId),
                watchProgress.watchedItems,
                watchProgress.allProgress,
                tmdbKeys.state.map { it?.hasKey == true }
            ) { picks, watched, progress, hasKey ->
                if (hasKey) repository.visible(picks, watched, progress) else emptyList()
            }
        }
        .distinctUntilChanged()
        // Re-judged like Home's catalog rows: on a filter change and when stored tags arrive (a pick
        // undecided after the gate's wait shows once it is decided), plus [refresh] for the cap.
        .combine(gateTriggers()) { picks, _ -> picks }
        .mapLatest(::shown)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    private fun gateTriggers(): Flow<Unit> =
        merge(recheck.map { }, filterSettings.settingsChanges(), tagLookup.changes).onStart { emit(Unit) }

    private suspend fun shown(picks: List<ScoutPick>): List<ScoutRowItem> {
        if (picks.isEmpty()) return emptyList()
        return try {
            if (ratingCap.current() != null) return emptyList()
            val metas = picks.map { it.toMetaPreview() }
            val kept = contentFilter.open()?.applyAll(listOf(metas))?.single() ?: metas
            val reasons = picks.associate { (it.tv to "tmdb:${it.tmdbId}") to it.reason }
            kept.mapNotNull { meta ->
                reasons[(meta.type == ContentType.SERIES) to meta.id]?.let { ScoutRowItem(meta, it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A broken pack file (the only throw of the filter) hides the row, never shows it unfiltered.
            Log.w(TAG, "Picks hidden: ${error.javaClass.simpleName}")
            emptyList()
        }
    }

    private companion object {
        const val TAG = "ScoutHomeRow"
    }
}

/** A pick as a TMDB catalog title, built as TmdbCollectionSourceResolver builds its list rows. */
internal fun ScoutPick.toMetaPreview(): MetaPreview {
    val type = if (tv) ContentType.SERIES else ContentType.MOVIE
    return MetaPreview(
        id = "tmdb:$tmdbId",
        type = type,
        rawType = if (tv) "series" else "movie",
        name = name,
        poster = tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780"),
        posterShape = PosterShape.POSTER,
        background = tmdbImageUrl(backdropPath, TmdbImageSizes.backdrop),
        logo = null,
        description = overview,
        releaseInfo = year,
        imdbRating = null,
        tmdbRating = rating?.toFloat(),
        genres = emptyList()
    )
}
