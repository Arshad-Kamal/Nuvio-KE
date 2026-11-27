package com.nuvio.tv.data.mdblist

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * A profile's MDBList ratings choices (Spec 105 S4, mockup v3 B1/B2). [enabled]: the switch as last set, null = never
 * set (on once a key exists, D3: off until a key, on with the first). [order]: all eight sources in the detail line's
 * order. [off]: the sources switched off (default the last two, so the first six show).
 */
data class MdbListRatingsSettings(
    val enabled: Boolean? = null,
    val order: List<MdbListRatingSource> = MdbListRatingSource.entries,
    val off: Set<MdbListRatingSource> = DEFAULT_OFF
) {
    /** What the detail line shows, in order, while ratings are on. */
    val shown: List<MdbListRatingSource> get() = order.filter { it !in off }

    /** Ratings on (D3/D7): the profile's key [connected] and the switch not turned off (unset = on). */
    fun onWith(connected: Boolean): Boolean = connected && enabled != false

    companion object {
        val DEFAULT_OFF: Set<MdbListRatingSource> = setOf(MdbListRatingSource.TRAKT, MdbListRatingSource.MAL)
    }
}

/**
 * Per-profile store (feature [FEATURE]; this TV's, not synced: BackupCatalog `tvOnly`). Not a secret: the key itself is
 * the tracker's (`tracking_accounts`, D7).
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MdbListRatingsPrefs @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    /** The active profile's choices; follows profile switches. */
    val active: Flow<MdbListRatingsSettings> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map(::readMdbListRatingsSettings)
    }.distinctUntilChanged()

    suspend fun setEnabled(profileId: Int, enabled: Boolean) {
        factory.get(profileId, FEATURE).edit { it[ENABLED] = enabled }
    }

    /** Flips [source] on or off, read inside the edit so two quick presses both count. */
    suspend fun toggleSource(profileId: Int, source: MdbListRatingSource) {
        factory.get(profileId, FEATURE).edit { prefs ->
            val off = readMdbListRatingsSettings(prefs).off
            prefs[OFF] = encodeSources(if (source in off) off - source else off + source)
        }
    }

    /** Moves [source] one place ([delta] -1 up, +1 down); nothing at the ends. */
    suspend fun move(profileId: Int, source: MdbListRatingSource, delta: Int) {
        factory.get(profileId, FEATURE).edit { prefs ->
            prefs[ORDER] = encodeSources(moveMdbListSource(readMdbListRatingsSettings(prefs).order, source, delta))
        }
    }

    internal companion object {
        /** On-disk name: never rename (every profile would lose its choices). */
        const val FEATURE = "mdblist_ratings"
        val ENABLED = booleanPreferencesKey("enabled")
        val ORDER = stringPreferencesKey("order")
        val OFF = stringPreferencesKey("off")
    }
}

/** The profile's MDBList key is stored and working (D7: ratings use the tracker's key). */
fun TrackingAccountStore.mdbListConnected(): Flow<Boolean> =
    account(TrackingProviderId.MDBLIST).map { it != null && !it.revoked }.distinctUntilChanged()

internal fun readMdbListRatingsSettings(prefs: Preferences): MdbListRatingsSettings = MdbListRatingsSettings(
    enabled = prefs[MdbListRatingsPrefs.ENABLED],
    order = prefs[MdbListRatingsPrefs.ORDER]?.let(::decodeMdbListOrder) ?: MdbListRatingSource.entries,
    off = prefs[MdbListRatingsPrefs.OFF]?.let { decodeSources(it).toSet() } ?: MdbListRatingsSettings.DEFAULT_OFF
)

/** Stored order: known names once each, in their stored places; any source missing (added later) joins at the end. */
internal fun decodeMdbListOrder(raw: String): List<MdbListRatingSource> {
    val stored = decodeSources(raw).distinct()
    return stored + MdbListRatingSource.entries.filter { it !in stored }
}

internal fun moveMdbListSource(
    order: List<MdbListRatingSource>,
    source: MdbListRatingSource,
    delta: Int
): List<MdbListRatingSource> {
    val from = order.indexOf(source)
    val to = from + delta
    if (from < 0 || to !in order.indices) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

private fun decodeSources(raw: String): List<MdbListRatingSource> =
    raw.split(',').mapNotNull { name -> MdbListRatingSource.entries.firstOrNull { it.name == name.trim() } }

private fun encodeSources(sources: Collection<MdbListRatingSource>): String = sources.joinToString(",") { it.name }
