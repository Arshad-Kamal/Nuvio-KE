package com.nuvio.tv.core.stream

import com.nuvio.tv.core.assessment.NetworkRow
import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.ServiceTable
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 51 P2: the limit per mode, the network-test scope rules and the figure (Spec 58 F2: 80% of the measured speed). */
class StreamLimitTest {

    private val rdUrl = "https://download.real-debrid.com/d/abc/film.mkv"

    /** Fastest row 132.4 Mbit/s: measured 132 (as the table prints it), the figure 106 (105.92 rounded up). */
    private val table = ServiceTable(
        NetworkTransport.WIFI, testedAtMs = 1_700_000_000_000L, http2 = true,
        rows = listOf(NetworkRow(1, 0, 40.0), NetworkRow(4, 16, 132.4))
    )
    private val tables = ServiceNetworkTables(tables = mapOf("real-debrid.com" to table))

    private val networkTest = StreamLimitSettings(StreamLimitMode.NETWORK_TEST)

    /** [sizeBytes] over a 60-minute runtime: Mbit/s = sizeBytes / 450_000_000. */
    private fun stream(
        url: String? = rdUrl,
        sizeBytes: Long? = 60_000_000_000L,
        infoHash: String? = null,
        title: String? = null,
        service: String? = null
    ) = Stream(
        name = "Addon", title = title, description = null, url = url, ytId = null, infoHash = infoHash,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Addon", addonLogo = null,
        streamData = if (sizeBytes == null && service == null) null else {
            AioStreamData(size = sizeBytes, filename = null, cached = null, parsedFile = null, serviceId = service)
        }
    )

    private fun verdict(
        stream: Stream,
        settings: StreamLimitSettings = networkTest,
        with: ServiceNetworkTables = tables,
        transport: NetworkTransport = NetworkTransport.WIFI
    ) = streamLimitVerdict(stream, settings, with, transport, runtimeMinutes = 60, episode = false, debridTableKeys = emptyMap())

    @Test
    fun `network test hides a stream over 80 percent of its service's fastest row, rounded up`() {
        // 60 GB over 60 min = 133.3 Mbit/s > 106.
        val over = verdict(stream())!!
        assertEquals(106, over.figureMbps)
        assertEquals(NetworkTestSource("real-debrid.com", 1_700_000_000_000L, 132), over.source)
        assertTrue(over.hidden)
        // 47.8 GB = 106.2: over the figure, though far under the measured speed.
        assertTrue(verdict(stream(sizeBytes = 47_800_000_000L))!!.hidden)
        // 47.7 GB = 106.0: at the rounded-up figure (over 80% of 132.4 = 105.92) stays shown.
        assertFalse(verdict(stream(sizeBytes = 47_700_000_000L))!!.hidden)
    }

    @Test
    fun `a request domain reaches its serving service's table through the alias`() {
        val aliased = tables.copy(aliases = mapOf("example.net" to "real-debrid.com"))
        val viaAddon = stream(url = "https://aiostreams.example.net/playback/xyz/film.mkv")
        assertNull(verdict(viaAddon))
        assertEquals("real-debrid.com", verdict(viaAddon, with = aliased)!!.source?.serviceKey)
    }

    // Spec 59 N-10: STorz-like rows on a domain no test reached, beside rows the alias carries.
    private val aliased = tables.copy(aliases = mapOf("example.net" to "real-debrid.com"))
    private val viaAddon = stream(url = "https://aiostreams.example.net/playback/1/film.mkv", service = "realdebrid")
    private val untested = stream(url = "https://stremthru.example.xyz/torz/2/film.mkv", service = "realdebrid")

    @Test
    fun `a debrid service maps to the one table its rows reach, else to nothing`() {
        assertEquals(mapOf("realdebrid" to "real-debrid.com"), debridServiceTableKeys(listOf(viaAddon, untested), aliased))
        // Only untested rows: nothing reached.
        assertEquals(emptyMap<String, String>(), debridServiceTableKeys(listOf(untested), aliased))
        // Rows of one service reaching two tables (a proxy tested on its own): ambiguous, nothing.
        val proxyTable = tables.tables + ("proxy.org" to table)
        val viaProxy = stream(url = "https://mf.proxy.org/play/3/film.mkv", service = "realdebrid")
        assertEquals(
            emptyMap<String, String>(),
            debridServiceTableKeys(listOf(viaAddon, viaProxy), aliased.copy(tables = proxyTable))
        )
        // A row with no service id names no service.
        assertEquals(emptyMap<String, String>(), debridServiceTableKeys(listOf(stream()), tables))
        // One ambiguous service drops only itself: another service in the same list still maps.
        val onPm = stream(url = "https://download.real-debrid.com/d/pm/film.mkv", service = "premiumize")
        assertEquals(
            mapOf("premiumize" to "real-debrid.com"),
            debridServiceTableKeys(listOf(viaAddon, viaProxy, onPm), aliased.copy(tables = proxyTable))
        )
    }

