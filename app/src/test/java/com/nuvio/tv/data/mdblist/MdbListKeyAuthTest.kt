package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S1a/S1b / D6-D8: connecting with a pasted key (TrackingKeyAuth), the session's key and its revocation. */
class MdbListKeyAuthTest {

    private val engine = FakeMdbListEngine()
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val quota = MdbListQuota { clock.now }
    private val client = mdbListClient(engine, clock)
    private val session = MdbListSession(client, accounts, quota)
    private val auth = MdbListKeyAuth(client, session, accounts)
    private val key = "abc123def456ghi789jkl0mno"
    private val userBody = """{"username":"arshadk","name":"Arshad","plan":"free","is_supporter":false,"api_requests":1000}"""
    private val quotaHeaders = mapOf("X-RateLimit-Limit" to "1000", "X-RateLimit-Remaining" to "988")

    @Test
    fun `an accepted key is checked with user and saved on that profile`() = runTest {
        engine.reply(200, userBody, quotaHeaders)
        val result = auth.connect(2, "  $key\n") { true }
        assertEquals(PasteCheck.Saved, result)
        val call = engine.calls.single()
        assertEquals("/user", call.path)
        assertEquals(key, call.query("apikey"))
        assertEquals(TrackingAccount(accessToken = key, accountName = "arshadk"), accounts.get(2, TrackingProviderId.MDBLIST))
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))
        assertEquals(988, quota.state(2).remaining)
        assertEquals("free", quota.state(2).plan)
    }

    @Test
    fun `a refused key keeps nothing`() = runTest {
        engine.reply(401, """{"error":"Invalid API key"}""")
        assertEquals(PasteCheck.Refused, auth.connect(1, key) { true })
        engine.reply(403, """{"error":"Blocked"}""")
        assertEquals(PasteCheck.Refused, auth.connect(1, key) { true })
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))
    }

    @Test
    fun `a key checked after its panel closed is not kept`() = runTest {
        engine.reply(200, userBody, quotaHeaders)
        assertEquals(PasteCheck.Closed, auth.connect(1, key) { false })
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))
        assertEquals(MdbListQuotaState(), quota.state(1))
    }

    @Test
    fun `a spent day or another error cannot check the key`() = runTest {
        engine.reply(429, DAILY_LIMIT_BODY)
        assertEquals(PasteCheck.Unreachable, auth.connect(1, key) { true })
        engine.reply(404)
        assertEquals(PasteCheck.Unreachable, auth.connect(1, key) { true })
        engine.reply(200, "not json")
        assertEquals(PasteCheck.Unreachable, auth.connect(1, key) { true })
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))
    }

    @Test
    fun `something that is not a key is not sent`() = runTest {
        listOf("", "   ", "short", "has spaces in it", "key\"with<quote>", "x".repeat(129)).forEach {
            assertEquals(PasteCheck.Refused, auth.connect(1, it) { true })
        }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `an unreachable MDBList keeps nothing, and a paused profile can still try a new key`() = runTest {
        quota.recordFailure(1, MdbListApiException(429, dailyLimit = true))
        repeat(4) { engine.fail() }
        assertEquals(PasteCheck.Unreachable, auth.connect(1, key) { true })
        assertEquals(4, engine.calls.size)
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))

        engine.reply(200, userBody, quotaHeaders)
        assertEquals(PasteCheck.Saved, auth.connect(1, key) { true })
        assertFalse(quota.state(1).isPaused(clock.now))
    }

    @Test
    fun `a new key replaces a revoked one`() = runTest {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "old-key-1234", accountName = "arshadk", revoked = true))
        engine.reply(200, userBody)
        auth.connect(1, key) { true }
        assertFalse(accounts.get(1, TrackingProviderId.MDBLIST)!!.revoked)
    }

    @Test
    fun `disconnect forgets the key and the quota`() = runTest {
        engine.reply(200, userBody, quotaHeaders)
        auth.connect(1, key) { true }
        auth.disconnect(1)
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))
        assertEquals(MdbListQuotaState(), quota.state(1))
    }

    @Test
    fun `the plan is read once with the profile's key, with the quota`() = runTest {
        auth.loadPlan(1)
        assertTrue("no key: nothing sent", engine.calls.isEmpty())
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key))
        engine.reply(200, userBody, quotaHeaders)
        auth.loadPlan(1)
        auth.loadPlan(1)
        assertEquals(key, engine.calls.single().query("apikey"))
        assertEquals("free", quota.state(1).plan)
        assertEquals(988, quota.state(1).remaining)
    }

    @Test
    fun `a plan read for a key replaced during the call is not kept`() = runTest {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key))
        engine.onCall = { accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "other-key-5678")) }
        engine.reply(200, userBody)
        auth.loadPlan(1)
        assertNull(quota.state(1).plan)
    }

    @Test
    fun `no key or a revoked key sends nothing`() = runTest {
        failsWith<MdbListSignedOutException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/user")) }
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key, revoked = true))
        failsWith<MdbListSignedOutException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/user")) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a paused profile sends nothing until the reset`() = runTest {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key))
        engine.reply(429, DAILY_LIMIT_BODY)
        failsWith<MdbListApiException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/sync/watched")) }
        failsWith<MdbListQuotaExceededException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/sync/watched")) }
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a 200 that names no user keeps nothing`() = runTest {
        engine.reply(200, """{"error":"Invalid API key!"}""")
        assertEquals(PasteCheck.Unreachable, auth.connect(1, key) { true })
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))
    }

    @Test
    fun `a 401 for a key replaced during the call marks nothing and records no quota`() = runTest {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key))
        val newKey = "new0key0123456789"
        engine.onCall = { accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = newKey)) }
        engine.reply(401)
        failsWith<MdbListSignedOutException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/upnext")) }
        assertEquals(TrackingAccount(accessToken = newKey), accounts.get(1, TrackingProviderId.MDBLIST))

        engine.reply(429, DAILY_LIMIT_BODY)
        engine.onCall = { accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key)) }
        failsWith<MdbListApiException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/upnext")) }
        assertFalse(quota.state(1).isPaused(clock.now))
    }

    @Test
    fun `a queued call sends the key stored when its turn comes`() = runTest {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key))
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.onCall = { release.await() }
        engine.reply(200)
        engine.reply(200)
        val first = async { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/upnext")) }
        val second = async { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/upnext")) }
        testScheduler.runCurrent()
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "replaced-key-42"))
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf(key, "replaced-key-42"), engine.calls.map { it.query("apikey") })
    }

    @Test
    fun `a paused profile sends again after the reset`() = runTest {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key))
        engine.reply(429, DAILY_LIMIT_BODY)
        failsWith<MdbListApiException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/sync/watched")) }
        clock.now = nextMdbListResetEpochMs(clock.now)
        engine.reply(200)
        session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/sync/watched"))
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `a 401 marks the key revoked, a 403 does not`() = runTest {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key, accountName = "arshadk"))
        engine.reply(403, """{"error":"Supporter-only feature"}""")
        failsWith<MdbListApiException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/upnext")) }
        assertFalse(accounts.get(1, TrackingProviderId.MDBLIST)!!.revoked)

        engine.reply(401)
        failsWith<MdbListSignedOutException> { session.call(1, MdbListRequest(MdbListHttpMethod.GET, "/upnext")) }
        assertTrue(accounts.get(1, TrackingProviderId.MDBLIST)!!.revoked)
    }
}
