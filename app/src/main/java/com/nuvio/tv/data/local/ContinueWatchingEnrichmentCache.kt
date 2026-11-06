package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null
)

/**
 * The one mapping from a cached next-up row to the domain model (B25: four
 * hand-written copies had drifted, three dropped contentLanguage). Callers that
 * recalculate the release badge pass the fresh flags and sort timestamp.
 */
fun CachedNextUpItem.toNextUpInfo(
    hasAired: Boolean = this.hasAired,
    isReleaseAlert: Boolean = this.isReleaseAlert,
    isNewSeasonRelease: Boolean = this.isNewSeasonRelease,
    sortTimestamp: Long = this.sortTimestamp
): NextUpInfo = NextUpInfo(
    contentId = contentId,
    contentType = contentType,
    name = name,
    poster = poster,
    backdrop = backdrop,
    logo = logo,
    videoId = videoId,
    season = season,
    episode = episode,
    episodeTitle = episodeTitle,
    episodeDescription = episodeDescription,
    thumbnail = thumbnail,
    released = released,
    hasAired = hasAired,
    airDateLabel = airDateLabel,
    lastWatched = lastWatched,
    imdbRating = imdbRating,
    genres = genres,
    releaseInfo = releaseInfo,
    sortTimestamp = sortTimestamp,
    releaseTimestamp = releaseTimestamp,
    isReleaseAlert = isReleaseAlert,
    isNewSeasonRelease = isNewSeasonRelease,
    seedSeason = seedSeason,
    seedEpisode = seedEpisode,
    contentLanguage = contentLanguage
)

@Serializable
data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null
)

/**
 * The one mapping from a cached in-progress row back to the domain model (cold-start
 * restore). Its inverse is toCachedInProgressSnapshot in ContinueWatchingNextUpReconciliation.kt.
 */
fun CachedInProgressItem.toInProgressItem(): ContinueWatchingItem.InProgress =
    ContinueWatchingItem.InProgress(
        progress = WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = name,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            position = position,
            duration = duration,
            lastWatched = lastWatched,
            progressPercent = progressPercent
        ),
        episodeThumbnail = episodeThumbnail,
        episodeDescription = episodeDescription,
        episodeImdbRating = episodeImdbRating,
        genres = genres,
        releaseInfo = releaseInfo,
        contentLanguage = contentLanguage
    )

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L

        /** Directory under filesDir; the one home for the per-profile cache file names below. */
        internal const val CACHE_DIR_NAME = "cw_enrichment"

        internal fun nextUpCacheFile(filesDir: File, profileId: Int): File =
            File(File(filesDir, CACHE_DIR_NAME), "nextup_${profileId}.json")

        internal fun inProgressCacheFile(filesDir: File, profileId: Int): File =
            File(File(filesDir, CACHE_DIR_NAME), "inprogress_${profileId}.json")
    }

    private val nextUpListSerializer = ListSerializer(CachedNextUpItem.serializer())
    private val inProgressListSerializer = ListSerializer(CachedInProgressItem.serializer())
    private val mutex = Mutex()
    @Volatile private var lastNextUpWriteMs = 0L
    @Volatile private var lastInProgressWriteMs = 0L
    @Volatile private var lastNextUpHash = 0
    @Volatile private var lastInProgressHash = 0

    /** Incremented when cache is cleared; observers can collect to trigger refresh. */
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared

    // --- Next Up snapshot cache ---

    private fun activeProfileId(): Int = profileManager.activeProfileId.value

    private fun nextUpFile(profileId: Int): File =
        nextUpCacheFile(context.filesDir, profileId)
            .also { it.parentFile?.mkdirs() }

    /** [profileId] defaults to the active profile; sync eviction passes the profile it read (L9-09). */
    suspend fun getNextUpSnapshot(
        profileId: Int = activeProfileId()
    ): List<CachedNextUpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = nextUpFile(profileId)
                if (!file.exists()) return@withContext emptyList()
                localStoreJson.decodeFromString(nextUpListSerializer, file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read next-up cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveNextUpSnapshot(
        items: List<CachedNextUpItem>,
        force: Boolean = false,
        profileId: Int = activeProfileId()
    ) = withContext(Dispatchers.IO) {
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastNextUpHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastNextUpWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = nextUpFile(profileId)
                atomicWrite(file, localStoreJson.encodeToString(nextUpListSerializer, items))
                // Throttle state tracks the active profile's file only.
                if (profileId == activeProfileId()) {
                    lastNextUpWriteMs = System.currentTimeMillis()
                    lastNextUpHash = contentHash
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write next-up cache: ${e.message}")
            }
        }
    }

    // --- In-progress snapshot cache ---

    private fun inProgressFile(profileId: Int): File =
        inProgressCacheFile(context.filesDir, profileId)
            .also { it.parentFile?.mkdirs() }

    /** [profileId] defaults to the active profile; sync eviction passes the profile it read (L9-09). */
    suspend fun getInProgressSnapshot(
        profileId: Int = activeProfileId()
    ): List<CachedInProgressItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = inProgressFile(profileId)
                if (!file.exists()) return@withContext emptyList()
                localStoreJson.decodeFromString(inProgressListSerializer, file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read in-progress cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveInProgressSnapshot(
        items: List<CachedInProgressItem>,
        force: Boolean = false,
        profileId: Int = activeProfileId()
    ) = withContext(Dispatchers.IO) {
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastInProgressHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastInProgressWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = inProgressFile(profileId)
                atomicWrite(file, localStoreJson.encodeToString(inProgressListSerializer, items))
                if (profileId == activeProfileId()) {
                    lastInProgressWriteMs = System.currentTimeMillis()
                    lastInProgressHash = contentHash
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write in-progress cache: ${e.message}")
            }
        }
    }

    /**
     * Deletes all CW enrichment cache files for the active profile.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val profileId = activeProfileId()
                nextUpFile(profileId).delete()
                inProgressFile(profileId).delete()
                lastNextUpHash = 0
                lastInProgressHash = 0
                Log.d(TAG, "Cleared CW enrichment cache for profile $profileId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear CW enrichment cache: ${e.message}")
            }
        }
        _cacheCleared.value++
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}
