package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.server.PasteKeyPageStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A tracker for the provider-layer tests (Spec 104 S1: no real provider exists until Specs 105/106). */
internal class FakeTrackingProvider(
    id: TrackingProviderId,
    connected: Boolean,
    capabilities: Set<TrackingCapability> = setOf(TrackingCapability.SCROBBLE),
    override val scrobbler: TrackingScrobbler? = FakeScrobbler(id),
    override val setup: TrackingProviderSetup? = null,
    val status: MutableStateFlow<TrackingAccountStatus?> = MutableStateFlow(null)
) : TrackingProvider {
    val authenticated = MutableStateFlow(connected)
    override val descriptor = TrackingProviderDescriptor(id, id.name, capabilities)
    override val isAuthenticated: StateFlow<Boolean> = authenticated
    override val deviceAuth: TrackingDeviceAuth = FakeDeviceAuth()
    override val accountStatus: Flow<TrackingAccountStatus?> = status
}

/** A per-TV setup (Spec 106 K): [submit] saves what it is given, [remove] forgets it. */
internal class FakeProviderSetup(hint: String? = null) : TrackingProviderSetup {
    val hint = MutableStateFlow(hint)
    val submitted = mutableListOf<String>()
    var removed = 0
    var removeFailure: Exception? = null
    var answer: PasteCheck = PasteCheck.Saved
    override val savedHint: StateFlow<String?> = this.hint
    override val text = TrackingSetupText(
        rowLine = 1, valueName = 2, steps = emptyList(), phoneTitle = 3, replaceTitle = 4, savedBody = 5, removeBody = 6,
        phonePage = PasteKeyPageStrings(7, 8, 9, 10, null, 11, 12, 13, 14, maxLength = 64)
    )
    override val phonePagePort: Int = 8160

    override suspend fun submit(value: String, stillWanted: () -> Boolean): PasteCheck {
        submitted += value
        if (answer == PasteCheck.Saved) hint.value = value.takeLast(4)
        return answer
    }

    override suspend fun remove() {
        removed += 1
        removeFailure?.let { throw it }
        hint.value = null
    }
}

/** Scripted sign-in: [polls] answer in order (the last repeats); [startFailure] makes start throw. */
internal class FakeDeviceAuth(
    var polls: MutableList<TrackingDevicePoll> = mutableListOf(TrackingDevicePoll.Pending),
    var startFailure: Exception? = null,
    var pollFailure: Exception? = null,
    var disconnectFailure: Exception? = null,
    private val intervalSeconds: Int = 5,
    private val lifetimeMs: Long = 600_000L
) : TrackingDeviceAuth {
    var started = 0
    val polled = mutableListOf<String>()
    /** The profile id of every start and poll, in order. */
    val profileIds = mutableListOf<Int>()
    val disconnected = mutableListOf<Int>()
    var nowMs: () -> Long = { 0L }

    override suspend fun start(profileId: Int): TrackingDeviceCode {
        profileIds += profileId
        startFailure?.let { throw it }
        started += 1
        return TrackingDeviceCode(
            deviceCode = "device-$started",
            userCode = "CODE$started",
            verificationUri = "https://example.test/pin",
            verificationUriComplete = "https://example.test/pin/CODE$started",
            expiresAtEpochMs = nowMs() + lifetimeMs,
            intervalSeconds = intervalSeconds
        )
    }

    override suspend fun poll(profileId: Int, code: TrackingDeviceCode): TrackingDevicePoll {
        profileIds += profileId
        pollFailure?.let { throw it }
        polled += code.userCode
        return if (polls.size > 1) polls.removeAt(0) else polls.first()
    }

    override suspend fun disconnect(profileId: Int) {
        disconnectFailure?.let { throw it }
        disconnected += profileId
    }
}

internal class FakeScrobbler(
    override val providerId: TrackingProviderId,
    override val seekScrobblePolicy: TrackingSeekScrobblePolicy = TrackingSeekScrobblePolicy.NONE,
    override val stopsWhenLeftPaused: Boolean = false,
    private val failure: Throwable? = null
) : TrackingScrobbler {
    val calls = mutableListOf<Pair<TrackingScrobbleAction, TrackingScrobbleEvent>>()

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        calls += action to event
        failure?.let { throw it }
    }
}

internal fun movieEvent(progress: Double = 40.0, skipped: Set<TrackingProviderId> = emptySet()) = TrackingScrobbleEvent(
    media = buildTrackingMediaReference(contentType = "movie", parentMetaId = "tt0133093", title = "The Matrix"),
    progressPercent = progress,
    skippedProviders = skipped
)
