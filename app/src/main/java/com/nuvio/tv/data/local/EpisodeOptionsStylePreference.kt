package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.episodeOptionsDataStore by preferencesDataStore(
    name = "episode_options_prefs",
    corruptionHandler = loggedResetCorruptionHandler("episode_options_prefs")
)

/** How the episode options menu renders on detail screens. RICH is the full-bleed
 *  backdrop overlay; CLASSIC is the pre-existing NuvioDialog. */
enum class EpisodeOptionsStyle { CLASSIC, RICH }

/**
 * Standalone device-level store (same shape as [UiScalePreference]) so the leaf
 * composable that owns the episode menu can read the style without ViewModel
 * plumbing. Deliberately not profile-scoped: it is a presentation toggle.
 */
object EpisodeOptionsStylePreference {
    private val key = stringPreferencesKey("episode_options_style")

    fun flow(context: Context): Flow<EpisodeOptionsStyle> =
        context.episodeOptionsDataStore.data.map { prefs ->
            val raw = prefs[key]
            if (raw == null) {
                EpisodeOptionsStyle.RICH
            } else {
                runCatching { EpisodeOptionsStyle.valueOf(raw) }.getOrDefault(EpisodeOptionsStyle.RICH)
            }
        }

    suspend fun set(context: Context, style: EpisodeOptionsStyle) {
        context.episodeOptionsDataStore.edit { prefs -> prefs[key] = style.name }
    }
}
