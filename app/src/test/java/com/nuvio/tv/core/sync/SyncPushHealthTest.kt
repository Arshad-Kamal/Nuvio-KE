package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPushHealthTest {

    private val failure = Result.failure<Unit>(IllegalStateException("offline"))
    private val success = Result.success(Unit)

    @Test
    fun `a failed push counts once per domain until that domain pushes successfully`() {
        val health = SyncPushHealth()
        health.record(SyncDomain.ADDONS, 1, failure)
        health.record(SyncDomain.ADDONS, 1, failure)
        health.record(SyncDomain.COLLECTIONS, 1, failure)
        assertEquals(2, pendingChanges(health.state.value, activeProfileId = 1))

        health.record(SyncDomain.ADDONS, 1, success)
        assertEquals(1, pendingChanges(health.state.value, activeProfileId = 1))
    }

    @Test
    fun `failures count for their own profile, account-wide ones for every profile`() {
        val health = SyncPushHealth()
        health.record(SyncDomain.WATCH_PROGRESS, 2, failure)
        health.record(SyncDomain.PROFILES, null, failure)
        assertEquals(1, pendingChanges(health.state.value, activeProfileId = 1))
        assertEquals(2, pendingChanges(health.state.value, activeProfileId = 2))
    }

    @Test
    fun `library queue adds its item count for the active profile`() {
        val health = SyncPushHealth()
        health.recordLibraryQueued(profileId = 1, queued = 3)
        health.recordLibraryQueued(profileId = 2, queued = 7)
        health.record(SyncDomain.WATCHED_ITEMS, 1, failure)
        assertEquals(4, pendingChanges(health.state.value, activeProfileId = 1))

        health.recordLibraryQueued(profileId = 1, queued = 0)
        assertEquals(1, pendingChanges(health.state.value, activeProfileId = 1))
    }

    @Test
    fun `forgetting a deleted profile keeps other profiles and account-wide failures`() {
        val health = SyncPushHealth()
        health.record(SyncDomain.ADDONS, 3, failure)
        health.record(SyncDomain.ADDONS, 1, failure)
        health.record(SyncDomain.PROFILES, null, failure)
        health.recordLibraryQueued(profileId = 3, queued = 4)

        health.forgetProfile(3)

        assertEquals(1, pendingChanges(health.state.value, activeProfileId = 3))
        assertEquals(2, pendingChanges(health.state.value, activeProfileId = 1))
    }

    @Test
    fun `clear forgets everything`() {
        val health = SyncPushHealth()
        health.record(SyncDomain.HOME_CATALOG, 1, failure)
        health.recordLibraryQueued(profileId = 1, queued = 5)
        health.clear()
        assertEquals(PushHealthState(), health.state.value)
    }
}
