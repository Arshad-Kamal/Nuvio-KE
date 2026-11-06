package com.nuvio.tv.core.assessment

import com.nuvio.tv.core.network.NetworkTransportReader
import com.nuvio.tv.core.network.StreamSweepEngine.H2Variant
import com.nuvio.tv.core.network.StreamSweepEngine.MeasuredPass
import com.nuvio.tv.core.network.StreamSweepEngine.SweepOutcome
import com.nuvio.tv.core.network.StreamSweepEngine.VerdictKind
import com.nuvio.tv.data.local.FrameRateMatchingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/** Spec 33 D3: per-service tables, their codec and the per-film rule. */
class ServiceNetworkTableTest {

    private val baseline = NetworkRow(1, 0, 40.0)
    private val twoBy8 = NetworkRow(2, 8, 90.0)
    private val fourBy16 = NetworkRow(4, 16, 200.0)
    private val table = ServiceTable(NetworkTransport.WIFI, 1_000L, http2 = true, rows = listOf(baseline, twoBy8, fourBy16))

    private val saved = PlaybackConfig(
        performanceMode = true,
        targetBufferMb = 150,
        minBufferMs = 20_000,
        maxBufferMs = 120_000,
        bufferForPlaybackMs = 3_000,
        bufferForPlaybackAfterRebufferMs = 5_000,
        backBufferMs = 0,
        retainBackBufferFromKeyframe = false,
        // Distinct from every row, so a pick proves the row's values were copied.
        parallel = true,
        connections = 3,
        chunkKb = 12_288,
        http2 = false,
        frameRateMatching = FrameRateMatchingMode.OFF
    )

    private fun limits(safe: Int, tierMaxChunkMb: Int = 16) =
        DeviceLimits(safe, tierMaxChunkMb, perfModeSupported = true, afrSupported = null)

    // ── service key + codec ─────────────────────────────────────────────────

    @Test
    fun `service key is the registrable domain, else the host`() {
        assertEquals("real-debrid.com", serviceKeyOf("https://download.real-debrid.com/d/abc/file.mkv"))
        assertEquals("real-debrid.com", serviceKeyOf("https://47.cdn.real-debrid.com/x"))
        assertEquals("foo.co.uk", serviceKeyOf("https://bar.foo.co.uk/x"))
        assertEquals("192.168.1.5", serviceKeyOf("http://192.168.1.5:8080/x"))
        assertEquals("::1", serviceKeyOf("http://[::1]:8080/x"))
        assertEquals("real-debrid.com", serviceKeyOf("https://DL.Real-Debrid.COM/x"))
        assertEquals("real-debrid.com", serviceKeyOf("https://dl.real-debrid.com./x"))
        assertEquals("example.com", serviceKeyOf("https://example.com/x"))
        assertEquals("localhost", serviceKeyOf("http://localhost:11470/x"))
        assertNull(serviceKeyOf("magnet:?xt=urn:btih:abc"))
        assertNull(serviceKeyOf("not a url"))
    }

    @Test
    fun `tables round-trip and bad JSON reads as none`() {
        val tables = ServiceNetworkTables(
            automatic = false,
            tables = mapOf("real-debrid.com" to table),
            aliases = mapOf("addon.example.net" to "real-debrid.com")
        )
        assertEquals(tables, ServiceNetworkTables.decode(tables.encode()))
        assertEquals(ServiceNetworkTables(), ServiceNetworkTables.decode(null))
        assertEquals(ServiceNetworkTables(), ServiceNetworkTables.decode("{not json"))
        assertTrue(ServiceNetworkTables.decode("{}").automatic)
    }

    @Test
    fun `a later build's keys and transport names keep the manual flag and the table`() {
        val decoded = ServiceNetworkTables.decode(
            """{"automatic":false,"extra":1,"tables":{"a.com":{"transport":"SATELLITE","testedAtMs":7,""" +
                """"rows":[{"connections":2,"chunkMb":8,"mbps":90.0,"new":true}]}}}"""
        )
        assertFalse(decoded.automatic)
        val t = decoded.tables.getValue("a.com")
        assertEquals(NetworkTransport.OTHER, t.transport)
        assertEquals(listOf(twoBy8), t.rows)
    }

    @Test
    fun `table lookup follows the alias from the request domain`() {
        val tables = ServiceNetworkTables(
            tables = mapOf("real-debrid.com" to table),
            aliases = mapOf("example.net" to "real-debrid.com")
        )
        assertEquals(table, tables.tableFor("https://addon.example.net/resolve/123"))
        assertEquals(table, tables.tableFor("https://dl.real-debrid.com/x"))
        assertNull(tables.tableFor("https://other.org/x"))
        assertNull(tables.tableFor("garbage"))
    }

    // ── sweep -> table ──────────────────────────────────────────────────────

