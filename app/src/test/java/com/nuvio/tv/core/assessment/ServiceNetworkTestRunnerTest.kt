package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 33 D6: when a play holds for the network test, what the test stores and writes, the ETA. */
class ServiceNetworkTestRunnerTest {

    private val url = "https://download.real-debrid.com/d/abc/film.mkv"
    private val addonUrl = "https://addon.example.org/resolve/abc"
    private val baseline = NetworkRow(1, 0, 40.0)
    private val fourBy16 = NetworkRow(4, 16, 200.0)
    private val table = ServiceTable(
        NetworkTransport.WIFI, testedAtMs = 1_000L, http2 = true,
        rows = listOf(baseline, NetworkRow(2, 8, 90.0), fourBy16)
    )
    private val tested = ServiceNetworkTables(tables = mapOf("real-debrid.com" to table))

    /** Automatic shape; network values distinct from every row so a write shows. */
    private val saved = PlayerSettings(
        bufferEngineEnabled = true,
        parallelNetworkEnabled = true,
        bufferBudgetManaged = false,
        nuvioPerformanceModeEnabled = true,
        useParallelConnections = true,
        parallelConnectionCount = 3,
        parallelChunkSizeKb = 12_288,
        enableHttp2 = false,
        bufferSettings = BufferSettings(targetBufferSizeMb = 150, maxBufferMs = 120_000, backBufferDurationMs = 0)
    )

    private val limits2Gb = DeviceLimits(325, 16, perfModeSupported = true, afrSupported = null)

    private fun hold(
        tables: ServiceNetworkTables,
        streamUrl: String = url,
        shape: Boolean = true,
        transport: NetworkTransport = NetworkTransport.WIFI
    ) = shouldHoldForNetworkTest(tables, streamUrl, shape, transport)

    // ── hold ────────────────────────────────────────────────────────────────

    @Test
    fun `an untested service holds, a tested one plays`() {
        assertTrue(hold(ServiceNetworkTables()))
        assertFalse(hold(tested))
    }

    @Test
    fun `the rule off or outside its shape never holds`() {
        assertFalse(hold(ServiceNetworkTables(automatic = false)))
        assertFalse(hold(ServiceNetworkTables(), shape = false))
        assertFalse(hold(ServiceNetworkTables(), streamUrl = "not a url"))
    }

    @Test
    fun `Run again holds tables tested before it, not after`() {
        assertTrue(hold(tested.copy(retestRequestedAtMs = 2_000L)))
        assertFalse(hold(tested.copy(retestRequestedAtMs = 500L)))
    }

    @Test
    fun `a switch between Wi-Fi and Ethernet re-tests, an unknown link does not`() {
        assertTrue(hold(tested, transport = NetworkTransport.ETHERNET))
        assertFalse(hold(tested, transport = NetworkTransport.OTHER))
    }

    @Test
    fun `a table tested on an unknown link re-tests once the link is known`() {
        val unknown = ServiceNetworkTables(tables = mapOf("real-debrid.com" to table.copy(transport = NetworkTransport.OTHER)))
        assertTrue(hold(unknown, transport = NetworkTransport.WIFI))
        assertFalse(hold(unknown, transport = NetworkTransport.OTHER))
    }

    @Test
    fun `an aliased request domain uses its service's table`() {
        assertTrue(hold(tested, streamUrl = addonUrl))
        assertFalse(hold(tested.copy(aliases = mapOf("example.org" to "real-debrid.com")), streamUrl = addonUrl))
    }

    // ── Spec 59 D3: a request domain leading to a measured service ──────────

    @Test
    fun `a new request domain whose service is measured on this link takes its table, no sweep`() {
        val after = tested.withKnownService("example.org", "real-debrid.com", NetworkTransport.WIFI)!!
        assertEquals(mapOf("example.org" to "real-debrid.com"), after.aliases)
        assertEquals(table, after.tables["real-debrid.com"]) // same table, same tested time
        assertFalse(hold(after, streamUrl = addonUrl))
    }

