package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.domain.model.Video
import java.time.Clock

object PlayerNextEpisodeRules {
    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int?,
        currentEpisode: Int
    ): Video? {
        // Absolute-numbered content (e.g. some anime via Kitsu) carries no season; order by episode
        // number alone and advance to the next one.
        if (currentSeason == null) {
            val sorted = videos
                .filter { it.episode != null }
                .sortedWith(compareBy<Video>({ it.season ?: 0 }, { it.episode ?: 0 }))
            val index = sorted.indexOfFirst { it.episode == currentEpisode }
            return if (index < 0) null else sorted.getOrNull(index + 1)
        }

        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null

        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    /**
     * The episode before the current one (Spec 45 Previous): the same order as [resolveNextEpisode],
     * over regular seasons only (Q4: Season 0 specials are never a previous episode, and a special
     * has none), so it crosses back over a season boundary (S2 E1 -> S1's last). Seasoned episodes
     * only: like Next, the player offers nothing for a season-less (absolute-numbered) episode.
     */
    fun resolvePreviousEpisode(
        videos: List<Video>,
        currentSeason: Int,
        currentEpisode: Int
    ): Video? {
        if (currentSeason == 0) return null
        val sorted = videos
            .filter { it.season != null && it.season != 0 && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })
        val index = sorted.indexOfFirst { it.season == currentSeason && it.episode == currentEpisode }
        return if (index <= 0) null else sorted[index - 1]
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        if (durationMs <= 0L) return false
        val outroSegments = skipIntervals.filter { it.type in OUTRO_SEGMENT_TYPES }

        // Fallback to the settings threshold when no outro data exists.
        if (outroSegments.isEmpty()) {
            return reachedUserThreshold(
                positionMs, durationMs, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
            )
        }

        val latestOutroEndMs = (outroSegments.maxOf { it.endTime } * 1_000.0).toLong()
        val postOutroGapMs = durationMs - latestOutroEndMs
        // The user's configured threshold as milliseconds from end.
        val userThresholdMs = when (thresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE ->
                ((1.0 - clampedThresholdPercent(thresholdPercent) / 100.0) * durationMs).toLong()
            NextEpisodeThresholdMode.MINUTES_BEFORE_END ->
                minutesBeforeEndThresholdMs(thresholdMinutesBeforeEnd)
        }

        return if (postOutroGapMs > userThresholdMs) {
            reachedUserThreshold(
                positionMs, durationMs, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
            )
        } else {
            // Outro ends close to the file end — fire at earliest outro start.
            positionMs / 1_000.0 >= outroSegments.minOf { it.startTime }
        }
    }

    /** Whether [positionMs] has reached the user's settings threshold; [durationMs] > 0. */
    private fun reachedUserThreshold(
        positionMs: Long,
        durationMs: Long,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean = when (thresholdMode) {
        NextEpisodeThresholdMode.PERCENTAGE ->
            (positionMs.toDouble() / durationMs.toDouble()) >=
                (clampedThresholdPercent(thresholdPercent) / 100.0)
        NextEpisodeThresholdMode.MINUTES_BEFORE_END ->
            durationMs - positionMs <= minutesBeforeEndThresholdMs(thresholdMinutesBeforeEnd)
    }

    private fun clampedThresholdPercent(thresholdPercent: Float): Float =
        thresholdPercent.coerceIn(97f, 100f)

    private fun minutesBeforeEndThresholdMs(thresholdMinutesBeforeEnd: Float): Long =
        (thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f) * 60_000f).toLong()

    fun hasEpisodeAired(raw: String?, clock: Clock = Clock.systemDefaultZone()): Boolean {
        return isEpisodeReleaseAired(raw, clock) ?: true
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")
}