    private fun outcome(
        baselineMbps: Double = 40.0,
        measured: List<MeasuredPass> = emptyList(),
        errorText: String? = null,
        h2: H2Variant? = null
    ) = SweepOutcome(
        verdictKind = VerdictKind.FASTEST_NO_BITRATE,
        verdictText = null,
        recommendation = null,
        baselineMbps = baselineMbps,
        targetMbps = null,
        measured = measured,
        errorText = errorText,
        stabilityCoV = null,
        stabilityPassCount = 0,
        h2Variant = h2
    )

    @Test
    fun `table keeps the baseline and the best figure per parallel configuration`() {
        val t = tableFromSweep(
            outcome(
                measured = listOf(MeasuredPass(4, 16, 180.0), MeasuredPass(2, 8, 90.0), MeasuredPass(4, 16, 200.0)),
                h2 = H2Variant.of(4, 16, h2Mbps = 100.0, h1Mbps = 200.0)
            ),
            NetworkTransport.ETHERNET,
            nowMs = 5L
        )!!
        assertEquals(listOf(baseline, twoBy8, fourBy16), t.rows)
        assertEquals(NetworkTransport.ETHERNET, t.transport)
        assertEquals(5L, t.testedAtMs)
        assertEquals(false, t.http2)
        assertNull(tableFromSweep(outcome(), NetworkTransport.WIFI, 0L)!!.http2)
        val h2Wins = H2Variant.of(4, 16, h2Mbps = 200.0, h1Mbps = 100.0)
        assertEquals(true, tableFromSweep(outcome(h2 = h2Wins), NetworkTransport.WIFI, 0L)!!.http2)
    }

    @Test
    fun `a sweep without a baseline keeps its parallel rows`() {
        val t = tableFromSweep(outcome(baselineMbps = 0.0, measured = listOf(MeasuredPass(2, 8, 90.0))), NetworkTransport.WIFI, 0L)
        assertEquals(listOf(twoBy8), t!!.rows)
    }

    @Test
    fun `an errored or empty sweep leaves no table`() {
        assertNull(tableFromSweep(outcome(errorText = "boom"), NetworkTransport.WIFI, 0L))
        assertNull(tableFromSweep(outcome(baselineMbps = 0.0), NetworkTransport.WIFI, 0L))
    }

    @Test
    fun `film bitrate is size over runtime`() {
        assertEquals(66.67, filmMbps(60_000_000_000L, 120)!!, 0.01)
        assertNull(filmMbps(null, 120))
        assertNull(filmMbps(60_000_000_000L, null))
        assertNull(filmMbps(0L, 120))
        assertNull(filmMbps(60_000_000_000L, 0))
    }

    // ── per-film rule ───────────────────────────────────────────────────────

    @Test
    fun `cheapest row reaching 2x wins`() {
        // 15 Mbps: the single connection (40 >= 30) costs no pipe memory.
        val single = choose(table, 15.0, saved, limits(1000))!!
        assertEquals(baseline, single.row)
        assertFalse(single.config.parallel)
        // 30 Mbps: 2 x 8 (90 >= 60) beats 4 x 16 on memory.
        val two = choose(table, 30.0, saved, limits(1000))!!
        assertEquals(twoBy8, two.row)
        assertTrue(two.config.parallel)
        assertEquals(2, two.config.connections)
        assertEquals(8_192, two.config.chunkKb)
        assertFalse(two.mayStall)
        // Exactly 2x counts.
        assertEquals(twoBy8, choose(table, 45.0, saved, limits(1000))!!.row)
    }

    @Test
    fun `without a 2x row the fastest wins, may-stall only below 1x`() {
        val fast = choose(table, 120.0, saved, limits(1000))!!
        assertEquals(fourBy16, fast.row)
        assertEquals(4, fast.config.connections)
        assertEquals(16_384, fast.config.chunkKb)
        assertFalse(fast.mayStall)
        val short = choose(table, 250.0, saved, limits(1000))!!
        assertEquals(fourBy16, short.row)
        assertTrue(short.mayStall)
    }

    @Test
    fun `the table's HTTP-2 verdict applies, else the saved value stays`() {
        assertTrue(choose(table, 30.0, saved, limits(1000))!!.config.http2)
        assertFalse(choose(table.copy(http2 = null), 30.0, saved, limits(1000))!!.config.http2)
    }

    @Test
    fun `rows the device cannot hold are dropped, not thinned`() {
        // safe 150: 4 x 16 (96 MB pipe) beside a 150 MB target would be cut to 8 MB chunks.
        val c = choose(table, 120.0, saved, limits(150))!!
        assertEquals(twoBy8, c.row)
        assertTrue(c.mayStall)
        assertTrue(c.config.targetBufferMb + c.config.parallelOverheadMb <= 150)
    }

    @Test
    fun `a faster heavier pipe wins over target depth down to the floor`() {
        // safe 180, saved target 150, 30 Mbps (bar 57): only 2 x 16 reaches it. Its 64 MB pipe
        // fits beside a 100 MB target (floor 75), so the target gives way, not the pipe.
        val t = table.copy(rows = listOf(NetworkRow(4, 8, 50.0), NetworkRow(2, 16, 70.0)))
        val c = choose(t, 30.0, saved.copy(targetBufferMb = 150), limits(180))!!
        assertEquals(NetworkRow(2, 16, 70.0), c.row)
        assertEquals(100, c.config.targetBufferMb)
        assertEquals(16_384, c.config.chunkKb)
    }

