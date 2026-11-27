package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.simkl.RelatedTitlesSource
import com.nuvio.tv.data.simkl.SimklRelated
import com.nuvio.tv.data.simkl.simklRedirectQuery
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long More like this waits for Simkl before falling back to TMDB's list (Spec 106 S4b). */
internal const val SIMKL_RELATED_TIMEOUT_MS = 10_000L

/**
 * The detail page's More like this from Simkl (Spec 106 S4b, F4), when it is the active profile's effective source
 * (chosen in Settings › Trackers, E6, and Simkl connected). Null = Simkl is not the source (the caller loads TMDB's list);
 * empty = Simkl had nothing, could not be reached in [timeoutMs], or knows no id of the title (the caller falls back to
 * TMDB's list). Anything else Simkl's side throws (not an IOException) propagates, as the TMDB path's settings read does.
 * Simkl's connection is read as it stands: right after app start, before the account store has answered, it reads as
 * signed out and TMDB's list shows (reopening the title picks Simkl).
 */
class DetailSimklRelated internal constructor(
    private val settings: ContinueWatchingSettingsDataStore,
    private val registry: TrackingProviderRegistry,
    private val related: SimklRelated,
    private val timeoutMs: Long
) {
    @Inject
    constructor(
        settings: ContinueWatchingSettingsDataStore,
        registry: TrackingProviderRegistry,
        related: SimklRelated
    ) : this(settings, registry, related, SIMKL_RELATED_TIMEOUT_MS)

    suspend fun load(meta: Meta): List<MetaPreview>? {
        val source = settings.relatedTitlesSource.first().effective(registry.connectedProviderIds())
        if (source != RelatedTitlesSource.SIMKL) return null
        // An addon's own id (none Simkl can look up) falls back to the title's IMDb id.
        val itemId = meta.id.takeIf { simklRedirectQuery(parseTrackingExternalIds(it), isMovie = false) != null }
            ?: meta.imdbId ?: meta.id
        val anime = settings.activeSimklAnimeSeasons.first()
        return withTimeoutOrNull(timeoutMs) { related.related(itemId, meta.apiType, anime) }.orEmpty()
    }
}
