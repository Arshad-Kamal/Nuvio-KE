package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences

/**
 * One home for standalone (non-profile-scoped) DataStore corruption handling (W4.1):
 * log, then reset (to empty unless [resetTo] says otherwise). The log matters — on a successful reset DataStore swallows
 * the original exception, so this line is the only trace a settings wipe happened.
 * [resetTo] lets a store reset to marker entries instead of nothing (the device player store
 * resets as already seeded, so it never re-copies stale profile values).
 *
 * Not for profile-scoped stores: ProfileDataStoreFactory installs the richer
 * shadow-copy-recovery handler for those. Must not call DataStore APIs (deadlock).
 */
internal fun loggedResetCorruptionHandler(
    storeName: String,
    resetTo: Preferences = emptyPreferences()
): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { ex ->
        Log.e(
            TAG,
            "Standalone DataStore corrupted ($storeName): ${ex.message} — resetting its preferences"
        )
        resetTo
    }

private const val TAG = "DataStoreCorruption"
