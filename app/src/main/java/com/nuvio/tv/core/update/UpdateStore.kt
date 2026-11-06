package com.nuvio.tv.core.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.data.local.localStoreJson
import com.nuvio.tv.data.local.loggedResetCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Device-wide, unsynced, kept on sign-out (ProfileDataStoreFactory's retained names). */
internal const val UPDATE_STORE_NAME = "update_settings"

private val Context.updateDataStore by preferencesDataStore(
    name = UPDATE_STORE_NAME,
    corruptionHandler = loggedResetCorruptionHandler(UPDATE_STORE_NAME)
)

/** What the update check keeps between starts (Spec 52 P7). */
data class UpdateRecord(
    val autoCheck: Boolean = true,
    val lastOkCheckMs: Long = 0L,
    val lastAttemptMs: Long = 0L,
    /** The latest release's offer for this TV as of the last answer; newer or not. */
    val knownRelease: KnownRelease? = null
)

/**
 * The legacy updater's file (removed in b467ba833) under NEW key names: its old keys
 * (ignored_release_tag, last_check_at_ms, update_banner_enabled) stay unread. UpdateController is
 * the only reader and writer: it reads once and keeps what each write returns.
 */
@Singleton
class UpdateStore internal constructor(private val dataStore: DataStore<Preferences>) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.updateDataStore)

    /** The stored record; a file that cannot be read reads as the defaults (auto on, never checked). */
    suspend fun read(): UpdateRecord = try {
        dataStore.data.first().toRecord()
    } catch (_: IOException) {
        UpdateRecord()
    }

    /** Throws the DataStore write's IOException. */
    suspend fun setAutoCheck(enabled: Boolean): UpdateRecord =
        dataStore.edit { it[AUTO_CHECK] = enabled }.toRecord()

    /**
     * Records an answered check at [nowMs] and returns the record; [CheckOutcome.Unreached] writes
     * nothing. Throws the DataStore write's IOException.
     */
    suspend fun record(outcome: CheckOutcome, nowMs: Long): UpdateRecord {
        if (outcome == CheckOutcome.Unreached) return read()
        return dataStore.edit { prefs ->
            prefs[LAST_ATTEMPT_MS] = nowMs
            when (outcome) {
                is CheckOutcome.Answered -> {
                    prefs[LAST_OK_CHECK_MS] = nowMs
                    val offer = outcome.offer
                    if (offer == null) prefs.remove(KNOWN_RELEASE)
                    else prefs[KNOWN_RELEASE] = localStoreJson.encodeToString(KnownRelease.serializer(), offer)
                }
                CheckOutcome.NoRelease -> prefs.remove(KNOWN_RELEASE)
                CheckOutcome.Failed, CheckOutcome.Unreached -> Unit
            }
        }.toRecord()
    }

    /**
     * Drops the known release when it is still [release] (a download proved it cannot install, P7);
     * a different one a later check stored stays. Throws the DataStore write's IOException.
     */
    suspend fun forgetRelease(release: KnownRelease): UpdateRecord =
        dataStore.edit { prefs ->
            if (prefs[KNOWN_RELEASE]?.let(::decodeRelease) == release) prefs.remove(KNOWN_RELEASE)
        }.toRecord()

    private fun Preferences.toRecord() = UpdateRecord(
        autoCheck = this[AUTO_CHECK] ?: true,
        lastOkCheckMs = this[LAST_OK_CHECK_MS] ?: 0L,
        lastAttemptMs = this[LAST_ATTEMPT_MS] ?: 0L,
        knownRelease = this[KNOWN_RELEASE]?.let(::decodeRelease)
    )

    internal companion object {
        val AUTO_CHECK = booleanPreferencesKey("auto_check")
        val LAST_OK_CHECK_MS = longPreferencesKey("last_ok_check_ms")
        val LAST_ATTEMPT_MS = longPreferencesKey("last_attempt_ms")
        val KNOWN_RELEASE = stringPreferencesKey("known_release")

        /** A record that does not decode reads as none (the next check rewrites it); unknown fields are skipped. */
        fun decodeRelease(json: String): KnownRelease? = try {
            localStoreJson.decodeFromString(KnownRelease.serializer(), json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
