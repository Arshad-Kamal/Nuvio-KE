package com.nuvio.tv.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Push domains whose last failed push counts as one "change waiting". Library counts its durable queue instead. */
enum class SyncDomain { PROFILES, PROFILE_SETTINGS, ADDONS, COLLECTIONS, HOME_CATALOG, WATCHED_ITEMS, WATCH_PROGRESS }

/** [profileId] null = account-wide (the profiles list). */
data class FailedPush(val domain: SyncDomain, val profileId: Int?)

data class PushHealthState(
    val failed: Set<FailedPush> = emptySet(),
    /** Library mutations still queued per profile, as read after that profile's last library push. */
    val libraryQueued: Map<Int, Int> = emptyMap()
)

/**
 * "Changes waiting" for the active profile, v1 (Spec 31 defaults, as narrowed in S7e2): the
 * library's queued mutations plus one per domain whose last push failed (any success, even a
 * no-op, clears it). In memory: a restart forgets failed pushes until the next one fails again;
 * the library queue is re-read on its next push or sync.
 */
internal fun pendingChanges(state: PushHealthState, activeProfileId: Int): Int =
    (state.libraryQueued[activeProfileId] ?: 0) +
        state.failed.count { it.profileId == null || it.profileId == activeProfileId }

/** Push outcomes reported by the sync services; local-only, cleared on sign-out. */
@Singleton
class SyncPushHealth @Inject constructor() {
    private val _state = MutableStateFlow(PushHealthState())
    val state: StateFlow<PushHealthState> = _state.asStateFlow()

    /** Records [result] as [domain]'s last push for [profileId]. */
    fun record(domain: SyncDomain, profileId: Int?, result: Result<*>) {
        val key = FailedPush(domain, profileId)
        _state.update { current ->
            current.copy(failed = if (result.isSuccess) current.failed - key else current.failed + key)
        }
    }

    fun recordLibraryQueued(profileId: Int, queued: Int) {
        _state.update { it.copy(libraryQueued = it.libraryQueued + (profileId to queued)) }
    }

    /** A deleted profile's id is reused by the next new profile: drop what it left behind. */
    fun forgetProfile(profileId: Int) {
        _state.update { current ->
            current.copy(
                failed = current.failed.filterTo(mutableSetOf()) { it.profileId != profileId },
                libraryQueued = current.libraryQueued - profileId
            )
        }
    }

    fun clear() {
        _state.value = PushHealthState()
    }
}