    @Test
    fun `a row from an untested domain takes its debrid service's table, its own table first`() {
        val keys = debridServiceTableKeys(listOf(viaAddon, untested), aliased)
        assertNull(verdict(untested, with = aliased))
        val taken = streamLimitVerdict(untested, networkTest, aliased, NetworkTransport.WIFI, 60, episode = false, keys)!!
        assertTrue(taken.hidden)
        assertEquals("real-debrid.com", taken.source?.serviceKey)
        // Another service's key does not apply.
        assertNull(streamLimitVerdict(untested, networkTest, aliased, NetworkTransport.WIFI, 60, false, mapOf("premiumize" to "real-debrid.com")))
        // A domain with its own table keeps it: 133 Mbit/s under its own figure (80% of 250 = 200) shows.
        val fast = ServiceTable(NetworkTransport.WIFI, testedAtMs = 2L, rows = listOf(NetworkRow(4, 16, 250.0)))
        val ownTable = aliased.copy(tables = aliased.tables + ("example.xyz" to fast))
        val own = streamLimitVerdict(untested, networkTest, ownTable, NetworkTransport.WIFI, 60, false, keys)!!
        assertEquals("example.xyz", own.source?.serviceKey)
        assertFalse(own.hidden)
    }

    @Test
    fun `a table from another link counts as none, unless the link is unknown now`() {
        assertNull(verdict(stream(), transport = NetworkTransport.ETHERNET))
        assertTrue(verdict(stream(), transport = NetworkTransport.OTHER)!!.hidden)
        val unknownLink = ServiceNetworkTables(tables = mapOf("real-debrid.com" to table.copy(transport = NetworkTransport.OTHER)))
        assertNull(verdict(stream(), with = unknownLink))
    }

    @Test
    fun `network test never limits torrents, untested services, small or local files, or unknown estimates`() {
        assertNull(verdict(stream(url = null, infoHash = "a".repeat(40))))
        assertNull(verdict(stream(url = "https://cdn.example.org/film.mkv")))
        assertNull(verdict(stream(sizeBytes = 90_000_000L)))
        assertNull(verdict(stream(sizeBytes = null)))
        // A local server (TorrServer) is outside the rule even with a table under its host.
        val local = ServiceNetworkTables(tables = mapOf("127.0.0.1" to table))
        assertNull(verdict(stream(url = "http://127.0.0.1:8090/stream/film.mkv"), with = local))
        // An episode's text-only size without a one-episode file name: no size, so outside the rule.
        val packText = stream(sizeBytes = null, title = "Show.S02.2160p\n💾 60 GB")
        assertNull(streamLimitVerdict(packText, networkTest, tables, NetworkTransport.WIFI, 60, episode = true, emptyMap()))
    }

    @Test
    fun `tables count whether Automatic network is on or not`() {
        assertTrue(verdict(stream(), with = tables.copy(automatic = false))!!.hidden)
    }

    @Test
    fun `manual applies one figure to every stream with an estimate, torrents included`() {
        val manual = StreamLimitSettings(StreamLimitMode.MANUAL, manualMbps = 40)
        // A torrent with a text size, read in decimal units: 21 GB over 60 min = 46.7 Mbit/s.
        val torrent = stream(url = null, sizeBytes = null, infoHash = "a".repeat(40), title = "Film\n💾 21 GB")
        val onTorrent = verdict(torrent, settings = manual)!!
        assertEquals(40, onTorrent.figureMbps)
        assertNull(onTorrent.source)
        assertTrue(onTorrent.hidden)
        assertFalse(verdict(stream(sizeBytes = 15_000_000_000L), settings = manual)!!.hidden)
        assertNull(verdict(stream(sizeBytes = null), settings = manual))
        // An estimate exactly at the figure stays shown: 27 GB over 60 min = 60.0 Mbit/s.
        val atFigure = verdict(stream(sizeBytes = 27_000_000_000L), settings = manual.copy(manualMbps = 60))!!
        assertEquals(60.0, atFigure.estimateMbps, 0.0)
        assertFalse(atFigure.hidden)
    }

    @Test
    fun `off limits nothing`() {
        assertNull(verdict(stream(), settings = StreamLimitSettings(StreamLimitMode.OFF)))
    }

    @Test
    fun `measured speed is the fastest row rounded to nearest, as the table prints it`() {
        assertEquals(132, networkMeasuredMbps(132.4))
        assertEquals(133, networkMeasuredMbps(132.5))
        assertEquals(56, networkMeasuredMbps(55.9))
    }

