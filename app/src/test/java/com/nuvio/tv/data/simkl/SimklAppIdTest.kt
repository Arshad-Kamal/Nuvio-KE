package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 K: this TV's Simkl app id (store, check, setup) and every call reading it per request (D9). */
@OptIn(ExperimentalCoroutinesApi::class)
class SimklAppIdTest {

    private val dir: File = Files.createTempDirectory("simkl").toFile()
    private val file = File(dir, "simkl_app_id")
    private val engine = FakeSimklEngine()
    private val clock = FakeClock()

    private fun TestScope.store(scope: CoroutineScope = backgroundScope) =
        SimklAppIdStore(file, UnconfinedTestDispatcher(testScheduler), scope)

    private fun client(store: SimklAppIdStore) = SimklClient(
        engine, TEST_CONFIG.copy(clientId = store::get), monotonicMs = { clock.now }, sleep = clock.sleep, jitterMs = { 0L }
    )

    @Test
    fun `an id survives a new store, a removed one is gone, and a malformed file reads as none`() = runTest(UnconfinedTestDispatcher()) {
        val first = store()
        assertNull(first.get())
        first.save(APP_ID)
        assertEquals(APP_ID, first.appId.value)
        assertEquals(APP_ID, store().get())
        assertFalse(File(dir, "simkl_app_id.tmp").exists())

        first.save(null)
        assertFalse(file.exists())
        assertNull(store().get())

        file.writeText("not an id?&x=1")
        assertNull(store().get())
        file.writeText("  $APP_ID\n")
        assertEquals(APP_ID, store().get())
    }

    @Test
    fun `a save that cannot reach the disk throws and keeps the id in use`() = runTest(UnconfinedTestDispatcher()) {
        val good = store()
        good.save(APP_ID)
        // The id's path taken by a folder with something in it: the rename and the delete both fail.
        file.delete()
        file.mkdirs()
        File(file, "x").writeText("x")

        assertFailsSuspend<java.io.IOException> { good.save(OTHER_ID) }
        assertEquals(APP_ID, good.appId.value)
        assertFalse(File(dir, "simkl_app_id.tmp").exists())
        assertFailsSuspend<java.io.IOException> { good.save(null) }
        assertEquals(APP_ID, good.appId.value)
    }

    @Test
    fun `a pasted id keeps only what a URL and a body can carry`() {
        assertEquals(APP_ID, cleanSimklAppId(" ${APP_ID.chunked(8).joinToString(" ")}\n"))
        assertNull(cleanSimklAppId("short"))
        assertNull(cleanSimklAppId("abcd1234&client_id=other"))
        assertNull(cleanSimklAppId("abcd/../1234"))
        assertNull(cleanSimklAppId("a".repeat(129)))
    }

    @Test
    fun `with no id set up nothing is sent, and each call reads the id saved now`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val client = client(store)
        assertFailsSuspend<SimklSignedOutException> { client.execute(SimklRequest(SimklHttpMethod.GET, "/movies/1"), null) }
        assertTrue(engine.calls.isEmpty())

        store.save(APP_ID)
        engine.reply(200)
        client.execute(SimklRequest(SimklHttpMethod.GET, "/movies/1"), null)
        store.save(OTHER_ID)
        engine.reply(200)
        client.execute(SimklRequest(SimklHttpMethod.GET, "/movies/1"), null)
        assertEquals(listOf(APP_ID, OTHER_ID), engine.calls.map { it.query("client_id") })

        assertFailsSuspend<SimklSignedOutException> {
            client.execute(SimklRequest(SimklHttpMethod.GET, "/movies/1"), null, clientId = " ")
        }
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `the check asks Simkl for a device code with the pasted id, and maps each answer`() = runTest(UnconfinedTestDispatcher()) {
        val client = client(store())
        engine.reply(200, """{"device_code":"d","user_code":"ABCD-EFGH","verification_uri":"https://simkl.com/pin"}""")
        assertEquals(PasteCheck.Saved, checkSimklAppId(client, APP_ID))
        val call = engine.calls.single()
        assertEquals("/oauth2/device", call.path)
        assertEquals(APP_ID, call.query("client_id"))
        assertTrue(call.body!!.contains("\"client_id\":\"$APP_ID\""))
        assertNull("no account token", call.headers["Authorization"])

        engine.reply(401, """{"error":"invalid_client"}""")
        assertEquals(PasteCheck.Refused, checkSimklAppId(client, APP_ID))
        engine.reply(412, """{"error":"client_id_failed"}""")
        assertEquals(PasteCheck.Refused, checkSimklAppId(client, APP_ID))
        engine.reply(429, """{"error":"user_limit_exceeded"}""")
        assertEquals(PasteCheck.Unreachable, checkSimklAppId(client, APP_ID))
        engine.fail()
        assertEquals(PasteCheck.Unreachable, checkSimklAppId(client, APP_ID))
        repeat(4) { engine.reply(503) }
        assertEquals(PasteCheck.Unreachable, checkSimklAppId(client, APP_ID))
    }

