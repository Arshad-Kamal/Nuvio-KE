package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.toNextUpInfo
import com.nuvio.tv.domain.model.NextUpInfo
import java.time.Clock

/**
 * Pure Continue Watching rules for currently-airing / fully-watched series and
 * next-up release alerts.
 * Kept free of Android / ViewModel dependencies so unit tests can lock the
 * air-day regression (checkmark + new episode → series removed from CW) and the
 * release-alert rule.
 */

/**
 * What to do when a series is marked fully-watched (caught-up checkmark) but
 * next-up resolution still found a next episode.
 */
enum class FullyWatchedNextUpAction {
    /** Unaired next episode: keep on CW, leave the checkmark. */
    KEEP_WITH_BADGE,

    /**
     * Aired next episode: user is no longer caught up. Keep on CW and clear the
     * stale fully-watched badge. Pre-fix code dropped the item instead.
     */
    KEEP_AND_CLEAR_BADGE,
}

/**
 * Decision for a fully-watched series that already resolved a next-up item.
 *
 * Historical bug (`71e4fb6` + mid-season checkmarks from `a58809`):
 * `if (hasAired) drop` removed airing shows from CW the day a new episode aired.
 * Correct behavior: never drop; clear the badge when the next episode has aired.
 */
fun fullyWatchedNextUpAction(nextUpHasAired: Boolean): FullyWatchedNextUpAction {
    return if (nextUpHasAired) {
        FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE
    } else {
        FullyWatchedNextUpAction.KEEP_WITH_BADGE
    }
}

/**
 * Inject an aired unwatched episode into the badge episode set so a later badge
 * pass does not re-apply the fully-watched checkmark from a stale aired list.
 */
fun mergeAiredNextEpisodeIntoBadgeCache(
    existingAiredEpisodes: Set<Pair<Int, Int>>?,
    nextSeason: Int?,
    nextEpisode: Int?,
): Set<Pair<Int, Int>>? {
    if (existingAiredEpisodes == null) return null
    if (nextSeason == null || nextEpisode == null) return existingAiredEpisodes
    return existingAiredEpisodes + (nextSeason to nextEpisode)
}

/**
 * After injecting the new aired episode, the series is fully watched only if
 * every aired episode (including the new one) is in the watched set.
 */
fun isFullyWatchedAgainstAiredList(
    airedEpisodes: Set<Pair<Int, Int>>,
    watchedEpisodes: Set<Pair<Int, Int>>,
): Boolean {
    if (airedEpisodes.isEmpty()) return false
    return airedEpisodes.all { it in watchedEpisodes }
}

/** Episodes that aired longer ago than this never raise a release alert: the show was likely abandoned. */
private const val RELEASE_ALERT_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

/** Release-alert flags of one next-up episode and the sort timestamp they imply. */
internal data class NextUpReleaseState(
    val sortTimestamp: Long,
    val releaseTimestamp: Long?,
    val isReleaseAlert: Boolean,
    val isNewSeasonRelease: Boolean
)

/**
 * The one release-alert rule. An aired episode released after the user last watched the
 * series ([lastWatched]) and less than 60 days before [nowMs] is a release alert and sorts by
 * its release time; it is also a new-season release when [seedSeason] is known and differs
 * from [nextSeason]. Anything else sorts by [lastWatched].
 */
internal fun nextUpReleaseState(
    hasAired: Boolean,
    releaseTimestamp: Long?,
    lastWatched: Long,
    nextSeason: Int,
    seedSeason: Int?,
    nowMs: Long = System.currentTimeMillis()
): NextUpReleaseState {
    val isReleaseAlert = hasAired &&
        releaseTimestamp != null &&
        releaseTimestamp > lastWatched &&
        nowMs - releaseTimestamp < RELEASE_ALERT_WINDOW_MS
    return NextUpReleaseState(
        sortTimestamp = if (isReleaseAlert) releaseTimestamp else lastWatched,
        releaseTimestamp = releaseTimestamp,
        isReleaseAlert = isReleaseAlert,
        isNewSeasonRelease = isReleaseAlert && seedSeason != null && nextSeason != seedSeason
    )
}

/**
 * A cached next-up row with hasAired and the release flags recomputed from its persisted
 * release timestamp, so a row written before its episode aired still gets the right badge.
 * Used by the pipeline and by the cold-start restore.
 */
internal fun CachedNextUpItem.toFreshNextUpInfo(nowMs: Long = System.currentTimeMillis()): NextUpInfo {
    val freshHasAired = releaseTimestamp?.let { nowMs >= it } ?: hasAired
    val state = nextUpReleaseState(
        hasAired = freshHasAired,
        releaseTimestamp = releaseTimestamp,
        lastWatched = lastWatched,
        nextSeason = season,
        seedSeason = seedSeason,
        nowMs = nowMs
    )
    return toNextUpInfo(
        hasAired = freshHasAired,
        isReleaseAlert = state.isReleaseAlert,
        isNewSeasonRelease = state.isNewSeasonRelease,
        sortTimestamp = state.sortTimestamp
    )
}

/**
 * Re-derives hasAired from [NextUpInfo.released] (the stored flag when the date is unknown)
 * and, when it flipped, the release flags, so an enriched overlay cached while its episode was
 * unaired does not stay stuck in "upcoming" after the air date. Returns this when unchanged.
 */
internal fun NextUpInfo.withRefreshedAiring(clock: Clock = Clock.systemDefaultZone()): NextUpInfo {
    val freshHasAired = isEpisodeReleaseAired(released, clock) ?: hasAired
    if (freshHasAired == hasAired) return this
    val state = nextUpReleaseState(
        hasAired = freshHasAired,
        releaseTimestamp = parseEpisodeReleaseInstant(released, clock.zone)?.toEpochMilli(),
        lastWatched = lastWatched,
        nextSeason = season,
        seedSeason = seedSeason,
        nowMs = clock.millis()
    )
    return copy(
        hasAired = freshHasAired,
        airDateLabel = if (freshHasAired) null else airDateLabel,
        sortTimestamp = state.sortTimestamp,
        releaseTimestamp = state.releaseTimestamp,
        isReleaseAlert = state.isReleaseAlert,
        isNewSeasonRelease = state.isNewSeasonRelease
    )
}