    @Test
    fun `figure is 80 percent of the fastest row, rounded up, exact products not bumped`() {
        assertEquals(45, networkLimitFigureMbps(55.9)) // the Shield's energycdn.com, measured 56 (Spec 58 D1)
        assertEquals(45, networkLimitFigureMbps(56.0))
        assertEquals(72, networkLimitFigureMbps(90.0)) // 90 * 0.8 would be 72.00000000000001
        assertEquals(106, networkLimitFigureMbps(132.4))
        assertEquals(48, networkLimitFigureMbps(59.2))
        assertEquals(1, networkLimitFigureMbps(0.5))
    }

    @Test
    fun `a table without rows sets no figure`() {
        assertNull(verdict(stream(), with = ServiceNetworkTables(tables = mapOf("real-debrid.com" to table.copy(rows = emptyList())))))
    }

    @Test
    fun `settings name each service whose table counts on this link, in key order, with the verdicts' figure`() {
        val premiumize = ServiceTable(NetworkTransport.WIFI, rows = listOf(NetworkRow(1, 0, 59.2)))
        val several = ServiceNetworkTables(
            tables = mapOf(
                "real-debrid.com" to table,
                "premiumize.me" to premiumize,
                "alldebrid.com" to table.copy(transport = NetworkTransport.ETHERNET),
                "torbox.app" to table.copy(rows = emptyList())
            )
        )
        assertEquals(
            listOf(ServiceLimitFigure("premiumize.me", 48, 59), ServiceLimitFigure("real-debrid.com", 106, 132)),
            networkLimitFigures(several, NetworkTransport.WIFI)
        )
        // An unknown link now keeps every table with rows, as the verdicts do.
        assertEquals(
            listOf("alldebrid.com", "premiumize.me", "real-debrid.com"),
            networkLimitFigures(several, NetworkTransport.OTHER).map { it.serviceKey }
        )
        assertTrue(networkLimitFigures(ServiceNetworkTables(), NetworkTransport.WIFI).isEmpty())
        assertTrue(networkLimitFigures(tables, NetworkTransport.ETHERNET).isEmpty())
    }

    @Test
    fun `the figure Settings names for a service is the one its streams are compared against, on every link`() {
        val several = ServiceNetworkTables(
            tables = mapOf(
                "real-debrid.com" to table,
                "premiumize.me" to ServiceTable(NetworkTransport.WIFI, rows = listOf(NetworkRow(1, 0, 59.2))),
                "alldebrid.com" to table.copy(transport = NetworkTransport.ETHERNET),
                "debrid-link.com" to table.copy(transport = NetworkTransport.OTHER),
                "torbox.app" to table.copy(rows = emptyList())
            )
        )
        for (transport in NetworkTransport.entries) {
            val named = networkLimitFigures(several, transport).associate { it.serviceKey to (it.figureMbps to it.measuredMbps) }
            for (key in several.tables.keys) {
                val compared = verdict(stream(url = "https://download.$key/d/abc/film.mkv"), with = several, transport = transport)
                assertEquals("$key on $transport", compared?.let { it.figureMbps to it.source?.measuredMbps }, named[key])
            }
        }
    }

    @Test
    fun `a table no stream reaches is not named`() {
        // The request domain once served its own streams, then aliased to real-debrid.com (withTest keeps its table).
        val stale = ServiceNetworkTables(
            tables = mapOf("real-debrid.com" to table, "example.net" to table.copy(rows = listOf(NetworkRow(1, 0, 20.0)))),
            aliases = mapOf("example.net" to "real-debrid.com")
        )
        assertEquals(listOf("real-debrid.com"), networkLimitFigures(stale, NetworkTransport.WIFI).map { it.serviceKey })
        assertEquals(106, verdict(stream(url = "https://aiostreams.example.net/playback/film.mkv"), with = stale)!!.figureMbps)
        // Reached again through another domain's alias, it is named.
        val viaAlias = stale.copy(aliases = stale.aliases + ("other.org" to "example.net"))
        assertEquals(listOf("example.net", "real-debrid.com"), networkLimitFigures(viaAlias, NetworkTransport.WIFI).map { it.serviceKey })
    }

    @Test
    fun `stored mode names read back, anything else is the default`() {
        assertEquals(StreamLimitMode.MANUAL, StreamLimitMode.fromStored("MANUAL"))
        assertEquals(StreamLimitMode.NETWORK_TEST, StreamLimitMode.fromStored(null))
        assertEquals(StreamLimitMode.NETWORK_TEST, StreamLimitMode.fromStored("SOMETHING_NEW"))
    }
}