    @Test
    fun `a row above the heap tier's chunk cap is dropped`() {
        val t = table.copy(rows = table.rows + NetworkRow(2, 32, 300.0))
        assertEquals(fourBy16, choose(t, 120.0, saved, limits(1000, tierMaxChunkMb = 16))!!.row)
        assertEquals(NetworkRow(2, 32, 300.0), choose(t, 120.0, saved, limits(1000, tierMaxChunkMb = 32))!!.row)
    }

    @Test
    fun `a tiny limit leaves the single connection, or nothing`() {
        // safe 50: no parallel pipe fits beside even the minimum target.
        assertEquals(baseline, choose(table, 15.0, saved, limits(50))!!.row)
        assertNull(choose(table.copy(rows = listOf(fourBy16)), 15.0, saved, limits(50)))
    }

    @Test
    fun `near-miss rows count as reaching 2x, as in the sweep`() {
        // 2 x 8 at 90 Mbps against 46 Mbps: 92 needed, 87.4 with the 5% tolerance.
        assertEquals(twoBy8, choose(table, 46.0, saved, limits(1000))!!.row)
    }

    @Test
    fun `no film bitrate keeps the saved settings`() {
        assertNull(choose(table, null, saved, limits(1000)))
        assertNull(choose(table, 0.0, saved, limits(1000)))
    }

    @Test
    fun `transport maps Ethernet first, then Wi-Fi`() {
        assertEquals(NetworkTransport.ETHERNET, NetworkTransportReader.transportOf(ethernet = true, wifi = true))
        assertEquals(NetworkTransport.WIFI, NetworkTransportReader.transportOf(ethernet = false, wifi = true))
        assertEquals(NetworkTransport.OTHER, NetworkTransportReader.transportOf(ethernet = false, wifi = false))
    }

    @Test
    fun runAgainMarksTablesTestedBeforeItAsDue() {
        val old = ServiceTable(NetworkTransport.ETHERNET, testedAtMs = 100L, rows = listOf(NetworkRow(1, 0, 50.0)))
        val fresh = old.copy(testedAtMs = 300L)
        val tables = ServiceNetworkTables(tables = mapOf("a.com" to old, "b.com" to fresh), retestRequestedAtMs = 200L)
        assertTrue(tables.retestDue(old))
        assertFalse(tables.retestDue(fresh))
        assertTrue(tables.retestPending)
        assertFalse(tables.copy(tables = mapOf("b.com" to fresh)).retestPending)
        // Never asked: nothing is due.
        assertFalse(ServiceNetworkTables(tables = mapOf("a.com" to old)).retestPending)
    }

    @Test
    fun retestRequestRoundTripsAndOlderBlobsReadZero() {
        val tables = ServiceNetworkTables(automatic = false, retestRequestedAtMs = 42L)
        assertEquals(tables, ServiceNetworkTables.decode(tables.encode()))
        assertEquals(0L, ServiceNetworkTables.decode("""{"automatic":false}""").retestRequestedAtMs)
    }

    @Test
    fun filmsUpToIsTheRowOverTheRuleBar() {
        // 2x the film's bitrate with the sweep's 5% tolerance: 57 Mbps carries films up to 30.
        assertEquals(30.0, filmsUpToMbps(57.0), 1e-9)
        // choose() agrees: at the figure the cheap row is picked, just above it the bigger pipe.
        val single = NetworkRow(1, 0, 57.0)
        val table = ServiceTable(rows = listOf(single, fourBy16))
        assertEquals(single, choose(table, floor(filmsUpToMbps(57.0)), saved, limits(1000))!!.row)
        assertEquals(fourBy16, choose(table, filmsUpToMbps(57.0) + 0.5, saved, limits(1000))!!.row)
    }

    @Test
    fun setupUndoLabelFollowsTheKind() {
        assertEquals(
            DeviceAssessmentApplier.ChangeSource.FIRST_LAUNCH,
            DeviceSetupCoordinator.undoSourceOf(DeviceSetupRecord.Kind.FIRST_LAUNCH)
        )
        assertEquals(
            DeviceAssessmentApplier.ChangeSource.SETUP_AGAIN,
            DeviceSetupCoordinator.undoSourceOf(DeviceSetupRecord.Kind.AGAIN)
        )
    }

    @Test
    fun deviceSetupRecordRoundTripsAndBadJsonIsNone() {
        val record = DeviceSetupRecord("NVIDIA|SHIELD Android TV|mdarcy|2 GB", 1_700_000_000_000L, DeviceSetupRecord.Kind.AGAIN)
        assertEquals(record, DeviceSetupRecord.decode(record.encode()))
        assertNull(DeviceSetupRecord.decode(null))
        assertNull(DeviceSetupRecord.decode("{"))
        assertNull(DeviceSetupRecord.decode("""{"atMs":1}"""))
    }
}
