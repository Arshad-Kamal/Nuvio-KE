package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingDeviceCode
import com.nuvio.tv.core.tracking.TrackingDevicePoll
import com.nuvio.tv.core.tracking.TrackingProviderId
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 S1a: AUTH V2 device sign-in, token refresh and revoke, signed-in calls. */
class SimklAuthTest {

    private val engine = FakeSimklEngine()
    private val clock = FakeClock()
    private val client = simklClient(engine, clock)
    private val accounts = accountStore()
    private val tokens = SimklTokens(client, TEST_CONFIG, accounts) { clock.now }
    private val quota = SimklQuota { clock.now }
    private val session = SimklSession(client, tokens, quota)
    private val auth = SimklDeviceAuth(client, TEST_CONFIG, accounts, session) { clock.now }

    private val day = 24L * 60 * 60 * 1_000
    private val code = TrackingDeviceCode("dev-secret", "BDWP-HQPK", "https://simkl.com/pin", null, 0L, 5)
    private val tokenReply = """{"access_token":"simkl_at_new","token_type":"Bearer","expires_in":604800,
        "refresh_token":"simkl_rt_1","scope":"media:read media:write"}"""

    private suspend fun signedIn(expiresIn: Long = 7 * day, access: String = "simkl_at_old") =
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount(access, "simkl_rt_1", clock.now + expiresIn, "arshad.k"))

    private suspend fun stored() = accounts.get(1, TrackingProviderId.SIMKL)

    private fun SentCall.json(): JSONObject = JSONObject(body!!)

    @Test
    fun `start asks for read and write and maps Simkl's code`() = runTest {
        engine.reply(200, """{"device_code":"dev-secret","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
            "verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}""")
        val started = auth.start(1)

        val sent = engine.calls.single()
        assertEquals("/oauth2/device", sent.path)
        assertEquals("media:read media:write", sent.json().getString("scope"))
        assertEquals("client-v2", sent.json().getString("client_id"))
        assertNull(sent.headers["Authorization"])
        assertEquals("BDWP-HQPK", started.userCode)
        assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", started.verificationUriComplete)
        assertEquals(clock.now + 900_000L, started.expiresAtEpochMs)
        assertEquals(5, started.intervalSeconds)
    }

    @Test
    fun `poll maps pending, slow down and expiry, and throws on anything else`() = runTest {
        engine.reply(400, """{"error":"authorization_pending"}""")
        engine.reply(400, """{"error":"slow_down"}""")
        engine.reply(400, """{"error":"expired_token"}""")
        engine.reply(401, """{"error":"invalid_client"}""")
        assertEquals(TrackingDevicePoll.Pending, auth.poll(1, code))
        assertEquals(TrackingDevicePoll.SlowDown, auth.poll(1, code))
        assertEquals(TrackingDevicePoll.Expired, auth.poll(1, code))
        assertEquals(401, assertFailsSuspend<SimklApiException> { auth.poll(1, code) }.status)
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", engine.calls.first().json().getString("grant_type"))
        assertEquals("dev-secret", engine.calls.first().json().getString("device_code"))
        assertNull(stored())
    }

    @Test
    fun `approval saves the account on the sign-in's profile with its Simkl name`() = runTest {
        // Profile 2 is not the active one (1): the sign-in's own profile gets the account.
        quota.recordFailure(2, SimklApiException(429, SIMKL_ERROR_USER_LIMIT, retryAfterSeconds = 3_600))
        engine.reply(200, tokenReply)
        engine.reply(200, """{"user":{"name":"arshad.k"},"account":{"id":7,"type":"free"}}""", mapOf("X-RateLimit-Remaining" to "499", "X-RateLimit-Limit" to "500"))
        assertEquals(TrackingDevicePoll.Approved, auth.poll(2, code))

        assertNull(stored())
        val account = accounts.get(2, TrackingProviderId.SIMKL)!!
        assertEquals("simkl_at_new", account.accessToken)
        assertEquals("simkl_rt_1", account.refreshToken)
        assertEquals(clock.now + 604_800_000L, account.expiresAtEpochMs)
        assertEquals("arshad.k", account.accountName)
        assertEquals("/users/settings", engine.calls[1].path)
        assertEquals("Bearer simkl_at_new", engine.calls[1].headers["Authorization"])
        assertEquals(SimklQuotaState(limit = 500, remaining = 499), quota.state(2))
    }

    @Test
    fun `approval still connects when the name cannot be read`() = runTest {
        engine.reply(200, tokenReply)
        engine.reply(404)
        assertEquals(TrackingDevicePoll.Approved, auth.poll(1, code))
        assertEquals("simkl_at_new", stored()!!.accessToken)
        assertNull(stored()!!.accountName)
    }

    @Test
    fun `a token a day from expiry is refreshed first and the refresh token kept`() = runTest {
        signedIn(expiresIn = day - 1)
        engine.reply(200, """{"access_token":"simkl_at_new","expires_in":604800}""")
        engine.reply(200, "{}")
        session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities"))

        val refresh = engine.calls[0]
        assertEquals("/oauth2/token", refresh.path)
        assertEquals("refresh_token", refresh.json().getString("grant_type"))
        assertEquals("simkl_rt_1", refresh.json().getString("refresh_token"))
        assertEquals("Bearer simkl_at_new", engine.calls[1].headers["Authorization"])
        assertEquals("simkl_rt_1", stored()!!.refreshToken)
        assertEquals("arshad.k", stored()!!.accountName)
    }

    @Test
    fun `an unreachable refresh keeps using a token that has not run out`() = runTest {
        signedIn(expiresIn = day - 1)
        engine.reply(503)
        engine.reply(503)
        engine.reply(503)
        engine.reply(503)
        assertEquals("simkl_at_old", tokens.accessToken(1))
        assertEquals("simkl_at_old", stored()!!.accessToken)
    }

    @Test
    fun `a 401 refreshes once and retries`() = runTest {
        signedIn()
        engine.reply(401, """{"error":"invalid_token"}""")
        engine.reply(200, """{"access_token":"simkl_at_new","expires_in":604800}""")
        engine.reply(200, """{"ok":1}""")
        assertEquals("""{"ok":1}""", session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")).body)
        assertEquals(listOf("/sync/activities", "/oauth2/token", "/sync/activities"), engine.calls.map { it.path })
    }

    @Test
    fun `a refused refresh marks the account revoked and calls stop`() = runTest {
        signedIn()
        engine.reply(401, """{"error":"invalid_token"}""")
        engine.reply(400, """{"error":"invalid_grant"}""")
        assertFailsSuspend<SimklSignedOutException> { session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")) }
        assertTrue(stored()!!.revoked)
        assertNull(tokens.accessToken(1))
        assertFailsSuspend<SimklSignedOutException> { session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")) }
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `a caller holding a replaced token takes the new one without a second refresh`() = runTest {
        signedIn(access = "simkl_at_current")
        assertEquals("simkl_at_current", tokens.refresh(1, staleAccessToken = "simkl_at_older"))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a spent day stops calls for that profile without sending them`() = runTest {
        signedIn()
        engine.reply(429, """{"error":"user_limit_exceeded"}""", mapOf("Retry-After" to "3600"))
        assertFailsSuspend<SimklApiException> { session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")) }
        assertFailsSuspend<SimklQuotaExceededException> { session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")) }
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `disconnect revokes the grant and forgets it even when Simkl cannot be reached`() = runTest {
        signedIn()
        engine.reply(200, "")
        auth.disconnect(1)
        assertEquals("/oauth2/revoke", engine.calls.single().path)
        assertEquals("simkl_rt_1", engine.calls.single().json().getString("token"))
        assertNull(stored())

        signedIn()
        engine.reply(500)
        engine.reply(500)
        engine.reply(500)
        engine.reply(500)
        auth.disconnect(1)
        assertNull(stored())
    }

    @Test
    fun `disconnect clears the quota and gives the revoke ten seconds`() = runTest {
        signedIn()
        quota.record(1, SimklResponse(200, "", mapOf("x-ratelimit-remaining" to "10")))
        engine.hold = kotlinx.coroutines.CompletableDeferred()
        auth.disconnect(1)
        assertEquals(10_000L, testScheduler.currentTime)
        assertNull(stored())
        assertEquals(SimklQuotaState(), quota.state(1))
    }

    @Test
    fun `a disconnect cancelled mid-revoke still forgets the account`() = runTest {
        signedIn()
        engine.hold = kotlinx.coroutines.CompletableDeferred()
        val job = launch { auth.disconnect(1) }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertNull(stored())
    }

    @Test
    fun `a missing-token 401 is not refreshed and a second 401 is not looped`() = runTest {
        signedIn()
        engine.reply(401, """{"error":"user_token_required"}""")
        assertEquals(401, assertFailsSuspend<SimklApiException> { session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")) }.status)
        assertEquals(1, engine.calls.size)

        engine.reply(401, """{"error":"invalid_token"}""")
        engine.reply(200, """{"access_token":"simkl_at_new","expires_in":604800}""")
        engine.reply(401, """{"error":"invalid_token"}""")
        assertFailsSuspend<SimklApiException> { session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")) }
        assertEquals(4, engine.calls.size)
    }

    @Test
    fun `an expired token with Simkl unreachable fails instead of sending it`() = runTest {
        signedIn(expiresIn = -1)
        repeat(4) { engine.reply(503) }
        assertFailsSuspend<SimklApiException> { tokens.accessToken(1) }
        assertEquals("simkl_at_old", stored()!!.accessToken)
        assertFalse(stored()!!.revoked)
    }

    @Test
    fun `an account without a refresh token is revoked when it needs one`() = runTest {
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("simkl_at_old", null, clock.now - 1))
        assertNull(tokens.accessToken(1))
        assertTrue(stored()!!.revoked)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `two callers refreshing the same token make one refresh`() = runTest {
        signedIn()
        engine.hold = kotlinx.coroutines.CompletableDeferred()
        engine.reply(200, """{"access_token":"simkl_at_new","expires_in":604800}""")
        val first = async { tokens.refresh(1, "simkl_at_old") }
        val second = async { tokens.refresh(1, "simkl_at_old") }
        testScheduler.runCurrent()
        engine.hold!!.complete(Unit)
        assertEquals("simkl_at_new", first.await())
        assertEquals("simkl_at_new", second.await())
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a sign-in landing during a refresh is kept, not overwritten`() = runTest {
        signedIn()
        engine.hold = kotlinx.coroutines.CompletableDeferred()
        engine.reply(200, """{"access_token":"simkl_at_refreshed","expires_in":604800}""")
        val refreshing = async { tokens.refresh(1, "simkl_at_old") }
        testScheduler.runCurrent()
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("simkl_at_other", "simkl_rt_2", clock.now + 7 * day))
        engine.hold!!.complete(Unit)
        assertEquals("simkl_at_other", refreshing.await())
        assertEquals("simkl_at_other", stored()!!.accessToken)
    }

    @Test
    fun `a refused refresh does not revoke a sign-in that landed meanwhile`() = runTest {
        signedIn()
        engine.hold = kotlinx.coroutines.CompletableDeferred()
        engine.reply(400, """{"error":"invalid_grant"}""")
        val refreshing = async { tokens.refresh(1, "simkl_at_old") }
        testScheduler.runCurrent()
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("simkl_at_other", "simkl_rt_2", clock.now + 7 * day))
        engine.hold!!.complete(Unit)
        assertEquals("simkl_at_other", refreshing.await())
        assertFalse(stored()!!.revoked)
        assertEquals("simkl_at_other", stored()!!.accessToken)
    }

    @Test
    fun `a sign-out landing during a refresh is not undone by its answer`() = runTest {
        signedIn()
        engine.hold = kotlinx.coroutines.CompletableDeferred()
        engine.reply(200, tokenReply)
        val refreshing = async { tokens.refresh(1, "simkl_at_old") }
        testScheduler.runCurrent()
        accounts.clear(1, TrackingProviderId.SIMKL)
        engine.hold!!.complete(Unit)
        assertNull(refreshing.await())
        assertNull(stored())
    }

    @Test
    fun `approval and disconnect drop the profile's synced data`() = runTest {
        // Each call records the profile and whether an account was stored at that moment.
        val changed = mutableListOf<Pair<Int, Boolean>>()
        val withHook = SimklDeviceAuth(client, TEST_CONFIG, accounts, session, onGrantChange = {
            changed += it to (accounts.get(it, TrackingProviderId.SIMKL) != null)
        }) { clock.now }
        engine.reply(200, tokenReply)
        engine.reply(200, """{"user":{"name":"arshad.k"}}""")
        withHook.poll(2, code)
        engine.reply(200)
        withHook.disconnect(2)
        assertEquals(listOf(2 to false, 2 to true, 2 to false), changed)
    }

    @Test
    fun `token replies never print their tokens`() {
        assertFalse(SimklTokenReply("simkl_at_x", "simkl_rt_x", 1).toString().contains("simkl_"))
        assertFalse(parseTokens(tokenReply).toString().contains("simkl_"))
    }

    @Test
    fun `a call queued behind the one that spent the day is not sent`() = runTest {
        signedIn()
        engine.hold = kotlinx.coroutines.CompletableDeferred()
        engine.reply(429, """{"error":"user_limit_exceeded"}""", mapOf("Retry-After" to "3600"))
        val first = async { runCatching { session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")) } }
        val second = async { runCatching { session.call(1, SimklRequest(SimklHttpMethod.GET, "/sync/activities")) } }
        testScheduler.runCurrent()
        engine.hold!!.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is SimklApiException)
        assertTrue(second.await().exceptionOrNull() is SimklQuotaExceededException)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a Retry-After longer than a day falls back to the New York reset`() {
        quota.recordFailure(1, SimklApiException(429, SIMKL_ERROR_USER_LIMIT, retryAfterSeconds = Long.MAX_VALUE / 10))
        assertEquals(nextSimklResetEpochMs(clock.now), quota.state(1).pausedUntilEpochMs)
    }
}
