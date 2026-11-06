package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.model.ProfileLookChoice
import com.nuvio.tv.domain.model.ProfileLookEntry
import com.nuvio.tv.domain.model.ProfileLookPick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each profile's TV-only look entry (Spec 44 Design "Local look"): its own file through
 * [ProfileDataStoreFactory] (`profile_look`, `_p<id>` past profile 1), so profile deletion and
 * sign-out sweep it with the other profile files, and it never enters the sync payload. Writes
 * report failure (a full disk) instead of throwing.
 */
@Singleton
class ProfileLookStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    /** Profile [profileId]'s entry, null when none; an unreadable file reads as none. */
    fun entryFor(profileId: Int): Flow<ProfileLookEntry?> =
        factory.get(profileId, FEATURE).data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map(::decode)
            .distinctUntilChanged()

    /** Replaces profile [profileId]'s entry ([entry] null clears it); false when the file cannot be written. */
    suspend fun write(profileId: Int, entry: ProfileLookEntry?): Boolean = try {
        factory.get(profileId, FEATURE).edit { prefs -> encode(prefs, entry) }
        true
    } catch (e: IOException) {
        Log.w(TAG, "Profile look not saved: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "ProfileLookStore"
        const val FEATURE = "profile_look"

        private val AVATAR = Part("avatar")
        private val BACKGROUND = Part("background")

        private class Part(prefix: String) {
            val pick = stringPreferencesKey("${prefix}_pick")
            val name = stringPreferencesKey("${prefix}_name")
            val key = stringPreferencesKey("${prefix}_key")
        }

        internal fun decode(prefs: Preferences): ProfileLookEntry? {
            val entry = ProfileLookEntry(avatar = decodePart(prefs, AVATAR), background = decodePart(prefs, BACKGROUND))
            return entry.takeIf { it.avatar != null || it.background != null }
        }

        internal fun encode(prefs: MutablePreferences, entry: ProfileLookEntry?) {
            encodePart(prefs, AVATAR, entry?.avatar)
            encodePart(prefs, BACKGROUND, entry?.background)
        }

        private fun decodePart(prefs: Preferences, part: Part): ProfileLookChoice? {
            val pick = decodePick(prefs[part.pick]) ?: return null
            return ProfileLookChoice(pick, name = prefs[part.name], syncedKey = prefs[part.key])
        }

        private fun encodePart(prefs: MutablePreferences, part: Part, choice: ProfileLookChoice?) {
            if (choice == null) {
                prefs.remove(part.pick)
                prefs.remove(part.name)
                prefs.remove(part.key)
                return
            }
            prefs[part.pick] = encodePick(choice.pick)
            // An absent key reads back as null: the snapshot's null fields round-trip.
            putOrRemove(prefs, part.name, choice.name)
            putOrRemove(prefs, part.key, choice.syncedKey)
        }

        private fun putOrRemove(prefs: MutablePreferences, key: Preferences.Key<String>, value: String?) {
            if (value == null) prefs.remove(key) else prefs[key] = value
        }

        internal fun encodePick(pick: ProfileLookPick): String = when (pick) {
            is ProfileLookPick.Bundled -> "$BUNDLED${pick.slug}"
            is ProfileLookPick.Photo -> "$PHOTO${pick.id}"
        }

        /** Junk (an unknown kind or an empty value) reads as no pick. */
        internal fun decodePick(raw: String?): ProfileLookPick? = when {
            raw == null -> null
            raw.startsWith(BUNDLED) -> raw.removePrefix(BUNDLED).takeIf { it.isNotEmpty() }?.let(ProfileLookPick::Bundled)
            raw.startsWith(PHOTO) -> raw.removePrefix(PHOTO).takeIf { it.isNotEmpty() }?.let(ProfileLookPick::Photo)
            else -> null
        }

        private const val BUNDLED = "bundled:"
        private const val PHOTO = "photo:"
    }
}
