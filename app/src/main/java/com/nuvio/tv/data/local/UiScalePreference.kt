package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uiScaleDataStore by preferencesDataStore(
    name = "ui_scale_prefs",
    corruptionHandler = loggedResetCorruptionHandler("ui_scale_prefs")
)

/**
 * App-wide UI scale (percent). Standalone store so the value is readable at the
 * composition root before the main settings graph is up. See upstream #2901.
 */
object UiScalePreference {
    private val key = intPreferencesKey("ui_scale_percent")
    const val MIN_PERCENT = 85
    const val MAX_PERCENT = 115

    fun flow(context: Context): Flow<Int> =
        context.uiScaleDataStore.data.map { prefs -> (prefs[key] ?: 100).coerceIn(MIN_PERCENT, MAX_PERCENT) }

    suspend fun set(context: Context, percent: Int) {
        context.uiScaleDataStore.edit { prefs -> prefs[key] = percent.coerceIn(MIN_PERCENT, MAX_PERCENT) }
    }
}