    @Test
    fun `a service due Run again, tested on another link or never tested is measured`() {
        assertNull(tested.copy(retestRequestedAtMs = 2_000L).withKnownService("example.org", "real-debrid.com", NetworkTransport.WIFI))
        assertNull(tested.withKnownService("example.org", "real-debrid.com", NetworkTransport.ETHERNET))
        assertNull(tested.withKnownService("example.org", "torbox.app", NetworkTransport.WIFI))
        // An unknown link keeps every table, as the hold does.
        assertEquals(
            mapOf("example.org" to "real-debrid.com"),
            tested.withKnownService("example.org", "real-debrid.com", NetworkTransport.OTHER)?.aliases
        )
    }

    @Test
    fun `a request domain aliased to a stale service moves to the measured one`() {
        // example.org led to other.net (due Run again); it now leads to real-debrid.com, measured.
        val stale = ServiceNetworkTables(
            tables = mapOf("real-debrid.com" to table.copy(testedAtMs = 3_000L), "other.net" to table),
            aliases = mapOf("example.org" to "other.net"),
            retestRequestedAtMs = 2_000L
        )
        assertTrue(hold(stale, streamUrl = addonUrl))
        val after = stale.withKnownService("example.org", "real-debrid.com", NetworkTransport.WIFI)!!
        assertEquals(mapOf("example.org" to "real-debrid.com"), after.aliases)
        assertFalse(hold(after, streamUrl = addonUrl))
        // other.net's queued table no stream reaches goes, so Run again can finish.
        assertEquals(setOf("real-debrid.com"), after.tables.keys)
        assertFalse(after.retestPending)
    }

    @Test
    fun `a request domain's own queued table goes once it leads to a measured service`() {
        val queued = ServiceNetworkTables(
            tables = mapOf("real-debrid.com" to table.copy(testedAtMs = 3_000L), "example.org" to table),
            retestRequestedAtMs = 2_000L
        )
        assertTrue(hold(queued, streamUrl = addonUrl))
        val after = queued.withKnownService("example.org", "real-debrid.com", NetworkTransport.WIFI)!!
        assertEquals(setOf("real-debrid.com"), after.tables.keys)
        assertFalse(after.retestPending)
        // Still reached through another alias: kept.
        val shared = queued.copy(aliases = mapOf("other.org" to "example.org"))
            .withKnownService("example.org", "real-debrid.com", NetworkTransport.WIFI)!!
        assertTrue("example.org" in shared.tables)
    }

    @Test
    fun `a stream served from its own domain is measured, never aliased to itself`() {
        // A stale self-alias, or a probe that got no answer and fell back to the request domain.
        val stale = tested.copy(aliases = mapOf("real-debrid.com" to "other.net"))
        assertTrue(hold(stale))
        assertNull(stale.withKnownService("real-debrid.com", "real-debrid.com", NetworkTransport.WIFI))
    }

    @Test
    fun `an alias whose service has no table holds`() {
        assertTrue(hold(tested.copy(aliases = mapOf("example.org" to "torbox.app")), streamUrl = addonUrl))
    }

    // ── what the test stores ────────────────────────────────────────────────

    @Test
    fun `the table goes under the serving service with an alias from the request domain`() {
        val after = ServiceNetworkTables().withTest("example.org", "real-debrid.com", table)
        assertEquals(table, after.tables["real-debrid.com"])
        assertEquals(mapOf("example.org" to "real-debrid.com"), after.aliases)
        assertEquals(table, after.tableFor(addonUrl))
    }

    @Test
    fun `a stream served from its own domain drops a stale alias`() {
        val stale = ServiceNetworkTables(aliases = mapOf("real-debrid.com" to "other.net"))
        val after = stale.withTest("real-debrid.com", "real-debrid.com", table)
        assertTrue(after.aliases.isEmpty())
        assertEquals(table, after.tableFor(url))
    }

    @Test
    fun `the serving domain loses an alias of its own`() {
        // real-debrid.com once redirected elsewhere; now it serves, so its own table must win.
        val stale = ServiceNetworkTables(aliases = mapOf("real-debrid.com" to "other.net"))
        val after = stale.withTest("example.org", "real-debrid.com", table)
        assertEquals(mapOf("example.org" to "real-debrid.com"), after.aliases)
        assertEquals(table, after.tableFor(url))
    }

