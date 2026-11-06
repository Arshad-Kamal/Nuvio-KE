package com.nuvio.tv.ui.screens.account

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.markerText
import com.nuvio.tv.core.auth.nameForLog
import com.nuvio.tv.core.logging.diagnosticSummary
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.SyncStatus
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

private const val TAG = "AccountViewModel"
private val qrLoginTraceCounter = AtomicLong(0L)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    private val serverConfiguration: ServerConfiguration,
    private val startupSyncService: StartupSyncService,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()
    private var qrLoginPollJob: Job? = null
    private var activeQrLoginTraceId: Long? = null
    private var qrLoginPollAttempt: Int = 0

    val usesEmailPasswordLogin: Boolean
        get() = serverConfiguration.isCustom && serverConfiguration.capabilities.emailPasswordAuth

    /** The Account pane's sync line; null until the stored last-sync time loads. */
    val syncStatus: StateFlow<SyncStatus?> = startupSyncService.syncStatus

    /** The account block's avatar (the active profile's, as in the top bar). */
    val activeProfile: StateFlow<UserProfile?> =
        combine(profileManager.profiles, profileManager.activeProfileId) { profiles, id ->
            profiles.firstOrNull { it.id == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), profileManager.activeProfile)

    init {
        observeAuthState()
        observeProfileNames()
        refreshOverviewAfterSync()
    }

    /** Sync now: returns whether a pull started; see [StartupSyncService.requestSyncNow]. */
    fun syncNow(): Boolean = startupSyncService.requestSyncNow()

    /** The synced-data table reads server counts: re-read them when a pull finishes. */
    private fun refreshOverviewAfterSync() {
        viewModelScope.launch {
            syncStatus.filterNotNull()
                .map { it.running }
                .distinctUntilChanged()
                .drop(1)
                .filter { running -> !running }
                .collect {
                    if (_uiState.value.authState is AuthState.FullAccount) loadSyncOverview()
                }
        }
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                _uiState.update {
                    it.copy(
                        authState = state,
                        // The next account must never see this one's counts, even if its read fails.
                        syncOverview = if (state is AuthState.FullAccount) it.syncOverview else null
                    )
                }
                if (state is AuthState.FullAccount) loadSyncOverview()
            }
        }
    }

    private fun observeProfileNames() {
        viewModelScope.launch {
            profileManager.profiles.collect { profiles ->
                _uiState.update { state ->
                    val current = state.syncOverview ?: return@update state
                    state.copy(
                        syncOverview = current.map { stat ->
                            profiles.firstOrNull { it.id == stat.profileId }?.let(stat::withLocal) ?: stat
                        }
                    )
                }
            }
        }
    }

    // Sign-in paths do not pull remote data: StartupSyncService schedules the pull on every
    // AuthState.FullAccount transition, and a second pull here raced it on the shared
    // isSyncingFromRemote guards (L5-28/42).
    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authManager.signInWithEmail(email, password).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
        }
    }

    /** Starts a fresh QR session (also the Refresh button); ignored while a request is in flight. */
    fun startQrLogin() {
        dispatchQr(QrEvent.Refresh(generateDeviceNonce()))
    }

    fun clearQrLoginSession() {
        Log.d(TAG, "QR_LOGIN[${activeQrLoginTraceId ?: "-"}] clearing session state=${_uiState.value.qrLogin::class.simpleName}")
        dispatchQr(QrEvent.Clear)
        activeQrLoginTraceId = null
    }

    /** Runs [event] through [reduceQrLogin], stores the result, then runs its effects. */
    private fun dispatchQr(event: QrEvent, startedBitmap: Bitmap? = null) {
        var effects: List<QrEffect> = emptyList()
        _uiState.update { state ->
            val (next, nextEffects) = reduceQrLogin(state.qrLogin, event)
            effects = nextEffects
            val bitmap = when {
                event is QrEvent.Started && state.qrLogin is QrLogin.Starting && next is QrLogin.Showing -> startedBitmap
                next is QrLogin.Idle || next is QrLogin.Starting -> null
                else -> state.qrLoginBitmap
            }
            state.copy(qrLogin = next, qrLoginBitmap = bitmap)
        }
        effects.forEach(::runQrEffect)
    }

    private fun runQrEffect(effect: QrEffect) {
        if (effect is QrEffect.StartSession) {
            // Per-session bookkeeping only once the reducer has accepted a new session.
            activeQrLoginTraceId = qrLoginTraceCounter.incrementAndGet()
            qrLoginPollAttempt = 0
            Log.d(
                TAG,
                "QR_LOGIN[$activeQrLoginTraceId] start requested auth=${_uiState.value.authState.nameForLog()} model=${Build.MODEL} redirectConfigured=${!serverConfiguration.tvLoginWebBaseUrl.isNullOrBlank()} customServer=${serverConfiguration.isCustom} anonKeyConfigured=${serverConfiguration.publishableKey.isNotBlank()}"
            )
        }
        val traceId = activeQrLoginTraceId
        when (effect) {
            QrEffect.CancelPoll -> {
                qrLoginPollJob?.cancel()
                qrLoginPollJob = null
            }
            is QrEffect.StartSession -> viewModelScope.launch { startQrSession(effect.nonce, traceId) }
            is QrEffect.SchedulePoll -> {
                // Usually runs from inside the current poll job (its result scheduled the next
                // poll), so this cancels that job: safe only while dispatchQr stays the last step
                // of pollQrLoginOnce. The new job is a sibling in viewModelScope, not a child.
                qrLoginPollJob?.cancel()
                qrLoginPollJob = viewModelScope.launch {
                    delay(effect.delayMs)
                    pollQrLoginOnce(effect.code, effect.nonce, traceId)
                }
            }
            is QrEffect.Exchange -> viewModelScope.launch { exchangeQrLogin(effect.code, effect.nonce, traceId) }
        }
    }

    private suspend fun startQrSession(nonce: String, traceId: Long?) {
        val startedAtMs = SystemClock.elapsedRealtime()
        authManager.startTvLoginSession(
            deviceNonce = nonce,
            deviceName = Build.MODEL,
            redirectBaseUrl = serverConfiguration.tvLoginWebBaseUrl.orEmpty(),
            traceId = traceId
        ).fold(
            onSuccess = { result ->
                val expiresAtMillis = runCatching { Instant.parse(result.expiresAt).toEpochMilli() }.getOrNull()
                if (result.code.isBlank() || result.webUrl.isBlank()) {
                    Log.w(TAG, "QR_LOGIN[${traceId ?: "-"}] start_tv_login_session returned incomplete data codeBlank=${result.code.isBlank()} urlBlank=${result.webUrl.isBlank()}")
                }
                val qrBitmap = runCatching { QrCodeGenerator.generate(result.webUrl, 420, margin = 1) }
                    .onFailure { e ->
                        Log.e(TAG, "QR_LOGIN[${traceId ?: "-"}] QR bitmap generation failed urlLength=${result.webUrl.length} error=${e.diagnosticSummary()}", e)
                    }
                    .getOrNull()
                Log.d(
                    TAG,
                    "QR_LOGIN[${traceId ?: "-"}] start_tv_login_session ok elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} urlLength=${result.webUrl.length} expiresAt=${result.expiresAt} pollInterval=${result.pollIntervalSeconds} qrBitmap=${qrBitmap != null}"
                )
                dispatchQr(
                    QrEvent.Started(nonce, result.code, expiresAtMillis, result.pollIntervalSeconds),
                    startedBitmap = qrBitmap
                )
            },
            onFailure = { e ->
                Log.e(TAG, "QR_LOGIN[${traceId ?: "-"}] start_tv_login_session failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
                dispatchQr(QrEvent.StartFailed(nonce, userFriendlyError(e)))
            }
        )
    }

    private suspend fun exchangeQrLogin(code: String, nonce: String, traceId: Long?) {
        val startedAtMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "QR_LOGIN[${traceId ?: "-"}] exchange begin auth=${_uiState.value.authState.nameForLog()}")
        authManager.exchangeTvLoginSession(code = code, deviceNonce = nonce, traceId = traceId).fold(
            onSuccess = {
                Log.d(TAG, "QR_LOGIN[${traceId ?: "-"}] exchange ok elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}")
                dispatchQr(QrEvent.ExchangeOk(code))
            },
            onFailure = { e ->
                Log.e(TAG, "QR_LOGIN[${traceId ?: "-"}] exchange failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
                dispatchQr(QrEvent.ExchangeFailed(code, userFriendlyError(e)))
            }
        )
    }

    // `plugins` stays in the response shape the server sends; it is decoded
    // but no longer shown (the in-app plugin feature was removed).
    @Serializable
    private data class SyncOverviewResponse(
        val addons: Map<String, Int> = emptyMap(),
        val plugins: Map<String, Int> = emptyMap(),
        @SerialName("library_items") val libraryItems: Map<String, Int> = emptyMap(),
        @SerialName("watch_progress") val watchProgress: Map<String, Int> = emptyMap(),
        @SerialName("watched_items") val watchedItems: Map<String, Int> = emptyMap(),
        val profiles: Map<String, ProfileInfo> = emptyMap()
    ) {
        @Serializable
        data class ProfileInfo(
            val name: String,
            val color: String
        )
    }

    fun loadSyncOverview() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncOverviewLoading = true) }

            val overview = runCatching {
                val response = postgrest.rpc("get_sync_overview")
                    .decodeAs<SyncOverviewResponse>()

                val allProfileIds = (response.addons.keys +
                    response.libraryItems.keys + response.watchProgress.keys +
                    response.watchedItems.keys + response.profiles.keys)
                    .mapNotNull { it.toIntOrNull() }
                    .distinct()
                    .sorted()

                val localProfiles = profileManager.profiles.value
                allProfileIds.map { pid ->
                    val pidStr = pid.toString()
                    val local = localProfiles.firstOrNull { it.id == pid }
                    val remote = response.profiles[pidStr]
                    val stats = ProfileSyncStats(
                        profileId = pid,
                        profileName = remote?.name
                            ?: context.getString(com.nuvio.tv.R.string.profile_default_name, pid),
                        avatarColorHex = remote?.color ?: "#1E88E5",
                        addons = response.addons[pidStr] ?: 0,
                        library = response.libraryItems[pidStr] ?: 0,
                        watchProgress = response.watchProgress[pidStr] ?: 0,
                        watchedItems = response.watchedItems[pidStr] ?: 0
                    )
                    local?.let(stats::withLocal) ?: stats
                }
            }.getOrNull()

            _uiState.update {
                it.copy(
                    syncOverview = overview ?: it.syncOverview,
                    isSyncOverviewLoading = false
                )
            }
        }
    }

    private fun userFriendlyError(e: Throwable): String {
        // Log the message only: markerText() adds the server body, which can carry credentials.
        Log.w(TAG, "Raw error: ${e.message.orEmpty()}")
        return context.getString(accountErrorMessageResId(e.markerText().orEmpty().lowercase()))
    }

    private fun generateDeviceNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private suspend fun pollQrLoginOnce(code: String, nonce: String, traceId: Long?) {
        val attempt = ++qrLoginPollAttempt
        val startedAtMs = SystemClock.elapsedRealtime()
        authManager.pollTvLoginSession(code = code, deviceNonce = nonce, traceId = traceId, attempt = attempt).fold(
            onSuccess = { result ->
                val expiresAtMillis = result.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                Log.d(TAG, "QR_LOGIN[${traceId ?: "-"}] poll attempt=$attempt ok status=${result.status} expiresAt=${result.expiresAt ?: "-"} nextInterval=${result.pollIntervalSeconds ?: "-"} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}")
                // Keep dispatchQr the last step: its SchedulePoll cancels this job (runQrEffect).
                dispatchQr(QrEvent.Polled(code, result.status, expiresAtMillis, result.pollIntervalSeconds))
            },
            onFailure = { e ->
                Log.e(TAG, "QR_LOGIN[${traceId ?: "-"}] poll attempt=$attempt failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
                dispatchQr(QrEvent.PollFailed(code, userFriendlyError(e)))
            }
        )
    }

    override fun onCleared() {
        qrLoginPollJob?.cancel()
        super.onCleared()
    }
}

