package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.progressKey
import com.nuvio.tv.domain.model.watchProgressKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "WatchProgressPrefs"

        // Companion-level on purpose: hotProgressSnapshots uses stateIn(Eagerly) on a
        // Dispatchers.IO scope, so DataStore work (writeBucketLocked → encodeProgressMap)
        // can start on an IO thread before late instance initializers have run. An
        // instance val here raced that construction and NPE'd under suite load
        // (caught 2026-09-03, WatchProgressPreferencesStorageTest in full-suite runs).
        private val progressMapSerializer =
            MapSerializer(String.serializer(), WatchProgress.serializer())
    }

    // profileId: the hot StateFlow outlives a profile switch, so every snapshot names the
    // profile it was read from and readers drop foreign ones (L9-39). In-memory only.
    private data class ProgressSnapshot(
        val profileId: Int,
        val recentJson: String,
        val archiveJson: String,
        val recent: Map<String, WatchProgress>,
        val archive: Map<String, WatchProgress>
    )

    /** One immutable holder per derived-list cache, so a reader never sees a torn mix (L9-75). */
    private data class ProgressListCache(
        val profileId: Int,
        val recentJson: String,
        val archiveJson: String,
        val result: List<WatchProgress>
    ) {
        fun matches(profileId: Int, snapshot: ProgressSnapshot): Boolean =
            this.profileId == profileId &&
                recentJson == snapshot.recentJson &&
                archiveJson == snapshot.archiveJson
    }

    private data class ProgressMapCache(
        val profileId: Int,
        val json: String,
        val entries: Map<String, WatchProgress>
    )

    private fun metadataStore(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, WATCH_PROGRESS_METADATA_FEATURE)

    private fun recentStore(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, WATCH_PROGRESS_RECENT_FEATURE)

    private fun archiveStore(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, WATCH_PROGRESS_ARCHIVE_FEATURE)

    private val lastSuccessfulPushMsKey = longPreferencesKey("last_successful_push_ms")
    private val deltaCursorKey = longPreferencesKey("watch_progress_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("watch_progress_delta_initialized")
    private val storageMutex = Mutex()
    private val initializedProfiles = mutableSetOf<Int>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    @Volatile private var recentMapCache: ProgressMapCache? = null
    @Volatile private var archiveMapCache: ProgressMapCache? = null

    /** Hot snapshot flow — reads DataStore once, then stays in memory. Updates automatically. */
    private val hotProgressSnapshots: kotlinx.coroutines.flow.StateFlow<ProgressSnapshot?> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            progressSnapshotsCold(pid)
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /**
     * Returns hot in-memory snapshot for active profile (instant read after first load).
     */
    private fun progressSnapshots(profileId: Int): Flow<ProgressSnapshot> {
        // For active profile, use hot flow (in-memory, no disk I/O after first read).
        // Right after a switch it still holds the previous profile's snapshot: skip it.
        return if (profileId == profileManager.activeProfileId.value) {
            hotProgressSnapshots.mapNotNull { snapshot -> snapshot?.takeIf { it.profileId == profileId } }
        } else {
            progressSnapshotsCold(profileId)
        }
    }

    /** Persisted timestamp of the last successful push to remote. */
    suspend fun getLastSuccessfulPushMs(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = metadataStore(profileId).data.first()
        return prefs[lastSuccessfulPushMsKey] ?: 0L
    }

    suspend fun setLastSuccessfulPushMs(timestampMs: Long, profileId: Int = profileManager.activeProfileId.value) {
        metadataStore(profileId).edit { prefs ->
            prefs[lastSuccessfulPushMsKey] = timestampMs
        }
    }

    suspend fun getDeltaCursor(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = metadataStore(profileId).data.first()
        return prefs[deltaCursorKey] ?: 0L
    }

    suspend fun isDeltaInitialized(profileId: Int = profileManager.activeProfileId.value): Boolean {
        val prefs = metadataStore(profileId).data.first()
        return prefs[deltaInitializedKey] ?: false
    }

    suspend fun setDeltaState(cursor: Long, initialized: Boolean = true, profileId: Int = profileManager.activeProfileId.value) {
        metadataStore(profileId).edit { prefs ->
            prefs[deltaCursorKey] = cursor.coerceAtLeast(0L)
            prefs[deltaInitializedKey] = initialized
        }
        Log.d(TAG, "setDeltaState: profile=$profileId cursor=${cursor.coerceAtLeast(0L)} initialized=$initialized")
    }

    /**
     * Get all watch progress items, sorted by last watched (most recent first)
     * For series, only returns the series-level entry (not individual episode entries)
     * to avoid duplicates in continue watching.
     *
     * JSON parsing, grouping, and sorting are performed off the main thread.
     * Results are cached — re-parsing only happens when the raw JSON actually changes.
     */
    @Volatile private var allProgressCache: ProgressListCache? = null

    val allProgress: Flow<List<WatchProgress>> = profileManager.activeProfileId.flatMapLatest { pid ->
        progressSnapshots(pid).map { snapshot ->
            // Fast path: if JSON hasn't changed, return cached result immediately.
            allProgressCache?.takeIf { it.matches(pid, snapshot) }?.let { return@map it.result }

            val allItems = mergeWatchProgressBuckets(snapshot.recent, snapshot.archive)

            // Group all entries by contentId and pick the most recently watched.
            // When lastWatched is equal (e.g. batch mark-as-watched), prefer the highest season/episode.
            val latestByContent = allItems.values
                .groupBy { it.contentId }
                .mapValues { (_, items) ->
                    items.maxWithOrNull(
                        compareBy<WatchProgress> { it.lastWatched }
                            .thenBy { it.season ?: 0 }
                            .thenBy { it.episode ?: 0 }
                    )
                }
                .values
                .filterNotNull()

            val result = latestByContent.sortedByDescending { it.lastWatched }

            // Cache for next emission
            allProgressCache = ProgressListCache(pid, snapshot.recentJson, snapshot.archiveJson, result)
            result
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Get watch progress for a specific content item
     */
    fun getProgress(contentId: String, profileId: Int = profileManager.activeProfileId.value): Flow<WatchProgress?> {
        return progressSnapshots(profileId).map { snapshot ->
            val map = mergeWatchProgressBuckets(snapshot.recent, snapshot.archive)
            // Try direct key first (movies), then find latest episode entry (series).
            map[contentId] ?: map.values
                .filter { it.contentId == contentId }
                .maxByOrNull { it.lastWatched }
        }
    }

    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int, profileId: Int = profileManager.activeProfileId.value): Flow<WatchProgress?> {
        return progressSnapshots(profileId).map { snapshot ->
            val key = watchProgressKey(contentId, season, episode)
            snapshot.recent[key] ?: snapshot.archive[key]
        }
    }

    /**
     * Get all episode progress for a series
     */
    fun getAllEpisodeProgress(contentId: String, profileId: Int = profileManager.activeProfileId.value): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return progressSnapshots(profileId).map { snapshot ->
            val map = mergeWatchProgressBuckets(snapshot.recent, snapshot.archive)
            map.values
                .filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associateBy { (it.season!! to it.episode!!) }
        }
    }

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(
        progress: WatchProgress,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        storageMutex.withLock {
            ensureStorageLocked(profileId)
            val recent = readBucket(recentStore(profileId), profileId, true)
            val key = progress.progressKey
            if (key in recent) {
                recentStore(profileId).edit { preferences ->
                    val map = parseProgressMap(preferences[watchProgressEntriesKey] ?: "{}").toMutableMap()
                    upsertProgressEntries(map, listOf(progress))
                    preferences[watchProgressEntriesKey] = encodeProgressMap(map)
                }
            } else {
                val archive = readBucket(archiveStore(profileId), profileId, false)
                val entries = mergeWatchProgressBuckets(recent, archive)
                upsertProgressEntries(entries, listOf(progress))
                writeBucketsLocked(
                    profileId = profileId,
                    current = WatchProgressBuckets(recent, archive),
                    updated = splitWatchProgressEntries(entries)
                )
            }
        }
    }

    suspend fun saveProgressBatch(
        progressList: List<WatchProgress>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (progressList.isEmpty()) return
        storageMutex.withLock {
            ensureStorageLocked(profileId)
            val current = readBucketsLocked(profileId)
            val entries = mergeWatchProgressBuckets(current.recent, current.archive)
            upsertProgressEntries(entries, progressList)
            writeBucketsLocked(profileId, current, splitWatchProgressEntries(entries))
        }
    }

    /**
     * Remove watch progress for a specific item
     */
    suspend fun removeProgress(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        storageMutex.withLock {
            ensureStorageLocked(profileId)
            val current = readBucketsLocked(profileId)
            val recent = current.recent.toMutableMap()
            val archive = current.archive.toMutableMap()
            val beforeSize = recent.size + archive.size
            Log.d(
                TAG,
                "removeProgress start contentId=$contentId season=$season episode=$episode entriesBefore=$beforeSize"
            )

            if (season != null && episode != null) {
                // Remove specific episode progress + the series-level entry
                // so the item disappears from continue watching
                val key = watchProgressKey(contentId, season, episode)
                recent.remove(key)
                recent.remove(contentId)
                archive.remove(key)
                archive.remove(contentId)
                Log.d(TAG, "removeProgress episodeKey=$key existsAfter=${key in recent || key in archive}")
            } else {
                // Remove all progress for this content
                val keysToRemove = (recent.keys + archive.keys).filter { key ->
                    key == contentId || key.startsWith("${contentId}_s")
                }.distinct()
                Log.d(TAG, "removeProgress removingKeys=${keysToRemove.joinToString()}")
                keysToRemove.forEach { key ->
                    recent.remove(key)
                    archive.remove(key)
                }
            }

            val updated = WatchProgressBuckets(recent, archive)
            Log.d(TAG, "removeProgress complete contentId=$contentId entriesAfter=${recent.size + archive.size}")
            writeBucketsLocked(profileId, current, updated)
        }
    }

    /**
     * Remove watch progress for multiple episodes in a single DataStore transaction.
     */
    suspend fun removeProgressBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (episodes.isEmpty()) return
        storageMutex.withLock {
            ensureStorageLocked(profileId)
            val current = readBucketsLocked(profileId)
            val recent = current.recent.toMutableMap()
            val archive = current.archive.toMutableMap()
            for ((season, episode) in episodes) {
                val key = watchProgressKey(contentId, season, episode)
                recent.remove(key)
                archive.remove(key)
            }
            recent.remove(contentId)
            archive.remove(contentId)
            Log.d(TAG, "removeProgressBatch contentId=$contentId removed=${episodes.size} episodes entriesAfter=${recent.size + archive.size}")
            writeBucketsLocked(profileId, current, WatchProgressBuckets(recent, archive))
        }
    }

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(
        progress: WatchProgress,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        // If the incoming duration is a dummy sentinel (≤ 1ms), check for an
        // existing local entry with a real duration from prior playback.
        // This creates a proper completed entry that syncs correctly cross-device.
        val effectiveDuration = if (progress.duration <= 1L) {
            val key = progress.progressKey
            val existing = getAllRawEntries(profileId)[key]
            existing?.duration?.takeIf { it > 1L } ?: progress.duration
        } else {
            progress.duration
        }

        val completedProgress = progress.copy(
            position = effectiveDuration,
            duration = effectiveDuration,
            lastWatched = System.currentTimeMillis()
        )
        saveProgress(completedProgress, profileId = profileId)
    }

    /**
     * Mark multiple items as completed in a single DataStore transaction.
     */
    suspend fun markAsCompletedBatch(
        progressList: List<WatchProgress>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (progressList.isEmpty()) return
        val rawEntries = getAllRawEntries(profileId)
        val now = System.currentTimeMillis()
        val completed = progressList.map { progress ->
            val effectiveDuration = if (progress.duration <= 1L) {
                val key = progress.progressKey
                rawEntries[key]?.duration?.takeIf { it > 1L } ?: progress.duration
            } else {
                progress.duration
            }
            progress.copy(
                position = effectiveDuration,
                duration = effectiveDuration,
                lastWatched = now
            )
        }
        saveProgressBatch(completed, profileId = profileId)
    }

    /**
     * Fills display artwork/name/duration hydrated for [snapshot] into the entry as it
     * is stored NOW (re-read under the storage mutex), so a position/lastWatched saved
     * after the snapshot was taken is never overwritten. Fields already present on the
     * current entry win; a vanished entry is not resurrected.
     *
     * @return true when an entry was updated.
     */
    suspend fun mergeHydratedArtwork(
        snapshot: WatchProgress,
        poster: String?,
        backdrop: String?,
        logo: String?,
        name: String?,
        durationMs: Long,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean = storageMutex.withLock {
        ensureStorageLocked(profileId)
        val key = snapshot.progressKey
        val recent = readBucket(recentStore(profileId), profileId, true)
        val inRecent = key in recent
        val bucket = if (inRecent) recent else readBucket(archiveStore(profileId), profileId, false)
        val current = bucket[key] ?: return@withLock false
        val merged = current.copy(
            poster = current.poster ?: poster,
            backdrop = current.backdrop ?: backdrop,
            logo = current.logo ?: logo,
            name = current.name.takeIf { it.isNotBlank() && it != current.contentId }
                ?: name ?: current.name,
            duration = current.duration.takeIf { it > 0 } ?: durationMs.takeIf { it > 0 } ?: current.duration
        )
        if (merged == current) return@withLock false
        // Same bucket, lastWatched unchanged: no recent/archive rebalancing needed.
        writeBucketLocked(
            if (inRecent) recentStore(profileId) else archiveStore(profileId),
            bucket + (key to merged)
        )
        true
    }

    /**
     * Writes the display metadata Continue Watching enriched for [enriched] (name, poster,
     * backdrop, logo, episodeTitle; the [withDisplayMetadata] precedence shared with
     * [saveProgress]) onto each entry as it is stored NOW, re-read under the storage mutex.
     * Everything else, position/duration/lastWatched/progressPercent included, stays as
     * stored, so a newer player save is never regressed; a vanished entry is not resurrected.
     *
     * @return the number of entries updated.
     */
    suspend fun mergeContinueWatchingMetadata(
        enriched: List<WatchProgress>,
        profileId: Int = profileManager.activeProfileId.value
    ): Int {
        if (enriched.isEmpty()) return 0
        return storageMutex.withLock {
            ensureStorageLocked(profileId)
            val current = readBucketsLocked(profileId)
            val recent = current.recent.toMutableMap()
            val archive = current.archive.toMutableMap()
            var updatedCount = 0
            enriched.forEach { progress ->
                val key = progress.progressKey
                val bucket = if (key in recent) recent else archive
                val stored = bucket[key] ?: return@forEach
                val merged = stored.withDisplayMetadata(preferred = progress, fallback = stored)
                if (merged != stored) {
                    bucket[key] = merged
                    updatedCount++
                }
            }
            // Same buckets, lastWatched unchanged: no recent/archive rebalancing needed.
            if (updatedCount > 0) {
                writeBucketsLocked(profileId, current, WatchProgressBuckets(recent, archive))
            }
            updatedCount
        }
    }

    /**
     * Returns the raw key→WatchProgress map from DataStore (for sync push).
     *
     * @param profileId Explicit profile to read from. Prevents race conditions
     *   when the active profile changes between scheduling and execution of a sync.
     */
    suspend fun getAllRawEntries(profileId: Int = profileManager.activeProfileId.value): Map<String, WatchProgress> {
        return storageMutex.withLock {
            ensureStorageLocked(profileId)
            val buckets = readBucketsLocked(profileId)
            mergeWatchProgressBuckets(buckets.recent, buckets.archive)
        }
    }

    /**
     * Merges remote entries into local storage. Newer lastWatched wins per key.
     *
     * @param profileId Explicit profile to write to. Prevents race conditions
     *   when the active profile changes between pull and merge operations.
     */
    suspend fun mergeRemoteEntries(
        remoteEntries: Map<String, WatchProgress>,
        lastSuccessfulPushMs: Long = 0L,
        profileId: Int = profileManager.activeProfileId.value,
        removeMissingRemoteEntries: Boolean = true
    ): Boolean {
        var preservedLocalItems = false
        Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${remoteEntries.size} remote entries, lastPushMs=$lastSuccessfulPushMs, profile=$profileId, removeMissing=$removeMissingRemoteEntries")
        storageMutex.withLock {
            ensureStorageLocked(profileId)
            val current = readBucketsLocked(profileId)
            val local = mergeWatchProgressBuckets(current.recent, current.archive)
            Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${local.size} existing local entries")

            // Remove local entries that no longer exist on remote - but protect
            // entries created after the last successful push (they haven't reached
            // remote yet, so their absence doesn't mean deletion on another device).
            if (removeMissingRemoteEntries && remoteEntries.isNotEmpty()) {
                val removedKeys = local.keys - remoteEntries.keys
                removedKeys.forEach { key ->
                    val localEntry = local[key]
                    if (localEntry != null && localEntry.lastWatched > lastSuccessfulPushMs) {
                        Log.d("WatchProgressPrefs", "  preserved key=$key (lastWatched=${localEntry.lastWatched} > lastPush=$lastSuccessfulPushMs)")
                        preservedLocalItems = true
                    } else {
                        local.remove(key)
                        Log.d("WatchProgressPrefs", "  removed key=$key (not in remote)")
                    }
                }
            }

            for ((key, remote) in remoteEntries) {
                val existing = local[key]
                if (existing == null || remote.lastWatched > existing.lastWatched) {
                    local[key] = mergeDisplayMetadata(remote, existing)
                    Log.d("WatchProgressPrefs", "  merged key=$key (existing=${existing != null})")
                } else if (existing.lastWatched > remote.lastWatched && existing.lastWatched > lastSuccessfulPushMs) {
                    Log.d("WatchProgressPrefs", "  skipped key=$key (local is newer)")
                    preservedLocalItems = true
                } else {
                    Log.d("WatchProgressPrefs", "  skipped key=$key (already synced)")
                }
            }

            val updated = splitWatchProgressEntries(local)
            Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${local.size} entries after merge, writing to DataStore")
            writeBucketsLocked(profileId, current, updated)
        }
        return preservedLocalItems
    }

    suspend fun applyRemoteChanges(
        upserts: Map<String, WatchProgress>,
        deletes: Collection<String>,
        lastSuccessfulPushMs: Long = 0L,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        if (upserts.isEmpty() && deletes.isEmpty()) {
            Log.d(TAG, "applyRemoteChanges: no changes for profile $profileId")
            return false
        }
        var preservedLocalItems = false
        var beforeCount = 0
        var afterCount = 0
        storageMutex.withLock {
            ensureStorageLocked(profileId)
            val current = readBucketsLocked(profileId)
            val local = mergeWatchProgressBuckets(current.recent, current.archive)
            beforeCount = local.size

            deletes.forEach { key ->
                local.remove(key)
            }

            upserts.forEach { (key, remote) ->
                val existing = local[key]
                if (existing == null || remote.lastWatched > existing.lastWatched) {
                    local[key] = mergeDisplayMetadata(remote, existing)
                } else if (existing.lastWatched > remote.lastWatched && existing.lastWatched > lastSuccessfulPushMs) {
                    preservedLocalItems = true
                }
            }

            val updated = splitWatchProgressEntries(local)
            afterCount = local.size
            writeBucketsLocked(profileId, current, updated)
        }
        Log.d(TAG, "applyRemoteChanges: profile=$profileId before=$beforeCount after=$afterCount upserts=${upserts.size} deletes=${deletes.size} preservedLocal=$preservedLocalItems")
        return preservedLocalItems
    }

    suspend fun replaceWithRemoteEntries(
        remoteEntries: Map<String, WatchProgress>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        Log.d("WatchProgressPrefs", "replaceWithRemoteEntries: ${remoteEntries.size} remote entries, profile=$profileId")
        storageMutex.withLock {
            ensureStorageLocked(profileId)
            val currentBuckets = readBucketsLocked(profileId)
            val current = mergeWatchProgressBuckets(currentBuckets.recent, currentBuckets.archive)
            if (remoteEntries.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "replaceWithRemoteEntries: remote empty while local has ${current.size} entries; preserving local watch progress")
                return@withLock
            }
            val merged = remoteEntries.mapValues { (key, remote) ->
                mergeDisplayMetadata(remote, current[key])
            }.toMutableMap()
            Log.d("WatchProgressPrefs", "replaceWithRemoteEntries: ${merged.size} entries after merge, writing to DataStore")
            writeBucketsLocked(profileId, currentBuckets, splitWatchProgressEntries(merged))
        }
    }

    /**
     * Clear all watch progress
     */
    suspend fun clearAll(profileId: Int = profileManager.activeProfileId.value) {
        storageMutex.withLock {
            ensureStorageLocked(profileId)
            clearBucketLocked(recentStore(profileId))
            clearBucketLocked(archiveStore(profileId))
            metadataStore(profileId).edit { preferences ->
                preferences.remove(watchProgressEntriesKey)
                preferences.remove(deltaCursorKey)
                preferences.remove(deltaInitializedKey)
                preferences[watchProgressStorageVersionKey] = WATCH_PROGRESS_STORAGE_VERSION
            }
        }
    }

    private fun progressSnapshotsCold(profileId: Int): Flow<ProgressSnapshot> = flow {
        ensureStorage(profileId)
        emitAll(
            combine(
                recentStore(profileId).data,
                archiveStore(profileId).data
            ) { _, _ -> Unit }
                .map {
                    storageMutex.withLock {
                        readSnapshotLocked(profileId)
                    }
                }
                .distinctUntilChanged { previous, current ->
                    previous.recentJson == current.recentJson &&
                        previous.archiveJson == current.archiveJson
                }
        )
    }

    private suspend fun readSnapshotLocked(profileId: Int): ProgressSnapshot {
        val recentJson = recentStore(profileId).data.first()[watchProgressEntriesKey] ?: "{}"
        val archiveJson = archiveStore(profileId).data.first()[watchProgressEntriesKey] ?: "{}"
        return ProgressSnapshot(
            profileId = profileId,
            recentJson = recentJson,
            archiveJson = archiveJson,
            recent = parseBucket(profileId, recentJson, true),
            archive = parseBucket(profileId, archiveJson, false)
        )
    }

    private suspend fun ensureStorage(profileId: Int) {
        storageMutex.withLock {
            ensureStorageLocked(profileId)
        }
    }

    private suspend fun ensureStorageLocked(profileId: Int) {
        if (profileId in initializedProfiles) return
        val metadata = metadataStore(profileId).data.first()
        if ((metadata[watchProgressStorageVersionKey] ?: 0) < WATCH_PROGRESS_STORAGE_VERSION) {
            val legacy = parseProgressMap(metadata[watchProgressEntriesKey] ?: "{}")
            if (legacy.isNotEmpty()) {
                val buckets = splitWatchProgressEntries(legacy)
                writeBucketLocked(archiveStore(profileId), buckets.archive)
                writeBucketLocked(recentStore(profileId), buckets.recent)
                Log.d(
                    TAG,
                    "Migrated profile=$profileId recent=${buckets.recent.size} archive=${buckets.archive.size}"
                )
            }
            metadataStore(profileId).edit { preferences ->
                preferences.remove(watchProgressEntriesKey)
                preferences[watchProgressStorageVersionKey] = WATCH_PROGRESS_STORAGE_VERSION
            }
        }
        initializedProfiles += profileId
    }

    private suspend fun readBucketsLocked(profileId: Int): WatchProgressBuckets {
        return WatchProgressBuckets(
            recent = readBucket(recentStore(profileId), profileId, true),
            archive = readBucket(archiveStore(profileId), profileId, false)
        )
    }

    private suspend fun readBucket(
        store: DataStore<Preferences>,
        profileId: Int,
        recent: Boolean
    ): Map<String, WatchProgress> {
        val json = store.data.first()[watchProgressEntriesKey] ?: "{}"
        return parseBucket(profileId, json, recent)
    }

    private fun parseBucket(
        profileId: Int,
        json: String,
        recent: Boolean
    ): Map<String, WatchProgress> {
        val cached = if (recent) recentMapCache else archiveMapCache
        if (cached != null && cached.profileId == profileId && cached.json == json) {
            return cached.entries
        }
        val parsed = parseProgressMap(json)
        val updated = ProgressMapCache(profileId, json, parsed)
        if (recent) {
            recentMapCache = updated
        } else {
            archiveMapCache = updated
        }
        return parsed
    }

    private suspend fun writeBucketsLocked(
        profileId: Int,
        current: WatchProgressBuckets,
        updated: WatchProgressBuckets
    ) {
        if (current.archive != updated.archive) {
            writeBucketLocked(archiveStore(profileId), updated.archive)
        }
        if (current.recent != updated.recent) {
            writeBucketLocked(recentStore(profileId), updated.recent)
        }
    }

    private suspend fun writeBucketLocked(
        store: DataStore<Preferences>,
        entries: Map<String, WatchProgress>
    ) {
        if (entries.isEmpty()) {
            clearBucketLocked(store)
            return
        }
        store.edit { preferences ->
            preferences[watchProgressEntriesKey] = encodeProgressMap(entries)
        }
    }

    private suspend fun clearBucketLocked(store: DataStore<Preferences>) {
        if (store.data.first()[watchProgressEntriesKey] == null) return
        store.edit { preferences ->
            preferences.remove(watchProgressEntriesKey)
        }
    }

    private fun upsertProgressEntries(
        map: MutableMap<String, WatchProgress>,
        progressList: List<WatchProgress>
    ) {
        progressList.forEach { progress ->
            val key = progress.progressKey
            val existing = map[key]
            // Preserve display metadata (poster, backdrop, logo, name) from the existing
            // entry when the incoming save has null values — prevents a mid-playback
            // position update from wiping artwork that was saved on first play.
            map[key] = if (existing != null) {
                progress.withDisplayMetadata(preferred = progress, fallback = existing)
            } else {
                progress
            }

            // Remove legacy series-level mirror key if this is an episode entry.
            // Mirror keys caused race conditions with stale progress data.
            if (progress.season != null && progress.episode != null) {
                val seriesKey = progress.contentId
                if (seriesKey != key && map.containsKey(seriesKey)) {
                    map.remove(seriesKey)
                }
            }
        }
    }

    /**
     * This entry with its five display-metadata fields (name, poster, backdrop, logo,
     * episodeTitle) resolved: [preferred]'s value when non-blank/non-null, else [fallback]'s.
     * Every other field stays as on this entry.
     */
    private fun WatchProgress.withDisplayMetadata(
        preferred: WatchProgress,
        fallback: WatchProgress
    ): WatchProgress = copy(
        name = preferred.name.takeIf { it.isNotBlank() } ?: fallback.name,
        poster = preferred.poster ?: fallback.poster,
        backdrop = preferred.backdrop ?: fallback.backdrop,
        logo = preferred.logo ?: fallback.logo,
        episodeTitle = preferred.episodeTitle ?: fallback.episodeTitle
    )

    private fun mergeDisplayMetadata(remote: WatchProgress, existing: WatchProgress?): WatchProgress {
        if (existing == null) return remote
        return remote.copy(
            name = existing.name.takeIf { it.isNotBlank() } ?: remote.name.takeIf { it.isNotBlank() } ?: existing.name,
            poster = existing.poster ?: remote.poster,
            backdrop = existing.backdrop ?: remote.backdrop,
            logo = existing.logo ?: remote.logo,
            episodeTitle = existing.episodeTitle ?: remote.episodeTitle,
            addonBaseUrl = remote.addonBaseUrl ?: existing.addonBaseUrl
        )
    }

    private fun parseProgressMap(json: String): Map<String, WatchProgress> {
        // Parse entry-by-entry so one malformed value doesn't wipe the entire map.
        val root = runCatching { localStoreJson.parseToJsonElement(json).jsonObject }.getOrNull()
        if (root == null) {
            Log.e(TAG, "Failed to parse progress data; trying legacy direct-payload format")
            // Backward compatibility with previously stored direct WatchProgress payloads.
            return runCatching {
                localStoreJson.decodeFromString(progressMapSerializer, json)
            }.getOrElse { emptyMap() }
        }
        val parsed = mutableMapOf<String, WatchProgress>()
        root.forEach { (key, value) ->
            runCatching {
                parseWatchProgressFromJson(value)
            }.onSuccess { watchProgress ->
                if (watchProgress != null) parsed[key] = watchProgress
            }.onFailure {
                Log.w(TAG, "Skipping malformed watch progress entry for key=$key")
            }
        }
        return parsed
    }

    private fun encodeProgressMap(entries: Map<String, WatchProgress>): String =
        localStoreJson.encodeToString(progressMapSerializer, entries)

    private fun parseWatchProgressFromJson(value: JsonElement): WatchProgress? {
        val obj = when {
            value is JsonObject -> value
            value is JsonPrimitive && value.isString -> {
                // Legacy double-encoded entries: a JSON string holding JSON.
                runCatching { localStoreJson.parseToJsonElement(value.content).jsonObject }.getOrNull()
            }
            else -> null
        } ?: return null
        val contentId = obj.getString("contentId", "content_id")?.takeIf { it.isNotBlank() } ?: return null
        val contentType = obj.getString("contentType", "content_type")?.takeIf { it.isNotBlank() } ?: return null
        val videoId = obj.getString("videoId", "video_id")?.takeIf { it.isNotBlank() } ?: contentId
        val lastWatched = obj.getLong("lastWatched", "last_watched") ?: return null

        return WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = obj.getString("name").orEmpty(),
            poster = obj.getString("poster"),
            backdrop = obj.getString("backdrop"),
            logo = obj.getString("logo"),
            videoId = videoId,
            season = obj.getInt("season"),
            episode = obj.getInt("episode"),
            episodeTitle = obj.getString("episodeTitle", "episode_title"),
            position = obj.getLong("position") ?: 0L,
            duration = obj.getLong("duration") ?: 0L,
            lastWatched = lastWatched,
            addonBaseUrl = obj.getString("addonBaseUrl", "addon_base_url"),
            progressPercent = obj.getFloat("progressPercent", "progress_percent"),
            source = obj.getString("source")?.takeIf { it.isNotBlank() } ?: WatchProgress.SOURCE_LOCAL,
            traktPlaybackId = obj.getLong("traktPlaybackId", "trakt_playback_id"),
            traktMovieId = obj.getInt("traktMovieId", "trakt_movie_id"),
            traktShowId = obj.getInt("traktShowId", "trakt_show_id"),
            traktEpisodeId = obj.getInt("traktEpisodeId", "trakt_episode_id"),
            // No snake_case aliases here: these fields postdate the legacy
            // snake_case writers, and both the Gson-era and kotlinx writers
            // have only ever used the property names. Dropping them (the
            // pre-fix behavior) lost paused-session ids on every
            // read-modify-write.
            simklPlaybackId = obj.getLong("simklPlaybackId"),
            mdbListPlaybackId = obj.getLong("mdbListPlaybackId"),
            trackingProviderId = obj.getString("trackingProviderId"),
            trackingProviderItemId = obj.getString("trackingProviderItemId"),
            trackingSourceUrl = obj.getString("trackingSourceUrl")
        )
    }

    private fun JsonObject.getString(vararg keys: String): String? {
        keys.forEach { key ->
            val value = this[key] ?: return@forEach
            if (value is JsonNull) return@forEach
            (value as? JsonPrimitive)?.let { return it.content }
        }
        return null
    }

    private fun JsonObject.getLong(vararg keys: String): Long? {
        keys.forEach { key ->
            val value = this[key] ?: return@forEach
            val primitive = value as? JsonPrimitive ?: return@forEach
            primitive.content.toLongOrNull()?.let { return it }
            primitive.content.toDoubleOrNull()?.toLong()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getInt(vararg keys: String): Int? {
        keys.forEach { key ->
            val value = this[key] ?: return@forEach
            val primitive = value as? JsonPrimitive ?: return@forEach
            primitive.content.toIntOrNull()?.let { return it }
            primitive.content.toDoubleOrNull()?.toInt()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getFloat(vararg keys: String): Float? {
        keys.forEach { key ->
            val value = this[key] ?: return@forEach
            val primitive = value as? JsonPrimitive ?: return@forEach
            primitive.content.toFloatOrNull()?.let { return it }
            primitive.content.toDoubleOrNull()?.toFloat()?.let { return it }
        }
        return null
    }

}
