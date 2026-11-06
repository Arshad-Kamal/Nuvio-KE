package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Singleton
class TvRecommendationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val programBuilder: ProgramBuilder
) {

    // Serialises Watch Next writes (no interleaving of one write's provider calls with
    // another's) but does not order them: an upsert can still land after a removal that
    // was issued later. The player heals that by re-pruning on later completed saves.
    private val mutex = Mutex()
    private val syncedFingerprints = ConcurrentHashMap<String, ProgramFingerprint>()

    private data class ProgramFingerprint(
        val title: String?,
        val position: Long,
        val duration: Long,
        val poster: String?,
        val backdrop: String?,
        val season: Int?,
        val episode: Int?
    )

    companion object {
        const val TAG = "TvRecommendation"
    }

    suspend fun updateSingleWatchNextProgram(progress: WatchProgress) {
        if (!isTvDevice()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val id = watchNextId(progress.contentId, progress.season, progress.episode)
                    val newFingerprint = ProgramFingerprint(
                        title = progress.name,
                        position = progress.position,
                        duration = progress.duration,
                        poster = progress.poster,
                        backdrop = progress.backdrop,
                        season = progress.season,
                        episode = progress.episode
                    )
                    val oldFingerprint = syncedFingerprints[id]
                    if (oldFingerprint != null &&
                        oldFingerprint.title == newFingerprint.title &&
                        oldFingerprint.poster == newFingerprint.poster &&
                        oldFingerprint.backdrop == newFingerprint.backdrop &&
                        oldFingerprint.season == newFingerprint.season &&
                        oldFingerprint.episode == newFingerprint.episode &&
                        oldFingerprint.duration == newFingerprint.duration &&
                        abs(oldFingerprint.position - newFingerprint.position) < 2_000L
                    ) {
                        return@withContext
                    }

                    // Ids are per episode: drop the title's other rows (e.g. the previous
                    // episode) so the launcher shows one entry per title.
                    dropRows { it != id && watchNextIdMatchesContentId(it, progress.contentId) }

                    val program = programBuilder.buildWatchNextProgram(progress)
                    programBuilder.upsertWatchNextProgram(program, id)
                    syncedFingerprints[id] = newFingerprint
                } catch (e: Exception) {
                    Log.w(TAG, "updateSingleWatchNextProgram failed", e)
                }
            }
        }
    }

    suspend fun onProgressRemoved(contentId: String, season: Int? = null, episode: Int? = null) =
        onProgressRemoved(listOf(WatchNextRemoval(contentId, season, episode)))

    /**
     * Drops the launcher rows of removed or finished items: per item with season and episode,
     * that episode's row and the title's bare row only; otherwise every row of the title.
     * One provider scan for the whole batch. Provider failures are logged inside
     * [ProgramBuilder]; callers guard their own boundary.
     */
    suspend fun onProgressRemoved(removals: Collection<WatchNextRemoval>) {
        if (removals.isEmpty() || !isTvDevice()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                dropRows { id ->
                    removals.any { watchNextIdMatchesRemoval(id, it.contentId, it.season, it.episode) }
                }
            }
        }
    }

    /** Provider rows and their fingerprints go by one predicate, so the two cannot drift. */
    private fun dropRows(matches: (String) -> Boolean) {
        programBuilder.removeWatchNextRows(matches)
        syncedFingerprints.keys.removeAll(matches)
    }

    private fun isTvDevice(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** One launcher-row removal; season and episode both set = episode scope, else whole title. */
data class WatchNextRemoval(val contentId: String, val season: Int? = null, val episode: Int? = null)
