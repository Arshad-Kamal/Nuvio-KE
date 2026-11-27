package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingCatalogReference
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 105 S6: MDBList's TVDB maps (rules, store), write numbering and the fill (§ S6 design N1-N5). */
class MdbListEpisodeNumberingTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeMdbListEngine()
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val session = MdbListSession(mdbListClient(engine, clock), accounts, MdbListQuota { clock.now })
    private val file get() = File(folder.root, "mdblist_sync/tvdb_maps.json")

    private companion object {
        const val SAVE_WAIT_MS = 10_001L
    }

    private fun reply(name: String) = mdbListJson.decodeFromString(MdbListTvdbMapReply.serializer(), tvdbMapFixture(name))

    private val conan by lazy { reply("tvdb-map-30983.json").toNumbering(null, 1_000L) }

    private suspend fun connected() =
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "key-one-12345", accountName = "a"))

    private fun TestScope.setup(): Pair<MdbListTvdbMapStore, MdbListEpisodeNumbering> {
        val store = tvdbMapStore(file, backgroundScope)
        return store to MdbListEpisodeNumbering(session, store, backgroundScope) { clock.now }
    }

    private fun episode(imdb: String, season: Int, number: Int, videoId: String? = "$imdb:$season:$number") = TrackingMediaReference(
        TrackingMediaKind.SHOW, ids = TrackingExternalIds(imdb = imdb), episode = TrackingEpisode(season, number),
        catalog = TrackingCatalogReference(imdb, "series", videoId)
    )

    private fun media(imdb: String?, tmdb: Long?) = MdbListMedia(ids = buildMap {
        imdb?.let { put("imdb", JsonPrimitive(it)) }
        tmdb?.let { put("tmdb", JsonPrimitive(it)) }
    })

    private fun mark(imdb: String?, tmdb: Long?, season: Int, episode: Int, at: String = "2025-11-13T00:00:00Z") =
        MdbListWatchedMark(media(imdb, tmdb), season, episode, at)

    private suspend fun MdbListEpisodeNumbering.number(profileId: Int, reference: TrackingMediaReference, itemTime: Long?) =
        toMdbList(profileId, reference, itemTime)?.reference

    // --- Rules ---

    @Test
    fun `a 200 keeps one-to-one links, and merged or tvdb-only episodes are unlinked`() {
        val aot = reply("tvdb-map-1429.json").toNumbering(null, 5L)
        assertTrue(aot.differs)
        assertEquals(116, aot.links.size)
        assertEquals(15, aot.unlinkedTvdb.size)
        assertEquals(0 to 36, aot.tmdbEpisode(4, 29))
        assertTrue(aot.knowsTvdb(0, 14)) // tvdb_only special: known to have no counterpart
        assertNull(aot.tmdbEpisode(0, 14))

        assertEquals(1_240, conan.links.size)
        assertEquals(65, conan.unlinkedTvdb.size)
        assertEquals(1 to 1221, conan.tmdbEpisode(34, 35))
        assertNull(conan.tmdbEpisode(20, 2)) // TVDB S20E2 and S20E3 share one TMDb episode
        assertTrue("20:2" in conan.unlinkedTvdb)
        assertEquals(1_246, conan.named.size)
    }

    @Test
    fun `spans and other-show rows are unlinked, and an episode a span covers is merged`() {
        val map = MdbListTvdbMapReply(
            episodes = listOf(
                MdbListTvdbMapEpisode(1, 1, 1, 1, spans = 2),
                MdbListTvdbMapEpisode(1, 2, 1, 2),
                MdbListTvdbMapEpisode(1, 3, 1, 3, showTmdb = 77),
                MdbListTvdbMapEpisode(1, 4, 1, 4)
            )
        ).toNumbering(null, 1L)
        assertEquals(listOf(1 to 4), map.links.map { it.tvdbSeason to it.tvdbEpisode }.map { (s, e) -> s to e })
        assertEquals(setOf("1:1", "1:2", "1:3"), map.unlinkedTvdb)
        assertEquals(setOf("1:1", "1:2", "1:4"), map.named)
    }

    @Test
    fun `a refetch keeps each unchanged link's time and the first fetch time`() {
        val old = MdbListTvdbMapReply(episodes = listOf(MdbListTvdbMapEpisode(1, 1, 1, 1))).toNumbering(null, 10L)
        val new = MdbListTvdbMapReply(
            episodes = listOf(MdbListTvdbMapEpisode(1, 1, 1, 1), MdbListTvdbMapEpisode(1, 2, 1, 2))
        ).toNumbering(old.copy(first404At = 15L), 20L)
        assertEquals(listOf(10L, 20L), new.links.map { it.linkedAt })
        assertEquals(10L, new.firstFetchedAt)
        assertEquals(20L, new.fetchedAt)
        assertNull(new.first404At)
    }

    @Test
    fun `a differing map turns matching only after two 404s a day apart`() {
        val day = MDBLIST_MAP_FRESH_MS
        assertFalse(null.after404(1L).differs)
        assertEquals(9L, MdbListShowNumbering(differs = false, fetchedAt = 1L).after404(9L).fetchedAt)
        val first = conan.after404(5_000L)
        assertTrue(first.differs)
        assertEquals(5_000L, first.first404At)
        assertEquals(first, first.after404(5_000L + day - 1))
        val second = first.after404(5_000L + day)
        assertFalse(second.differs)
        assertEquals(5_000L + day, second.fetchedAt)
    }

    @Test
    fun `rows move into the addon's numbering, and rows that cannot be shown are withheld`() {
        val maps = MdbListTvdbMapsState(
            aliases = mapOf(CONAN_IMDB to CONAN_TMDB, "tt2" to 2L, "tt3" to 3L),
            shows = mapOf(CONAN_TMDB to conan, 2L to MdbListShowNumbering(differs = false, fetchedAt = 1L))
        )
        val snapshot = MdbListSnapshot(
            marks = listOf(
                mark(CONAN_IMDB, CONAN_TMDB, 1, 1221),
                mark(CONAN_IMDB, 9L, 1, 1221), // names another TMDb show than the alias
                mark(CONAN_IMDB, null, 1, 99_999), // no TVDB link
                mark("tt2", 2L, 3, 4), // matching numbering
                mark("tt3", 3L, 1, 1), // map unknown
                mark("tt4", null, 1, 1), // alias unknown
                mark(null, 55L, 2, 2), // tmdb: title
                MdbListWatchedMark(media("tt5", null)) // a film
            ),
            playback = listOf(
                MdbListPlayback(id = 1, type = "episode", show = media(CONAN_IMDB, CONAN_TMDB), episode = MdbListPlaybackEpisode(1, 1220)),
                MdbListPlayback(id = 2, type = "episode", show = media(CONAN_IMDB, CONAN_TMDB), episode = MdbListPlaybackEpisode(0, 15)), // merged
                MdbListPlayback(id = 3, type = "episode", show = media("tt3", 3L), episode = MdbListPlaybackEpisode(1, 1)),
                MdbListPlayback(id = 4, type = "movie", movie = media("tt5", null))
            )
        ).inAddonNumbering(maps)
        assertEquals(
            listOf(Triple(CONAN_IMDB, 34, 35), Triple("tt2", 3, 4), Triple(null, 2, 2), Triple("tt5", null, null)),
            snapshot.marks.map { Triple(it.media.imdb, it.season, it.episode) }
        )
        // TMDb S1E1220 is TVDB S34E34; the merged episode and the unknown show are withheld; the film passes.
        assertEquals(listOf(1L to (34 to 34), 4L to null), snapshot.playback.map { p -> p.id to p.episode?.let { it.season!! to it.number!! } })
    }

    @Test
    fun `a local entry is kept when MDBList could not have it`() {
        val linkedLater = conan.copy(
            links = conan.links.map { if (it.tvdbSeason == 34 && it.tvdbEpisode == 35) it.copy(linkedAt = 9_000L) else it }
        )
        val maps = MdbListTvdbMapsState(
            aliases = mapOf(CONAN_IMDB to CONAN_TMDB, "tt2" to 2L),
            shows = mapOf(CONAN_TMDB to linkedLater, 2L to MdbListShowNumbering(differs = false, fetchedAt = 1L))
        )
        assertTrue(maps.retainsLocalEpisode("kitsu:1", 1, 1, 5L)) // a title MDBList cannot name
        assertFalse(maps.retainsLocalEpisode("tt9", 1, 1, 5_000L)) // unknown: hidden
        assertFalse(maps.retainsLocalEpisode("tt2", 1, 1, 5_000L)) // matching: MDBList holds it
        assertFalse(maps.retainsLocalEpisode(CONAN_IMDB, null, null, 5_000L))
        assertTrue(maps.retainsLocalEpisode(CONAN_IMDB, 20, 2, 5L)) // no counterpart
        assertTrue(maps.retainsLocalEpisode(CONAN_IMDB, 34, 35, 5_000L)) // played inside the link lag
        assertFalse(maps.retainsLocalEpisode(CONAN_IMDB, 34, 35, 500L)) // before the first fetch: old history
        assertFalse(maps.retainsLocalEpisode(CONAN_IMDB, 34, 35, 9_000L)) // after the link
        // A write sent the episode at 4,000: from then on MDBList holds it.
        val sent = maps.withLinkSentAt(CONAN_TMDB, 34, 35, 4_000L)
        assertFalse(sent.retainsLocalEpisode(CONAN_IMDB, 34, 35, 5_000L))
        assertTrue(sent.retainsLocalEpisode(CONAN_IMDB, 34, 35, 3_000L))
        assertEquals(sent, sent.withLinkSentAt(CONAN_TMDB, 34, 35, 6_000L)) // never moved later
    }

    @Test
    fun `N1 - the episode id names its numbering, else the title's ids`() {
        assertEquals(MdbListEpisodeOrigin.Tvdb("tt1"), mdbListEpisodeOrigin(episode("tt1", 1, 1)))
        assertEquals(MdbListEpisodeOrigin.Tvdb("tt1"), mdbListEpisodeOrigin(episode("tt1", 1, 1, videoId = "tt1")))
        assertEquals(MdbListEpisodeOrigin.None, mdbListEpisodeOrigin(episode("tt1", 1, 1, videoId = "kitsu:5:1")))
        assertEquals(MdbListEpisodeOrigin.Tmdb, mdbListEpisodeOrigin(episode("tt1", 1, 1, videoId = "tmdb:5:1:1")))
        val tmdbShow = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = 5), episode = TrackingEpisode(1, 1))
        assertEquals(MdbListEpisodeOrigin.Tmdb, mdbListEpisodeOrigin(tmdbShow))
        val tvdbShow = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tvdb = "5"), episode = TrackingEpisode(1, 1))
        assertEquals(MdbListEpisodeOrigin.None, mdbListEpisodeOrigin(tvdbShow))
    }

    // --- Store ---

    @Test
    fun `the store keeps its maps across instances and reads an unreadable file as empty`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to CONAN_TMDB)).withShow(CONAN_TMDB, conan) }
        testScheduler.advanceTimeBy(SAVE_WAIT_MS) // background work: the save waits 10 s
        val again = tvdbMapStore(file, backgroundScope).current()
        assertEquals(CONAN_TMDB, again.aliases[CONAN_IMDB])
        assertEquals(conan, again.shows[CONAN_TMDB])
        assertEquals(1 to 1221, again.showFor(CONAN_IMDB)?.tmdbEpisode(34, 35))

        file.writeText("{not json")
        assertEquals(MdbListTvdbMapsState(), tvdbMapStore(file, backgroundScope).current())
    }

    // --- Write numbering (N5) ---

    @Test
    fun `a write looks up the alias and map once, then numbers from the store`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (_, numbering) = setup()
        engine.reply(200, tvdbMapFixture("imdb-show-tt0131179.json"))
        engine.reply(200, tvdbMapFixture("tvdb-map-30983.json"))
        val numbered = numbering.number(1, episode(CONAN_IMDB, 34, 35).copy(ids = TrackingExternalIds(imdb = CONAN_IMDB, tvdb = "72454")), clock.now)
        assertEquals(1 to 1221, numbered?.episode?.let { it.season to it.number })
        assertEquals(CONAN_TMDB, numbered?.ids?.tmdb)
        assertNull(numbered?.ids?.tvdb)
        assertEquals(listOf("/imdb/show/$CONAN_IMDB/", "/shows/$CONAN_TMDB/tvdb-map/"), engine.calls.map { it.path })

        assertNull(numbering.number(1, episode(CONAN_IMDB, 20, 2), clock.now)) // merged: no counterpart
        assertNull(numbering.number(1, episode(CONAN_IMDB, 1, 1, videoId = "kitsu:1:1"), clock.now))
        assertEquals(2, engine.calls.size)
        assertEquals(1 to 1221, numbering.removalEpisode(1, CONAN_IMDB, 34, 35))
    }

    @Test
    fun `a matching show passes its numbers under its TMDb id, a tmdb or film write passes untouched`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf("tt0903747" to 1396L)) }
        engine.reply(404, tvdbMapFixture("tvdb-map-404.json"))
        val numbered = numbering.number(1, episode("tt0903747", 2, 3), clock.now)
        assertEquals(2 to 3, numbered?.episode?.let { it.season to it.number })
        assertEquals(1396L, numbered?.ids?.tmdb)
        val tmdbShow = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = 5), episode = TrackingEpisode(1, 1))
        assertEquals(tmdbShow, numbering.number(1, tmdbShow, clock.now))
        val film = TrackingMediaReference(TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "tt0133093"))
        assertEquals(film, numbering.number(1, film, clock.now))
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a failed refetch uses the stored map, and with none nothing is sent`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to CONAN_TMDB, "tt7" to 7L)).withShow(CONAN_TMDB, conan) }
        clock.now = 1_000L + MDBLIST_MAP_FRESH_MS // stale
        engine.reply(400)
        assertEquals(1 to 1221, numbering.number(1, episode(CONAN_IMDB, 34, 35), clock.now)?.episode?.let { it.season to it.number })
        // Within the failure's hour the stored map answers without a request.
        assertNotNull(numbering.number(1, episode(CONAN_IMDB, 34, 35), clock.now))
        assertEquals(1, engine.calls.size)
        engine.reply(400)
        assertNull(numbering.number(1, episode("tt7", 1, 1), clock.now))
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `a map is refetched for an episode it does not know once it is an hour old`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to CONAN_TMDB)).withShow(CONAN_TMDB, conan) }
        clock.now = 1_000L + 30 * 60 * 1_000L
        assertNull(numbering.number(1, episode(CONAN_IMDB, 34, 36), clock.now)) // unknown, map 30 min old: no fetch
        assertEquals(0, engine.calls.size)
        clock.now = 1_000L + 60 * 60 * 1_000L
        engine.reply(200, tvdbMapFixture("tvdb-map-30983.json"))
        numbering.number(1, episode(CONAN_IMDB, 34, 36), clock.now)
        assertEquals(listOf("/shows/$CONAN_TMDB/tvdb-map/"), engine.calls.map { it.path })
    }

    @Test
    fun `a mark dated before its link moves the link time back once sent`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to CONAN_TMDB)) }
        engine.reply(200, tvdbMapFixture("tvdb-map-30983.json"))
        val numbered = numbering.toMdbList(1, episode(CONAN_IMDB, 34, 35), clock.now - 5_000L)!!
        assertEquals(clock.now, store.current().shows.getValue(CONAN_TMDB).byTvdb.getValue("34:35").linkedAt) // not sent yet
        numbered.onSent()
        assertEquals(clock.now - 5_000L, store.current().shows.getValue(CONAN_TMDB).byTvdb.getValue("34:35").linkedAt)
        assertEquals(clock.now, store.current().shows.getValue(CONAN_TMDB).byTvdb.getValue("34:34").linkedAt)
    }

    @Test
    fun `writes waiting on one show share its fetch, and a waiter giving up does not cancel it`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to CONAN_TMDB)) }
        val release = CompletableDeferred<Unit>()
        engine.onCall = { release.await() }
        engine.reply(200, tvdbMapFixture("tvdb-map-30983.json"))
        val first = launch { numbering.number(1, episode(CONAN_IMDB, 34, 35), clock.now) }
        val second = async { numbering.number(1, episode(CONAN_IMDB, 34, 34), clock.now) }
        first.cancel()
        release.complete(Unit)
        assertEquals(1 to 1220, second.await()?.episode?.let { it.season to it.number })
        assertEquals(1, engine.calls.size)
        assertNotNull(store.current().shows[CONAN_TMDB])
    }

    // --- Fill (N3) ---

    @Test
    fun `a fill fetches what the snapshot's shows lack, playbacks first, and nothing more next time`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        val snapshot = MdbListSnapshot(
            marks = listOf(mark("tt0903747", null, 1, 1), mark(CONAN_IMDB, CONAN_TMDB, 1, 1221)),
            playback = listOf(
                MdbListPlayback(id = 1, pausedAt = "2025-11-12T00:00:00Z", type = "episode", show = media(CONAN_IMDB, CONAN_TMDB),
                    episode = MdbListPlaybackEpisode(1, 1222))
            )
        )
        engine.replyTo(MdbListHttpMethod.GET, "/shows/$CONAN_TMDB/tvdb-map/", 200, tvdbMapFixture("tvdb-map-30983.json"))
        engine.replyTo(MdbListHttpMethod.GET, "/imdb/show/tt0903747/", 200, """{"ids":{"imdb":"tt0903747","tmdb":1396}}""")
        engine.replyTo(MdbListHttpMethod.GET, "/shows/1396/tvdb-map/", 404, tvdbMapFixture("tvdb-map-404.json"))
        numbering.fill(1, snapshot)
        // Conan's alias comes from its rows (no lookup); Breaking Bad's rows name no TMDb id.
        assertEquals(listOf("/shows/$CONAN_TMDB/tvdb-map/", "/imdb/show/tt0903747/", "/shows/1396/tvdb-map/"), engine.calls.map { it.path })
        assertFalse(store.current().shows.getValue(1396L).differs)
        numbering.fill(1, snapshot)
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun `a day-old map is refetched only when a refresh changed the show's rows`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf("tt2" to 2L)).withShow(2L, MdbListShowNumbering(differs = false, fetchedAt = clock.now)) }
        clock.now += MDBLIST_MAP_FRESH_MS
        val before = MdbListSnapshot(marks = listOf(mark("tt2", 2L, 1, 1)))
        numbering.fill(1, before) // first sight: no trigger
        numbering.fill(1, before) // unchanged
        assertEquals(0, engine.calls.size)
        engine.reply(404)
        numbering.fill(1, before.copy(marks = before.marks + mark("tt2", 2L, 1, 2)))
        assertEquals(listOf("/shows/2/tvdb-map/"), engine.calls.map { it.path })
    }

    @Test
    fun `a new row naming an episode the differing map lacks refetches it an hour on`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to CONAN_TMDB)).withShow(CONAN_TMDB, conan.copy(fetchedAt = clock.now)) }
        val snapshot = MdbListSnapshot(marks = listOf(mark(CONAN_IMDB, CONAN_TMDB, 1, 1300, at = "2025-11-14T00:00:00Z")))
        numbering.fill(1, snapshot)
        assertEquals(0, engine.calls.size) // the map is under an hour old
        clock.now += 60 * 60 * 1_000L
        engine.reply(200, tvdbMapFixture("tvdb-map-30983.json"))
        numbering.fill(1, snapshot)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `rows that all name another TMDb id replace the alias and fetch its map`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to 1L)).withShow(1L, MdbListShowNumbering(differs = false, fetchedAt = clock.now)) }
        engine.reply(200, tvdbMapFixture("tvdb-map-30983.json"))
        numbering.fill(1, MdbListSnapshot(marks = listOf(mark(CONAN_IMDB, CONAN_TMDB, 1, 1221))))
        assertEquals(CONAN_TMDB, store.current().aliases[CONAN_IMDB])
        assertEquals(listOf("/shows/$CONAN_TMDB/tvdb-map/"), engine.calls.map { it.path })
    }

    @Test
    fun `a fill stops at a rate refusal, makes at most 100 requests, and waits an hour after a failure`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (_, numbering) = setup()
        val many = MdbListSnapshot(marks = (1..120).map { mark("tt$it", it.toLong(), 1, 1) })
        engine.reply(429, DAILY_LIMIT_BODY)
        numbering.fill(1, many)
        assertEquals(1, engine.calls.size)

        clock.now = nextMdbListResetEpochMs(clock.now) + 1
        repeat(120) { engine.reply(400) }
        numbering.fill(1, many)
        assertEquals(101, engine.calls.size)
        numbering.fill(1, many) // the 100 that failed wait an hour; the 20 left are asked
        assertEquals(121, engine.calls.size)
        clock.now += 60 * 60 * 1_000L
        repeat(100) { engine.reply(400) }
        numbering.fill(1, many)
        assertEquals(221, engine.calls.size)
    }

    @Test
    fun `a TVDB episode listed twice is not linked`() {
        val map = MdbListTvdbMapReply(
            episodes = listOf(MdbListTvdbMapEpisode(1, 1, 1, 1), MdbListTvdbMapEpisode(1, 1, 1, 2), MdbListTvdbMapEpisode(1, 2, 1, 3))
        ).toNumbering(null, 1L)
        assertEquals(listOf("1:2"), map.links.map { episodeKey(it.tvdbSeason, it.tvdbEpisode) })
        assertTrue("1:1" in map.unlinkedTvdb)
    }

    @Test
    fun `a 404 on a differing map keeps it, holds it an hour, and a second 404 a day on makes it matching`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to CONAN_TMDB)).withShow(CONAN_TMDB, conan) }
        clock.now = 1_000L + MDBLIST_MAP_FRESH_MS
        engine.reply(404)
        assertEquals(1 to 1221, numbering.number(1, episode(CONAN_IMDB, 34, 35), clock.now)?.episode?.let { it.season to it.number })
        assertEquals(clock.now, store.current().shows.getValue(CONAN_TMDB).first404At)
        numbering.number(1, episode(CONAN_IMDB, 34, 35), clock.now) // held: no request
        numbering.fill(1, MdbListSnapshot(marks = listOf(mark(CONAN_IMDB, CONAN_TMDB, 1, 1221))))
        assertEquals(1, engine.calls.size)
        clock.now += MDBLIST_MAP_FRESH_MS
        engine.reply(404)
        assertEquals(34 to 35, numbering.number(1, episode(CONAN_IMDB, 34, 35), clock.now)?.episode?.let { it.season to it.number })
        assertFalse(store.current().shows.getValue(CONAN_TMDB).differs)
    }

    @Test
    fun `a failed or unknown alias lookup is held an hour`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        val snapshot = MdbListSnapshot(marks = listOf(mark("tt1", null, 1, 1), mark("tt2", null, 1, 1)))
        engine.replyTo(MdbListHttpMethod.GET, "/imdb/show/tt1/", 400)
        engine.replyTo(MdbListHttpMethod.GET, "/imdb/show/tt2/", 404)
        numbering.fill(1, snapshot)
        numbering.fill(1, snapshot)
        assertEquals(2, engine.calls.size)
        assertTrue(store.current().aliases.isEmpty())
        clock.now += 60 * 60 * 1_000L
        engine.reply(400)
        engine.reply(400)
        numbering.fill(1, snapshot)
        assertEquals(4, engine.calls.size)
    }

    @Test
    fun `the five-minute limit and a missing key stop a fill without holding anything`() = runTest(UnconfinedTestDispatcher()) {
        val (_, numbering) = setup()
        val snapshot = MdbListSnapshot(marks = listOf(mark("tt1", 1L, 1, 1), mark("tt2", 2L, 1, 1)))
        numbering.fill(1, snapshot) // no key: nothing sent
        assertEquals(0, engine.calls.size)
        connected()
        engine.reply(429, RATE_LIMIT_BODY)
        numbering.fill(1, snapshot)
        assertEquals(1, engine.calls.size)
        engine.reply(404)
        engine.reply(404)
        numbering.fill(1, snapshot) // neither show was held
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun `a row change whose refetch failed is asked again after the hold`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (store, numbering) = setup()
        store.update { it.copy(aliases = mapOf("tt2" to 2L)).withShow(2L, MdbListShowNumbering(differs = false, fetchedAt = clock.now)) }
        clock.now += MDBLIST_MAP_FRESH_MS
        val before = MdbListSnapshot(marks = listOf(mark("tt2", 2L, 1, 1)))
        val after = before.copy(marks = before.marks + mark("tt2", 2L, 1, 2))
        numbering.fill(1, before)
        engine.reply(400)
        numbering.fill(1, after)
        numbering.fill(1, after) // held
        assertEquals(1, engine.calls.size)
        clock.now += 60 * 60 * 1_000L
        engine.reply(404)
        numbering.fill(1, after)
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `fills queued during a running one collapse into one per profile`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val (_, numbering) = setup()
        val release = CompletableDeferred<Unit>()
        engine.onCall = { release.await() }
        var reads = 0
        val snapshot = MdbListSnapshot(marks = listOf(mark("tt1", 1L, 1, 1)))
        repeat(2) { engine.reply(404) }
        numbering.launchFill(1) { reads++; snapshot } // runs, waits on its request
        repeat(3) { numbering.launchFill(1) { reads++; snapshot } }
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(2, reads)
    }

    @Test
    fun `a key change's snapshot clear leaves the maps file`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = setup()
        store.update { it.copy(aliases = mapOf(CONAN_IMDB to CONAN_TMDB)) }
        testScheduler.advanceTimeBy(SAVE_WAIT_MS) // background work: the save waits 10 s
        assertTrue(file.isFile)
        MdbListSyncStore(file.parentFile!!, kotlinx.coroutines.Dispatchers.Unconfined).clear(1)
        assertTrue(file.isFile)
    }

    @Test
    fun `a burst of changes is saved with the newest state`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = setup()
        store.update { it.copy(aliases = mapOf("tt1" to 1L)) }
        testScheduler.advanceTimeBy(5_000L)
        store.update { it.copy(aliases = it.aliases + ("tt2" to 2L)) }
        assertFalse(file.isFile)
        testScheduler.advanceTimeBy(SAVE_WAIT_MS)
        assertEquals(mapOf("tt1" to 1L, "tt2" to 2L), tvdbMapStore(file, backgroundScope).current().aliases)
    }
}
