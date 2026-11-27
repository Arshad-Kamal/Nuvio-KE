package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeGroup
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.streams.StreamBadgeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamBadgeSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    companion object {
        const val FEATURE = "stream_badge_settings"

        /** One instance for "nothing stored", so readers keep the presentation's identity path. */
        private val NO_RULES = StreamBadgeRules()
        private const val LEGACY_DEBRID_FEATURE = "debrid_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private fun legacyDebridStore(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, LEGACY_DEBRID_FEATURE)

    private val streamBadgeRulesKey = stringPreferencesKey("stream_badge_rules")
    private val showFileSizeBadgesKey = booleanPreferencesKey("show_file_size_badges")
    // Retired settings (U5m): read, never written; the profile-settings sync carries the stored values.
    private val showAddonLogoKey = booleanPreferencesKey("show_addon_logo")
    private val streamBadgePlacementKey = stringPreferencesKey("stream_badge_placement")
    private val useAddonFormattingKey = booleanPreferencesKey("use_addon_formatting")
    private val legacyDebridStreamBadgeRulesKey = stringPreferencesKey("debrid_stream_badge_rules")

    /**
     * The last stored rules JSON and its decode (Spec 130 DEFER). Badge readers call
     * `settings.first()` per 5-stream chunk; a big pack's JSON (FR Badges 160 KB) decoded each
     * time, and an unchanged string now returns the same instance, so StreamBadgePresentation
     * takes its identity fast path. One immutable pair, swapped whole.
     */
    @Volatile
    private var decodedRules: Pair<String, StreamBadgeRules>? = null

    val settings: Flow<StreamBadgeSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.onStart { migrateProfile(pid) }
    }.map { prefs ->
        StreamBadgeSettings(
            rules = cachedStreamBadgeRules(prefs[streamBadgeRulesKey]),
            showFileSizeBadges = prefs[showFileSizeBadgesKey] ?: true,
            showAddonLogo = prefs[showAddonLogoKey] ?: true,
            badgePlacement = prefs[streamBadgePlacementKey].toStreamBadgePlacement(),
            useAddonFormatting = prefs[useAddonFormattingKey] ?: false
        )
    }

    suspend fun setShowFileSizeBadges(enabled: Boolean) {
        store().edit { it[showFileSizeBadgesKey] = enabled }
    }

    suspend fun setUseAddonFormatting(enabled: Boolean) {
        store().edit { it[useAddonFormattingKey] = enabled }
    }

    suspend fun setStreamBadgeRules(rules: StreamBadgeRules) {
        updateStreamBadgeRules { rules }
    }

    /**
     * The phone badge page's save; it edits badge rules only. [transform] runs on the stored rules
     * inside one DataStore edit, so back-to-back changes each see the previous write (Spec 130 F4:
     * a delete based on an in-memory copy brought back a pack the previous delete removed).
     * Returns the saved rules.
     */
    suspend fun updateStreamBadgeRules(transform: (StreamBadgeRules) -> StreamBadgeRules): StreamBadgeRules {
        var saved = StreamBadgeRules()
        store().edit {
            val normalized = transform(cachedStreamBadgeRules(it[streamBadgeRulesKey])).normalized()
            if (normalized.hasImport) {
                it[streamBadgeRulesKey] = json.encodeToString(normalized)
            } else {
                it.remove(streamBadgeRulesKey)
            }
            saved = normalized
        }
        return saved
    }

    private suspend fun migrateProfile(profileId: Int) {
        val currentPrefs = store(profileId).data.first()
        if (currentPrefs[streamBadgeRulesKey] != null) return
        val legacyPrefs = legacyDebridStore(profileId).data.first()
        val legacyRules = parseStreamBadgeRules(legacyPrefs[legacyDebridStreamBadgeRulesKey]) ?: return
        store(profileId).edit {
            if (it[streamBadgeRulesKey] == null) {
                it[streamBadgeRulesKey] = json.encodeToString(legacyRules.normalized())
            }
        }
        legacyDebridStore(profileId).edit { it.remove(legacyDebridStreamBadgeRulesKey) }
    }

    private fun cachedStreamBadgeRules(value: String?): StreamBadgeRules {
        if (value == null) return NO_RULES
        decodedRules?.let { (raw, rules) -> if (raw == value) return rules }
        val rules = parseStreamBadgeRules(value) ?: StreamBadgeRules()
        decodedRules = value to rules
        return rules
    }

    private fun parseStreamBadgeRules(value: String?): StreamBadgeRules? {
        if (value.isNullOrBlank()) return null
        val decodedRules = try {
            json.decodeFromString<StreamBadgeRules>(value).normalized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (decodedRules?.hasImport == true) return decodedRules

        val legacyRules = try {
            json.decodeFromString<LegacyStreamBadgeRules>(value)
                .toBadgeRules()
                .normalized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return legacyRules?.takeIf { it.hasImport } ?: decodedRules
    }

    private fun String?.toStreamBadgePlacement(): StreamBadgePlacement =
        StreamBadgePlacement.entries.firstOrNull { placement ->
            placement.name.equals(this, ignoreCase = true)
        } ?: StreamBadgePlacement.BOTTOM
}

@Serializable
private data class LegacyStreamBadgeRules(
    val sourceUrl: String = "",
    val filters: List<StreamBadgeFilter> = emptyList(),
    val groups: List<StreamBadgeGroup> = emptyList()
) {
    fun toBadgeRules(): StreamBadgeRules =
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = sourceUrl.ifBlank { "Local badge rules" },
                    filters = filters,
                    groups = groups
                )
            )
        )
}
