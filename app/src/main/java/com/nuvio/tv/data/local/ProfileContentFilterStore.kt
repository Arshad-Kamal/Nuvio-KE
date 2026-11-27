package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A profile's stored content filters, or [Unreadable] (the gates fail closed on it; the editor writes nothing). */
sealed interface ContentFilterRead {
    data class Stored(val settings: ContentFilterSettings) : ContentFilterRead
    data object Unreadable : ContentFilterRead
}

/**
 * Each profile's content filters (Spec 117 P1, D5: device-local, keyed by profile id, never in the
 * sync payload): its own file through [ProfileDataStoreFactory] (`profile_content_filters`, `_p<id>`
 * past profile 1), so profile deletion and sign-out sweep it with the other profile files. One key
 * holds the settings as JSON; only the profile editor writes it.
 *
 * A file that cannot be read, or a value that does not decode, reads as [ContentFilterRead.Unreadable]
 * (after an IOException the flow emits that once and completes). No key reads as empty settings.
 * Known gap, as the rating cap: a corrupt file is handled by the factory's corruption handler, which
 * restores the `.bak` shadow copy or else resets to empty. Writes report failure instead of throwing.
 */
@Singleton
class ProfileContentFilterStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    fun settingsFor(profileId: Int): Flow<ContentFilterRead> =
        factory.get(profileId, FEATURE).data
            .map(::decode)
            .catch { e -> if (e is IOException) emit(ContentFilterRead.Unreadable) else throw e }
            .distinctUntilChanged()

    /** Stores [settings] for profile [profileId] (empty settings remove the key); false when the file cannot be written. */
    suspend fun write(profileId: Int, settings: ContentFilterSettings): Boolean = try {
        factory.get(profileId, FEATURE).edit { prefs -> put(prefs, settings) }
        true
    } catch (e: IOException) {
        Log.w(TAG, "Content filters not saved: ${e.javaClass.simpleName}")
        false
    }

    /**
     * The editor's save (117/S7a): stores [edited] for profile [profileId], keeping the stored
     * `previewProfileId` (Spec 122 T1b: the editor never saw a clear made since it opened), in one
     * read-modify-write. An unreadable stored value keeps none. False when the file cannot be written.
     */
    suspend fun writeEdited(profileId: Int, edited: ContentFilterSettings): Boolean = try {
        factory.get(profileId, FEATURE).edit { prefs ->
            val stored = (decode(prefs) as? ContentFilterRead.Stored)?.settings
            put(prefs, edited.copy(previewProfileId = stored?.previewProfileId))
        }
        true
    } catch (e: IOException) {
        Log.w(TAG, "Content filters not saved: ${e.javaClass.simpleName}")
        false
    }

    /**
     * Spec 122 D3, the picker: sets the primary's `previewProfileId` to [previewProfileId] (null = off),
     * keeping everything else stored, in one read-modify-write. An unreadable stored value is not
     * written over (false: the picker's write must not wipe the filters it could not read); false too
     * when the file cannot be written.
     */
    suspend fun setPreview(previewProfileId: Int?): Boolean = try {
        var readable = false
        factory.get(PRIMARY_PROFILE_ID, FEATURE).edit { prefs ->
            val read = decode(prefs)
            readable = read is ContentFilterRead.Stored
            if (read is ContentFilterRead.Stored) put(prefs, read.settings.copy(previewProfileId = previewProfileId))
        }
        readable
    } catch (e: IOException) {
        Log.w(TAG, "Content filter preview not saved: ${e.javaClass.simpleName}")
        false
    }

    /**
     * Spec 122 (R1): turns the primary's preview off when it names [profileId] (called before that profile
     * is deleted), in one read-modify-write. An unreadable value is left alone; false when the file
     * cannot be written (the deletion still goes ahead: the reader ignores an id no profile has).
     */
    suspend fun clearPreviewOf(profileId: Int): Boolean = try {
        factory.get(PRIMARY_PROFILE_ID, FEATURE).edit { prefs ->
            val read = decode(prefs)
            if (read is ContentFilterRead.Stored && read.settings.previewProfileId == profileId) {
                put(prefs, read.settings.copy(previewProfileId = null))
            }
        }
        true
    } catch (e: IOException) {
        Log.w(TAG, "Content filter preview not cleared: ${e.javaClass.simpleName}")
        false
    }

    private fun put(prefs: MutablePreferences, settings: ContentFilterSettings) {
        if (settings == ContentFilterSettings()) {
            prefs.remove(SETTINGS)
        } else {
            prefs[SETTINGS] = localStoreJson.encodeToString(ContentFilterSettings.serializer(), settings)
        }
    }

    companion object {
        private const val TAG = "ProfileContentFilterStore"
        const val FEATURE = "profile_content_filters"
        const val PRIMARY_PROFILE_ID = 1

        /** Far above any editor-made value (18 ids, word lists, a few own packs); larger is damage. */
        internal const val MAX_STORED_CHARS = 256 * 1024

        private val SETTINGS = stringPreferencesKey("settings")

        internal fun decode(prefs: Preferences): ContentFilterRead {
            val raw = prefs[SETTINGS] ?: return ContentFilterRead.Stored(ContentFilterSettings())
            if (raw.length > MAX_STORED_CHARS) {
                Log.w(TAG, "Content filters unreadable: ${raw.length} chars")
                return ContentFilterRead.Unreadable
            }
            return try {
                ContentFilterRead.Stored(localStoreJson.decodeFromString(ContentFilterSettings.serializer(), raw))
            } catch (e: IllegalArgumentException) { // includes SerializationException (KT-JSON-04)
                Log.w(TAG, "Content filters unreadable: ${e.javaClass.simpleName}")
                ContentFilterRead.Unreadable
            }
        }
    }
}
