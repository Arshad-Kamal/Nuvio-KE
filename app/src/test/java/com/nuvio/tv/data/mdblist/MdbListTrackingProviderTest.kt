package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingAccountStatus
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.disconnect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S1b: MDBList in the tracker registry: its key sign-in state and its A2 box (allowance and plan). */
@OptIn(ExperimentalCoroutinesApi::class)
class MdbListTrackingProviderTest {

    private val engine = FakeMdbListEngine()
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val quota = MdbListQuota { clock.now }
    private val client = mdbListClient(engine, clock)
    private val session = MdbListSession(client, accounts, quota)
    private val keyAuth = MdbListKeyAuth(client, session, accounts)
    private val key = "abc123def456ghi789jkl0mno"
    private val userBody = """{"username":"arshadk","plan":"free"}"""
    private val quotaHeaders = mapOf("X-RateLimit-Limit" to "1000", "X-RateLimit-Remaining" to "988")

    private fun TestScope.provider(active: MutableStateFlow<Int> = MutableStateFlow(1)) = MdbListTrackingProvider(
        accounts, keyAuth, MdbListScrobbler(session, TEST_CONFIG, active, IDENTITY_NUMBERING), quota, active, backgroundScope
    ) { clock.now }

    @Test
    fun `MDBList reads and writes its library, progress and watched marks and scrobbles, with no setup and a key sign-in`() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = provider()
            assertEquals(
                setOf(
                    TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE,
                    TrackingCapability.PROGRESS_READ, TrackingCapability.PROGRESS_WRITE, TrackingCapability.WATCHED_READ,
                    TrackingCapability.WATCHED_WRITE, TrackingCapability.SCROBBLE
                ),
                provider.descriptor.capabilities
            )
            assertEquals(TrackingProviderId.MDBLIST, provider.descriptor.id)
            assertNull(provider.setup)
            assertNull(provider.deviceAuth)
            assertTrue(provider.keyAuth === keyAuth)
        }

    @Test
    fun `signed in follows the stored key, and a key MDBList refused reads signed out`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider()
        assertFalse(provider.isAuthenticated.value)
        engine.reply(200, userBody, quotaHeaders)
        keyAuth.connect(1, key) { true }
        assertTrue(provider.isAuthenticated.value)

        accounts.update(1, TrackingProviderId.MDBLIST) { it?.copy(revoked = true) }
        assertFalse(provider.isAuthenticated.value)

        accounts.update(1, TrackingProviderId.MDBLIST) { it?.copy(revoked = false) }
        provider.disconnect(1)
        assertFalse(provider.isAuthenticated.value)
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))
    }

    @Test
    fun `the box shows the active profile's numbers and plan, read once when unknown`() = runTest(UnconfinedTestDispatcher()) {
        val active = MutableStateFlow(1)
        val provider = provider(active)
        assertNull("not connected: nothing to show, nothing sent", provider.accountStatus.first())
        assertTrue(engine.calls.isEmpty())

        // After a restart the quota is unknown: the panel's first look reads /user once.
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key, accountName = "arshadk"))
        engine.reply(200, userBody, quotaHeaders)
        provider.accountStatus.first()
        val status = provider.accountStatus.first()
        assertEquals(1, engine.calls.size)
        assertEquals("/user", engine.calls.single().path)
        assertEquals(
            TrackingAccountStatus(
                limit = 1000, remaining = 988, resetsAtEpochMs = nextMdbListResetEpochMs(clock.now), plan = "free"
            ),
            status
        )

        active.value = 2
        assertNull("another profile's box is its own", provider.accountStatus.first())
    }

    @Test
    fun `a profile that becomes active while the box is shown has its plan read`() = runTest(UnconfinedTestDispatcher()) {
        val active = MutableStateFlow(1)
        val provider = provider(active)
        val seen = mutableListOf<TrackingAccountStatus?>()
        backgroundScope.launch { provider.accountStatus.collect { seen += it } }
        assertTrue("profile 1 has no key: nothing sent", engine.calls.isEmpty())

        accounts.save(2, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key))
        engine.reply(200, userBody, quotaHeaders)
        active.value = 2
        assertEquals(1, engine.calls.size)
        assertEquals("free", seen.last()?.plan)
    }

    @Test
    fun `while paused the reset is the pause's end and nothing is sent for the plan`() = runTest(UnconfinedTestDispatcher()) {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key))
        quota.recordFailure(1, MdbListApiException(429, dailyLimit = true))
        val provider = provider()
        val status = provider.accountStatus.first()!!
        assertTrue(engine.calls.isEmpty())
        assertEquals(status.pausedUntilEpochMs, status.resetsAtEpochMs)
        assertTrue(status.isPaused(clock.now))
        assertEquals(0, status.remaining)
    }
}
