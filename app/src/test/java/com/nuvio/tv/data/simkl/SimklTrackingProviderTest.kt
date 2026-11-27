package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingProviderId
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106: Simkl's sign-in state follows the stored account and this TV's app id (K); its allowance box (E2/E5). */
@OptIn(ExperimentalCoroutinesApi::class)
class SimklTrackingProviderTest {

    private val accounts = accountStore()
    private val engine = FakeSimklEngine()
    private val clock = FakeClock()
    private val client = SimklClient(engine, TEST_CONFIG, monotonicMs = { clock.now }, sleep = clock.sleep)
    private val quota = SimklQuota { clock.now }
    private val session = SimklSession(client, SimklTokens(client, TEST_CONFIG, accounts), quota)
    private val deviceAuth = SimklDeviceAuth(client, TEST_CONFIG, accounts, session)

    private fun TestScope.store(scope: CoroutineScope) = SimklAppIdStore(
        Files.createTempDirectory("simkl").resolve("simkl_app_id").toFile(), UnconfinedTestDispatcher(testScheduler), scope
    )

    private fun TestScope.provider(store: SimklAppIdStore, active: MutableStateFlow<Int> = MutableStateFlow(1)) =
        SimklTrackingProvider(
            accounts, deviceAuth, SimklAppIdSetup(store, client, { listOf(1) }, { }, backgroundScope),
            SimklScrobbler(
                session,
                SimklHistoryWriter(session, SimklPendingMarks(Files.createTempDirectory("simkl").toFile(), Dispatchers.Unconfined)),
                active
            ),
            quota, active,
            backgroundScope
        ) { clock.now }

    @Test
    fun `Simkl reads, writes, scrobbles and keeps a library`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider(store(backgroundScope))
        assertEquals(
            setOf(
                TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE,
                TrackingCapability.PROGRESS_READ, TrackingCapability.PROGRESS_WRITE, TrackingCapability.WATCHED_READ,
                TrackingCapability.WATCHED_WRITE, TrackingCapability.SCROBBLE
            ),
            provider.descriptor.capabilities
        )
    }

    @Test
    fun `signed in needs the app id and the account, and a revoked grant reads signed out`() = runTest(UnconfinedTestDispatcher()) {
        val store = store(backgroundScope)
        val provider = provider(store)
        assertFalse(provider.isAuthenticated.value)

        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("simkl_at_1", "simkl_rt_1"))
        assertFalse("no app id on this TV yet", provider.isAuthenticated.value)

        store.save("client-v2-app-id")
        assertTrue(provider.isAuthenticated.value)

        accounts.update(1, TrackingProviderId.SIMKL) { it?.copy(revoked = true) }
        assertFalse(provider.isAuthenticated.value)

        accounts.update(1, TrackingProviderId.SIMKL) { it?.copy(revoked = false) }
        assertTrue(provider.isAuthenticated.value)
        store.save(null)
        assertFalse("an account kept past a removed app id is not usable", provider.isAuthenticated.value)
    }

    @Test
    fun `the allowance box is the active profile's quota, the reset the pause's end or the next New York midnight`() =
        runTest(UnconfinedTestDispatcher()) {
            val active = MutableStateFlow(1)
            val provider = provider(store(backgroundScope), active)
            assertNull(provider.accountStatus.first())

            quota.record(1, SimklResponse(200, "{}", mapOf("x-ratelimit-limit" to "500", "x-ratelimit-remaining" to "368")))
            val status = provider.accountStatus.first()!!
            assertEquals(500, status.limit)
            assertEquals(368, status.remaining)
            assertEquals(nextSimklResetEpochMs(clock.now), status.resetsAtEpochMs)
            assertEquals(SIMKL_USAGE_PAGE, status.usagePage)
            assertFalse(status.isPaused(clock.now))

            quota.recordFailure(1, SimklApiException(429, SIMKL_ERROR_USER_LIMIT, retryAfterSeconds = 3_600))
            val paused = provider.accountStatus.first()!!
            assertEquals(0, paused.remaining)
            assertEquals(clock.now + 3_600_000L, paused.pausedUntilEpochMs)
            assertEquals(paused.pausedUntilEpochMs, paused.resetsAtEpochMs)
            assertTrue(paused.isPaused(clock.now))

            active.value = 2
            assertNull("another profile's allowance is its own", provider.accountStatus.first())
        }
}