    @Test
    fun `a refused or abandoned id saves nothing and signs nobody out`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val signedOut = mutableListOf<Int>()
        val setup = SimklAppIdSetup(store, client(store), { listOf(1, 2) }, { signedOut += it }, backgroundScope)

        assertEquals(PasteCheck.Refused, setup.submit("bad id!", stillWanted = { true }))
        assertTrue("an id that is not one is never sent", engine.calls.isEmpty())

        engine.reply(401, """{"error":"invalid_client"}""")
        assertEquals(PasteCheck.Refused, setup.submit(APP_ID, stillWanted = { true }))

        assertEquals(PasteCheck.Closed, setup.submit(APP_ID, stillWanted = { false }))
        assertEquals(1, engine.calls.size)

        var wanted = true
        engine.reply(200)
        // The page closes while Simkl answers: the yes is not saved.
        assertEquals(PasteCheck.Closed, setup.submit(APP_ID) { wanted.also { wanted = false } })
        assertNull(store.get())
        assertNull(setup.savedHint.value)
        assertTrue(signedOut.isEmpty())
    }

    @Test
    fun `any new id signs everyone out under the id before it (a first one too), the same again signs nobody out`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store()
            val events = mutableListOf<String>()
            val setup = SimklAppIdSetup(
                store, client(store), { listOf(1, 2) },
                { profileId -> events += "out:$profileId:${store.get()}" }, backgroundScope
            )

            // A first id: accounts stored without one (an older build's) belong to another app's grant.
            engine.reply(200)
            assertEquals(PasteCheck.Saved, setup.submit(" $APP_ID ", stillWanted = { true }))
            assertEquals(APP_ID, store.get())
            assertEquals(APP_ID.takeLast(4), setup.savedHint.value)
            assertEquals(listOf("out:1:null", "out:2:null"), events)
            events.clear()

            engine.reply(200)
            assertEquals(PasteCheck.Saved, setup.submit(APP_ID, stillWanted = { true }))
            assertTrue(events.isEmpty())

            engine.reply(200)
            assertEquals(PasteCheck.Saved, setup.submit(OTHER_ID, stillWanted = { true }))
            assertEquals(listOf("out:1:$APP_ID", "out:2:$APP_ID"), events)
            assertEquals(OTHER_ID, store.get())
            assertEquals(OTHER_ID.takeLast(4), setup.savedHint.value)
        }

    @Test
    fun `a sign-out that fails part way keeps the id and fails the call`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.save(APP_ID)
        val out = mutableListOf<Int>()
        val setup = SimklAppIdSetup(store, client(store), { listOf(1, 2) }, { profileId ->
            if (profileId == 2) throw java.io.IOException("disk")
            out += profileId
        }, backgroundScope)
        assertFailsSuspend<java.io.IOException> { setup.remove() }
        assertEquals(listOf(1), out)
        assertEquals(APP_ID, store.get())

        engine.reply(200)
        assertFailsSuspend<java.io.IOException> { setup.submit(OTHER_ID, stillWanted = { true }) }
        assertEquals(APP_ID, store.get())
    }

    @Test
    fun `remove signs everyone out under the id, then forgets it`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.save(APP_ID)
        val events = mutableListOf<String>()
        val setup = SimklAppIdSetup(store, client(store), { listOf(3) }, { events += "out:$it:${store.get()}" }, backgroundScope)
        setup.remove()
        assertEquals(listOf("out:3:$APP_ID"), events)
        assertNull(store.get())
        assertNull(setup.savedHint.value)
    }

    @Test
    fun `a sign-out with the id revokes under it, and without one only forgets the account`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val accounts = accountStore()
        val client = client(store)
        val config = TEST_CONFIG.copy(clientId = store::get)
        val auth = SimklDeviceAuth(client, config, accounts, SimklSession(client, SimklTokens(client, config, accounts), SimklQuota()))

        store.save(APP_ID)
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("at", "rt"))
        engine.reply(200)
        auth.disconnect(1)
        assertEquals("/oauth2/revoke", engine.calls.single().path)
        assertEquals(APP_ID, engine.calls.single().query("client_id"))
        assertNull(accounts.get(1, TrackingProviderId.SIMKL))

        store.save(null)
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("at2", "rt2"))
        auth.disconnect(1)
        assertEquals(1, engine.calls.size)
        assertNull(accounts.get(1, TrackingProviderId.SIMKL))
    }

    @Test
    fun `the setup hint follows the store from its first read`() = runTest(UnconfinedTestDispatcher()) {
        file.writeText(APP_ID)
        val store = store()
        val setup = SimklAppIdSetup(store, client(store), { emptyList() }, { }, backgroundScope)
        assertEquals(APP_ID.takeLast(4), setup.savedHint.value)
    }

    private companion object {
        const val APP_ID = "a3f9c2e17b4d5e6f708192a3b4c5d6e7f80e58d61c7f3a"
        const val OTHER_ID = "0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e7f3b"
    }
}