    @Test
    fun `a queued table no alias reaches any more goes, so Run again can finish`() {
        val old = table.copy(testedAtMs = 1_000L)
        val queued = ServiceNetworkTables(
            tables = mapOf("real-debrid.com" to old),
            aliases = mapOf("example.org" to "real-debrid.com"),
            retestRequestedAtMs = 2_000L
        )
        val moved = queued.withTest("example.org", "torbox.app", table.copy(testedAtMs = 3_000L))
        assertEquals(setOf("torbox.app"), moved.tables.keys)
        assertFalse(moved.retestPending)
        // Not queued, or still reached through another alias: kept.
        val notQueued = queued.copy(retestRequestedAtMs = 0L).withTest("example.org", "torbox.app", table)
        assertTrue("real-debrid.com" in notQueued.tables)
        val shared = queued.copy(aliases = queued.aliases + ("other.org" to "real-debrid.com"))
            .withTest("example.org", "torbox.app", table.copy(testedAtMs = 3_000L))
        assertTrue("real-debrid.com" in shared.tables)
    }

    @Test
    fun `a re-test replaces the table and clears its Run again`() {
        val queued = tested.copy(retestRequestedAtMs = 2_000L)
        val after = queued.withTest("real-debrid.com", "real-debrid.com", table.copy(testedAtMs = 3_000L))
        assertFalse(after.retestPending)
    }

    // ── what the test writes ────────────────────────────────────────────────

    @Test
    fun `the fastest fitting row is written, only the fields that change`() {
        val plan = networkTestPlan(table, saved, limits2Gb)!!
        assertEquals(AssessmentApplyPlan(parallelConnectionCount = 4, parallelChunkSizeKb = 16_384, enableHttp2 = true), plan)
    }

    @Test
    fun `settings already on the fastest row write nothing`() {
        val onRow = saved.copy(parallelConnectionCount = 4, parallelChunkSizeKb = 16_384, enableHttp2 = true)
        assertNull(networkTestPlan(table, onRow, limits2Gb))
    }

    @Test
    fun `a single-connection table turns parallel off and keeps the pipe values`() {
        val plan = networkTestPlan(table.copy(rows = listOf(baseline)), saved, limits2Gb)!!
        assertEquals(AssessmentApplyPlan(useParallelConnections = false, enableHttp2 = true), plan)
    }

    @Test
    fun `a row that needs the buffer cut is not the one written`() {
        // 200 MB safe: 4 x 16 MB (96 MB pipe) needs the 150 MB target cut, 2 x 8 MB (32 MB) fits
        // beside it. The buffer is never written.
        val plan = networkTestPlan(table, saved, limits2Gb.copy(safeMb = 200))!!
        assertEquals(AssessmentApplyPlan(parallelConnectionCount = 2, parallelChunkSizeKb = 8_192, enableHttp2 = true), plan)
    }

    @Test
    fun `no HTTP-2 verdict leaves HTTP-2 untouched`() {
        val plan = networkTestPlan(table.copy(http2 = null), saved, limits2Gb)!!
        assertNull(plan.enableHttp2)
    }

    @Test
    fun `nothing fits beside a buffer over the safe limit, nothing is written`() {
        assertNull(networkTestPlan(table, saved, limits2Gb.copy(safeMb = 100)))
    }

    // ── ETA ─────────────────────────────────────────────────────────────────

    @Test
    fun `the estimate counts the steps left and rounds to minutes`() {
        // 13 steps of 8 s: 104 s at the start, under 45 s from step 9.
        assertEquals(2, networkTestMinutesLeft(0))
        assertEquals(2, networkTestMinutesLeft(1))
        assertEquals(1, networkTestMinutesLeft(5))
        assertEquals(1, networkTestMinutesLeft(8))
        assertEquals(0, networkTestMinutesLeft(9))
        assertEquals(0, networkTestMinutesLeft(99))
    }

    @Test
    fun `the shown step stays within the total`() {
        assertEquals(1, networkTestDisplayStep(0))
        assertEquals(NETWORK_TEST_STEPS, networkTestDisplayStep(NETWORK_TEST_STEPS + 3))
    }
}