/**
 * Maps a lowercased auth/network error message to its user-facing string resource.
 * Specific QR-login failures are matched before the generic `tv login` branches:
 * "invalid tv login redirect base url" also contains "tv login" + "invalid" and
 * would otherwise be swallowed by the generic invalid-QR branch.
 */
internal fun accountErrorMessageResId(message: String): Int = when {
    message.contains("empty response") -> R.string.account_error_generic_retry

    // Auth errors
    message.contains("invalid login credentials") -> R.string.account_error_invalid_credentials
    message.contains("email not confirmed") -> R.string.account_error_email_not_confirmed
    message.contains("user already registered") -> R.string.account_error_email_already_registered
    message.contains("invalid email") -> R.string.account_error_invalid_email
    message.contains("password") && message.contains("short") -> R.string.account_error_password_too_short
    message.contains("password") && message.contains("weak") -> R.string.account_error_password_too_weak
    message.contains("signup is disabled") -> R.string.account_error_signup_disabled
    message.contains("rate limit") || message.contains("too many requests") -> R.string.account_error_rate_limited

    // QR login: specific failures first, then the generic `tv login` fallbacks
    message.contains("invalid tv login redirect base url") ->
        R.string.account_error_qr_login_misconfigured
    message.contains("invalid device nonce") ->
        R.string.account_error_qr_login_invalid_request
    message.contains("start_tv_login_session") && message.contains("could not find the function") ->
        R.string.account_error_qr_login_outdated
    message.contains("gen_random_bytes") && message.contains("does not exist") ->
        R.string.account_error_qr_login_missing_setup
    message.contains("tv login") && message.contains("expired") -> R.string.account_error_qr_login_expired
    message.contains("tv login") && message.contains("invalid") -> R.string.account_error_invalid_qr_login
    message.contains("tv login") && message.contains("nonce") -> R.string.account_error_qr_login_other_device

    // Network errors
    message.contains("unable to resolve host") || message.contains("no address associated") -> R.string.account_error_no_internet
    message.contains("timeout") || message.contains("timed out") -> R.string.account_error_connection_timeout
    message.contains("connection refused") || message.contains("connect failed") -> R.string.account_error_connection_refused

    // Auth state
    message.contains("not authenticated") -> R.string.account_error_not_authenticated

    // Supabase HTTP errors (e.g. 404 for missing RPC, 400 for bad params)
    message.contains("404") || message.contains("could not find") -> R.string.account_error_service_unavailable
    message.contains("400") || message.contains("bad request") -> R.string.account_error_invalid_request

    // Fallback
    else -> R.string.account_error_unexpected
}
