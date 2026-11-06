package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared holder for fully-watched series IDs derived from the CW pipeline.
 * Updated by HomeViewModel, observed by any screen that needs series watched badges.
 * Persisted per profile so badges appear instantly on cold start.
 *
 * Uses per-series revalidation deadlines instead of a global TTL:
 * - Default: 7 days after last validation
 * - If a known upcoming season release date exists, revalidate at that date
 */
@Singleton
class WatchedSeriesStateHolder @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_series_cache"
        private val KEY = stringSetPreferencesKey("fully_watched_ids")
        private val REVALIDATE_KEY = stringPreferencesKey("revalidate_after")
        private val VALIDATION_RESET_KEY = intPreferencesKey("validation_reset_version")
        private const val VALIDATION_RESET_VERSION = 1
        private const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val fullyWatchedSeriesIds: StateFlow<Set<String>> = _fullyWatchedSeriesIds.asStateFlow()

    /** Per-series revalidation deadline (contentId → epochMs when re-check is needed). */
    @Volatile
    private var revalidateAfterMap: Map<String, Long> = emptyMap()
    @Volatile
    private var loadedForProfileId: Int? = null

    private fun store(profileId: Int = profileManager.activeProfileId.value) = factory.get(profileId, FEATURE)

    suspend fun loadFromDisk(profileId: Int = profileManager.activeProfileId.value) {
        if (loadedForProfileId == profileId) return
        // Ids still in memory belong to the previously loaded profile (Spec 48 P2, NM b39ce87d4):
        // a profile switch disposes the old HomeViewModel before its switch handler can clear them.
        val switchingProfile = loadedForProfileId != null
        val prefs = store(profileId).data.first()
        val persisted = prefs[KEY] ?: emptySet()
        val resetVersion = prefs[VALIDATION_RESET_KEY] ?: 0
        val deadlines = if (resetVersion < VALIDATION_RESET_VERSION) {
            store(profileId).edit { p ->
                p.remove(REVALIDATE_KEY)
                p[VALIDATION_RESET_KEY] = VALIDATION_RESET_VERSION
            }
            emptyMap()
        } else {
            parseTimestamps(prefs[REVALIDATE_KEY])
        }
        // Applied under the writers' lock, so a concurrent read-modify-write cannot put the
        // previous profile's ids back after the replace (Spec 48 P2 review).
        synchronized(this) {
            revalidateAfterMap = deadlines
            if (switchingProfile || (_fullyWatchedSeriesIds.value.isEmpty() && persisted.isNotEmpty())) {
                _fullyWatchedSeriesIds.value = persisted
            }
            loadedForProfileId = profileId
        }
    }

    /**
     * Maps the current badge IDs through [transform] under this holder's lock, so a concurrent
     * CW badge update cannot be lost. Validation deadlines are left untouched.
     */
    @Synchronized
    fun update(transform: (current: Set<String>) -> Set<String>) {
        val ids = transform(_fullyWatchedSeriesIds.value)
        _fullyWatchedSeriesIds.value = ids
        persistLatest(profileManager.activeProfileId.value, ids, deadlines = null)
    }

    /** Clear in-memory state only — does NOT touch DataStore on disk. */
    fun clearInMemory() {
        _fullyWatchedSeriesIds.value = emptySet()
        revalidateAfterMap = emptyMap()
    }

    /**
     * Update badge IDs and mark the given series as freshly validated.
     * [revalidateAt] allows setting per-series deadlines (e.g. upcoming season premiere).
     * Series not in [revalidateAt] get the default 7-day TTL.
     * Private: callers go through [transformWithValidation], which derives [ids] from the
     * current set under the lock (a caller-side snapshot lost concurrent updates, L5-03).
     */
    @Synchronized
    private fun updateWithValidation(
        ids: Set<String>,
        validatedIds: Set<String>,
        revalidateAt: Map<String, Long> = emptyMap(),
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val idsChanged = _fullyWatchedSeriesIds.value != ids
        _fullyWatchedSeriesIds.value = ids
        val now = System.currentTimeMillis()
        val defaultDeadline = now + DEFAULT_TTL_MS
        val updated = revalidateAfterMap.toMutableMap()
        var deadlinesChanged = false
        validatedIds.forEach { id ->
            val newDeadline = revalidateAt[id] ?: defaultDeadline
            if (updated[id] != newDeadline) {
                updated[id] = newDeadline
                deadlinesChanged = true
            }
        }
        // Prune entries for series no longer in badge set AND not freshly validated.
        // Skip pruning when only adding deadlines (validatedIds not in ids) to avoid
        // removing deadlines set by earlier calls in the same async inject loop.
        if (idsChanged) {
            val keysToRetain = ids + validatedIds
            val sizeBefore = updated.size
            updated.keys.retainAll(keysToRetain)
            if (updated.size != sizeBefore) deadlinesChanged = true
        }

        revalidateAfterMap = updated
        if (idsChanged || deadlinesChanged) {
            persistLatest(profileId, ids, updated)
        }
    }

    /**
     * Read-modify-write form of [updateWithValidation]: [transform] maps the current badge IDs
     * under this holder's lock, so concurrent callers cannot lose each other's updates. The
     * default keeps the IDs and only records validation deadlines.
     */
    @Synchronized
    fun transformWithValidation(
        validatedIds: Set<String>,
        revalidateAt: Map<String, Long> = emptyMap(),
        profileId: Int = profileManager.activeProfileId.value,
        transform: (current: Set<String>) -> Set<String> = { it }
    ) {
        updateWithValidation(transform(_fullyWatchedSeriesIds.value), validatedIds, revalidateAt, profileId)
    }

    /**
     * Adds [ids] to (or removes them from) the fully-watched badge set and marks them
     * validated. The snapshot comparison (read outside the lock) only skips a no-op write; the change itself is applied
     * to the current IDs under the lock, so a concurrent Home/CW badge update to another
     * series is not overwritten with a stale snapshot (L5-03).
     */
    fun setSeriesFullyWatched(ids: Set<String>, fullyWatched: Boolean) {
        val current = _fullyWatchedSeriesIds.value
        val updated = if (fullyWatched) current + ids else current - ids
        if (updated == current) return
        transformWithValidation(validatedIds = ids) { latest ->
            if (fullyWatched) latest + ids else latest - ids
        }
    }

    /**
     * Returns true if the given series does not yet need re-validation.
     */
    fun isSeriesValidationFresh(contentId: String): Boolean {
        val deadline = revalidateAfterMap[contentId] ?: return false
        return System.currentTimeMillis() < deadline
    }

    /**
     * Returns true if the series has ever been validated (has an entry in the map).
     */
    fun hasBeenValidated(contentId: String): Boolean {
        return contentId in revalidateAfterMap
    }

    /**
     * Clears all revalidation deadlines, forcing every series to be re-evaluated
     * on the next CW pipeline cycle. Called when the user manually clears the CW cache.
     */
    fun clearValidationState(profileId: Int = profileManager.activeProfileId.value) {
        revalidateAfterMap = emptyMap()
        scope.launch {
            store(profileId).edit { prefs -> prefs.remove(REVALIDATE_KEY) }
        }
    }

    /**
     * Filters the given set to only those series that need re-validation
     * (past their revalidation deadline or never validated).
     */
    fun filterStaleIds(ids: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        return ids.filter { id ->
            val deadline = revalidateAfterMap[id] ?: return@filter true
            now >= deadline
        }.toSet()
    }

    /**
     * Persists on IO. Each write stores the holder's state as it is when the write runs, not
     * the values captured at launch, so whichever write lands last stores the latest state even
     * when launches complete out of order. If the active profile changed meanwhile, the holder
     * no longer holds [profileId]'s state, so the captured values are written instead.
     * [deadlines] null leaves the persisted deadlines untouched.
     */
    private fun persistLatest(profileId: Int, ids: Set<String>, deadlines: Map<String, Long>?) {
        scope.launch {
            store(profileId).edit { prefs ->
                val (latestIds, latestDeadlines) = if (profileManager.activeProfileId.value == profileId) {
                    synchronized(this@WatchedSeriesStateHolder) {
                        _fullyWatchedSeriesIds.value to revalidateAfterMap
                    }
                } else {
                    ids to deadlines
                }
                prefs[KEY] = latestIds
                if (deadlines != null && latestDeadlines != null) {
                    prefs[REVALIDATE_KEY] = encodeLongMap(latestDeadlines)
                }
            }
        }
    }

    private fun parseTimestamps(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return decodeLongMap(json) ?: emptyMap()
    }
}
