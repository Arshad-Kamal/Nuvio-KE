package com.nuvio.tv.core.health

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.addonOwnerProfileId
import com.nuvio.tv.data.local.addonOwnerProfileIdFlow
import com.nuvio.tv.data.local.localStoreJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted, profile-scoped passive health for add-ons. Keys are opaque:
 * [ADDON_PREFIX] + canonical base URL, or a [METADATA_PREFIX] key.
 * kotlinx JSON + per-profile DataStore (one home: data/local LocalStoreJson).
 * No polling.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AddonHealthStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) {
    companion object {
        private const val FEATURE = "addon_health"
        const val ADDON_PREFIX = "addon:"
        const val METADATA_PREFIX = "metadata:"
        const val METADATA_KEY = "metadata:pipeline"
        // Redundant fast successes for the same key within this window are
        // dropped (collapses e.g. one addon serving many catalog rows on
        // startup). Failures, empties and slow successes are never coalesced.
        private const val COALESCE_WINDOW_MS = 30_000L

        fun addonKey(canonicalBaseUrl: String): String = ADDON_PREFIX + canonicalBaseUrl
    }

    private val lastFastSuccessAtMs = ConcurrentHashMap<String, Long>()
    private val recordsKey = stringPreferencesKey("health_records")
    private val recordMapSerializer = MapSerializer(String.serializer(), HealthRecord.serializer())

    // Health follows the addon owner: a usesPrimaryAddons profile shares profile 1's records.
    private fun effectiveProfileId(): Int =
        addonOwnerProfileId(profileManager.profiles.value, profileManager.activeProfileId.value)

    private fun store(profileId: Int = effectiveProfileId()) = factory.get(profileId, FEATURE)

    private val effectiveProfileIdFlow: Flow<Int> = profileManager.addonOwnerProfileIdFlow()

    private fun slowThresholdFor(key: String): Long =
        if (key.startsWith(METADATA_PREFIX)) {
            AddonHealthModel.METADATA_SLOW_LATENCY_MS
        } else {
            AddonHealthModel.SLOW_LATENCY_MS
        }

    /** Derived traffic-light levels, keyed as above. Recomputed on every write. */
    val levels: Flow<Map<String, AddonHealthLevel>> =
        layoutPreferenceDataStore.addonHealthEnabled.flatMapLatest { enabled ->
            if (!enabled) {
                flowOf(emptyMap())
            } else {
                effectiveProfileIdFlow.flatMapLatest { pid ->
                    factory.get(pid, FEATURE).data.map { preferences ->
                        try {
                            val now = System.currentTimeMillis()
                            parseRecords(preferences[recordsKey]).mapValues { (key, record) ->
                                AddonHealthModel.deriveLevel(record, now, slowThresholdFor(key))
                            }
                        } catch (e: Exception) {
                            emptyMap()
                        }
                    }
                }
            }
        }

    /** Record one request outcome against [key]. Never throws. */
    suspend fun record(key: String, outcome: HealthOutcome, latencyMs: Long) {
        try {
            if (!layoutPreferenceDataStore.addonHealthEnabled.first()) return
            val now = System.currentTimeMillis()
            if (outcome == HealthOutcome.SUCCESS) {
                if (latencyMs <= slowThresholdFor(key)) {
                    val lastFast = lastFastSuccessAtMs[key] ?: 0L
                    if (now - lastFast < COALESCE_WINDOW_MS) return
                    lastFastSuccessAtMs[key] = now
                }
            }
            val sample = HealthSample(
                atMs = now,
                outcome = outcome,
                latencyMs = latencyMs
            )
            store().edit { preferences ->
                val current = parseRecords(preferences[recordsKey]).toMutableMap()
                val updated = AddonHealthModel.applySample(
                    current[key] ?: HealthRecord(),
                    sample
                )
                current[key] = updated
                preferences[recordsKey] = localStoreJson.encodeToString(recordMapSerializer, current)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Health is passive telemetry: a storage failure (e.g. a full disk on a
            // device holding remuxes) must cost one lost sample, never crash the app
            // from the unguarded launches at the record call sites. Contract holds.
        }
    }

    private fun parseRecords(json: String?): Map<String, HealthRecord> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            localStoreJson.decodeFromString(recordMapSerializer, json)
        }.getOrDefault(emptyMap())
    }
}
