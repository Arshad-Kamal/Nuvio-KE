package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authSessionNoticeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_session_notice_store",
    corruptionHandler = loggedResetCorruptionHandler("auth_session_notice_store")
)

enum class StartupAuthNotice {
    NUVIO
}

@Singleton
class AuthSessionNoticeDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val hadNuvioAuthKey = booleanPreferencesKey("had_nuvio_auth")
    private val nuvioExplicitLogoutKey = booleanPreferencesKey("nuvio_explicit_logout")
    private val pendingNuvioNoticeKey = booleanPreferencesKey("pending_nuvio_notice")

    val pendingNotice: Flow<StartupAuthNotice?> = context.authSessionNoticeDataStore.data.map { preferences ->
        when {
            preferences[pendingNuvioNoticeKey] == true -> StartupAuthNotice.NUVIO
            else -> null
        }
    }

    suspend fun markNuvioAuthenticated() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadNuvioAuthKey] = true
            preferences[nuvioExplicitLogoutKey] = false
            preferences[pendingNuvioNoticeKey] = false
        }
    }

    suspend fun markNuvioExplicitLogout() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadNuvioAuthKey] = false
            preferences[nuvioExplicitLogoutKey] = true
            preferences[pendingNuvioNoticeKey] = false
        }
    }

    suspend fun markUnexpectedNuvioLogoutIfNeeded(): Boolean {
        var marked = false
        context.authSessionNoticeDataStore.edit { preferences ->
            val hadAuth = preferences[hadNuvioAuthKey] == true
            val explicitLogout = preferences[nuvioExplicitLogoutKey] == true
            if (hadAuth && !explicitLogout) {
                preferences[pendingNuvioNoticeKey] = true
                marked = true
            }
            preferences[hadNuvioAuthKey] = false
            preferences[nuvioExplicitLogoutKey] = false
        }
        return marked
    }

    suspend fun consumeNotice(notice: StartupAuthNotice) {
        context.authSessionNoticeDataStore.edit { preferences ->
            when (notice) {
                StartupAuthNotice.NUVIO -> preferences[pendingNuvioNoticeKey] = false
            }
        }
    }
}
