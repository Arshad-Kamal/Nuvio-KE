package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nuvio.tv.MainActivity
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgramBuilder @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    fun buildWatchNextProgram(progress: WatchProgress): WatchNextProgram {
        val isMovie = progress.contentType == "movie"
        val programType = if (isMovie) {
            TvContractCompat.WatchNextPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
        }

        val builder = WatchNextProgram.Builder()
            .setType(programType)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(progress.name)
            .setLastEngagementTimeUtcMillis(progress.lastWatched)
            .setInternalProviderId(watchNextId(progress.contentId, progress.season, progress.episode))
            .setIntentUri(buildPlayUri(progress))

        builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)

        val horizontalArt = progress.backdrop ?: progress.poster
        horizontalArt?.let {
            val uriWithCacheBuster = Uri.parse(it).buildUpon()
                .appendQueryParameter("v", "horizontal_fix")
                .build()
            builder.setPosterArtUri(uriWithCacheBuster)
        }

        if (progress.duration > 0) {
            val positionMs = if (progress.position > 0) {
                progress.position.toInt()
            } else {
                (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
            }
            builder.setLastPlaybackPositionMillis(positionMs)
            builder.setDurationMillis(progress.duration.toInt())
        } else if (progress.progressPercent != null && progress.progressPercent > 0f) {
            val syntheticDuration = 100_000
            val syntheticPosition = (progress.progressPercent / 100f * syntheticDuration).toInt()
            builder.setDurationMillis(syntheticDuration)
            builder.setLastPlaybackPositionMillis(syntheticPosition)
        }

        if (!isMovie) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build()
    }

    fun upsertWatchNextProgram(program: WatchNextProgram, internalId: String) {
        try {
            val existingId = findWatchNextByInternalId(internalId)
            if (existingId != null) {
                val uri = TvContractCompat.buildWatchNextProgramUri(existingId)
                context.contentResolver.update(uri, program.toContentValues(), null, null)
            } else {
                context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    program.toContentValues()
                )
            }
        } catch (e: Exception) {
            Log.w(TvRecommendationManager.TAG, "upsertWatchNextProgram failed", e)
        }
    }

    /** Deletes every Watch Next row whose internal provider id satisfies [matches]. */
    fun removeWatchNextRows(matches: (String) -> Boolean) {
        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val idIdx = it.getColumnIndex(
                        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                    )
                    if (idIdx >= 0) {
                        val providerId = it.getString(idIdx)
                        if (providerId != null && matches(providerId)) {
                            val pkIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                            if (pkIdx >= 0) {
                                val uri = TvContractCompat.buildWatchNextProgramUri(it.getLong(pkIdx))
                                context.contentResolver.delete(uri, null, null)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TvRecommendationManager.TAG, "removeWatchNextRows failed", e)
        }
    }

    private fun findWatchNextByInternalId(internalId: String): Long? {
        return try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null,
                null,
                null
            )
            var foundId: Long? = null
            cursor?.use {
                while (it.moveToNext()) {
                    val providerIdIdx = it.getColumnIndex(
                        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                    )
                    if (providerIdIdx >= 0) {
                        val currentProviderId = it.getString(providerIdIdx)
                        if (currentProviderId == internalId) {
                            val idIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                            if (idIdx >= 0) {
                                foundId = it.getLong(idIdx)
                                break
                            }
                        }
                    }
                }
            }
            foundId
        } catch (e: Exception) {
            Log.w(TvRecommendationManager.TAG, "findWatchNextByInternalId failed", e)
            null
        }
    }

    private fun buildPlayUri(progress: WatchProgress): Uri =
        Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", progress.contentId)
                putExtra("contentType", progress.contentType)
                putExtra("videoId", progress.videoId)
                putExtra("name", progress.name)
                putExtra("poster", progress.poster)
                putExtra("backdrop", progress.backdrop)
                putExtra("logo", progress.logo)
                progress.season?.let { putExtra("season", it) }
                progress.episode?.let { putExtra("episode", it) }
                progress.episodeTitle?.let { putExtra("episodeTitle", it) }
                putExtra("launchMode", "stream")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )
}

/** Launcher row id: one row per episode (`wn_<id>_s<S>e<E>`), or `wn_<id>` for a movie. */
internal fun watchNextId(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "wn_${contentId}_s${season}e$episode" else "wn_$contentId"

/**
 * Rows a removal must drop: with [season] and [episode], only that episode's row plus the
 * title's bare row (other episodes keep theirs); otherwise every row of [contentId].
 */
internal fun watchNextIdMatchesRemoval(
    providerId: String,
    contentId: String,
    season: Int?,
    episode: Int?
): Boolean =
    if (season != null && episode != null) {
        providerId == watchNextId(contentId, season, episode) ||
            providerId == watchNextId(contentId, null, null)
    } else {
        watchNextIdMatchesContentId(providerId, contentId)
    }

internal fun watchNextIdMatchesContentId(providerId: String?, contentId: String): Boolean {
    val baseId = "wn_$contentId"
    if (providerId == baseId) return true
    if (providerId?.startsWith("${baseId}_s") != true) return false

    val episodeSeparator = providerId.indexOf('e', startIndex = baseId.length + 2)
    return episodeSeparator > baseId.length + 2 &&
        episodeSeparator < providerId.lastIndex &&
        (baseId.length + 2 until episodeSeparator).all { providerId[it].isDigit() } &&
        (episodeSeparator + 1..providerId.lastIndex).all { providerId[it].isDigit() }
}
