package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.withJwtRefreshRetry
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseProfileLockState
import com.nuvio.tv.data.remote.supabase.SupabaseProfile
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.syncedAvatar
import com.nuvio.tv.domain.model.syncedBackground
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSyncService"
private const val PROFILE_PULL_MIN_INTERVAL_MS = 10_000L
private const val LOCK_STATE_REFRESH_ATTEMPTS = 4

internal data class ProfilePullFreshness(
    val userId: String? = null,
    val pulledAtMs: Long = 0L
) {
    fun isRecent(candidateUserId: String, nowMs: Long): Boolean {
        return userId == candidateUserId &&
            pulledAtMs > 0L &&
            nowMs >= pulledAtMs &&
            nowMs - pulledAtMs < PROFILE_PULL_MIN_INTERVAL_MS
    }
}

sealed class SetProfilePinResult {
    object Success : SetProfilePinResult()
    object CurrentPinRequired : SetProfilePinResult()
    object Failure : SetProfilePinResult()
}

@Singleton
class ProfileSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileDataStore: ProfileDataStore,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity,
    private val profileLockStateDataStore: ProfileLockStateDataStore,
    private val pushHealth: SyncPushHealth
) {
    private val pullMutex = Mutex()
    private var pullFreshness = ProfilePullFreshness()
    private var lastPulledProfiles = emptyList<UserProfile>()

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profiles = profileManager.profiles.value

            val params = buildJsonObject {
                put("p_client_max_profiles", ProfileManager.MAX_PROFILES)
                put("p_profiles", buildJsonArray {
                    profiles.forEach { profile ->
                        // The look key normalises with these same helpers (Spec 44), so a TV-only
                        // look compares against exactly what other devices receive.
                        val avatar = profile.syncedAvatar()
                        val background = profile.syncedBackground()
                        addJsonObject {
                            put("profile_index", profile.id)
                            put("name", profile.name)
                            put("avatar_color_hex", profile.avatarColorHex)
                            put("uses_primary_addons", profile.usesPrimaryAddons)
                            put("uses_primary_plugins", profile.usesPrimaryPlugins)
                            put("avatar_id", avatar.id)
                            put("avatar_url", avatar.url)
                            put("profile_background_id", background.id)
                            put("profile_background_url", background.url)
                        }
                    }
                })
                putSyncOriginClientId(syncClientIdentity)
            }
            authManager.withJwtRefreshRetry {
                postgrest.rpc("sync_push_profiles", params)
            }

            Log.d(TAG, "Pushed ${profiles.size} profiles to remote")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profiles to remote", e)
            Result.failure(e)
        }
    }.also { pushHealth.record(SyncDomain.PROFILES, profileId = null, it) }

    suspend fun pullFromRemote(force: Boolean = false): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        pullMutex.withLock {
            val userId = (authManager.authState.value as? AuthState.FullAccount)?.userId
            val now = SystemClock.elapsedRealtime()
            if (!force && userId != null && pullFreshness.isRecent(userId, now)) {
                return@withLock Result.success(lastPulledProfiles)
            }
            try {
                val response = authManager.withJwtRefreshRetry {
                    postgrest.rpc("sync_pull_profiles")
                }
                val remote = response.decodeList<SupabaseProfile>()

                Log.d(TAG, "pullFromRemote: fetched ${remote.size} profiles from Supabase")

                val profiles = remote.map { entry ->
                    UserProfile(
                        id = entry.profileIndex,
                        name = entry.name,
                        avatarColorHex = entry.avatarColorHex,
                        usesPrimaryAddons = entry.usesPrimaryAddons,
                        usesPrimaryPlugins = entry.usesPrimaryPlugins,
                        avatarId = entry.avatarId,
                        avatarUrl = entry.avatarUrl,
                        profileBackgroundId = entry.profileBackgroundId,
                        profileBackgroundUrl = entry.profileBackgroundUrl
                    )
                }

                if (profiles.isNotEmpty()) {
                    profileDataStore.replaceAllProfiles(profiles)
                    Log.d(TAG, "Merged ${profiles.size} remote profiles into local store")
                }

                val currentUserId = (authManager.authState.value as? AuthState.FullAccount)?.userId
                if (userId != null && currentUserId == userId) {
                    lastPulledProfiles = profiles
                    pullFreshness = ProfilePullFreshness(
                        userId = userId,
                        pulledAtMs = SystemClock.elapsedRealtime()
                    )
                }
                Result.success(profiles)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull profiles from remote", e)
                Result.failure(e)
            }
        }
    }

    suspend fun deleteProfileData(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        pushHealth.forgetProfile(profileId)
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                putSyncOriginClientId(syncClientIdentity)
            }
            authManager.withJwtRefreshRetry {
                postgrest.rpc("sync_delete_profile_data", params)
            }

            Log.d(TAG, "Deleted remote data for profile $profileId")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote profile data for profile $profileId", e)
            Result.failure(e)
        }
    }

    suspend fun pullProfileLockStates(): Result<Map<Int, Boolean>> = withContext(Dispatchers.IO) {
        try {
            val response = authManager.withJwtRefreshRetry {
                postgrest.rpc("sync_pull_profile_locks")
            }
            val remote = response.decodeList<SupabaseProfileLockState>()
            val result = remote.associate { it.profileIndex to it.pinEnabled }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profile lock states", e)
            Result.failure(e)
        }
    }

    /**
     * Pulls the profile lock states into [ProfileLockStateDataStore], up to
     * [LOCK_STATE_REFRESH_ATTEMPTS] tries with a growing backoff; a failed pull keeps the
     * persisted map. The one refresh for MainActivity's PIN gate and the profile screen
     * (L5-c).
     */
    suspend fun refreshProfileLockStates() {
        for (attempt in 1..LOCK_STATE_REFRESH_ATTEMPTS) {
            val result = profileLockStateDataStore.refreshPinEnabled { pullProfileLockStates() }
            if (result.isSuccess) return
            Log.e(TAG, "Failed to refresh profile PIN states (attempt=$attempt)", result.exceptionOrNull())
            if (attempt < LOCK_STATE_REFRESH_ATTEMPTS) delay(2000L * attempt)
        }
    }

    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String? = null): SetProfilePinResult = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            authManager.withJwtRefreshRetry {
                postgrest.rpc("set_profile_pin", params)
            }
            SetProfilePinResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set profile PIN", e)
            if (isCurrentPinRequiredError(e)) {
                SetProfilePinResult.CurrentPinRequired
            } else {
                SetProfilePinResult.Failure
            }
        }
    }

    private fun isCurrentPinRequiredError(e: Throwable): Boolean =
        e.message?.contains("Current PIN is required", ignoreCase = true) == true

    suspend fun clearProfilePin(profileId: Int, currentPin: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            authManager.withJwtRefreshRetry {
                postgrest.rpc("clear_profile_pin", params)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear profile PIN", e)
            Result.failure(e)
        }
    }

    suspend fun verifyProfilePin(profileId: Int, pin: String): Result<SupabaseProfilePinVerifyResult> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
            }
            val response = authManager.withJwtRefreshRetry {
                postgrest.rpc("verify_profile_pin", params)
            }
            val decoded = response.decodeList<SupabaseProfilePinVerifyResult>().firstOrNull()
                ?: SupabaseProfilePinVerifyResult(unlocked = false, retryAfterSeconds = 0)
            Result.success(decoded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify profile PIN", e)
            Result.failure(e)
        }
    }
}
