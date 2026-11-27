package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingCatalogReference
import com.nuvio.tv.core.tracking.TrackingEpisode
import org.junit.Assert.assertNull
import kotlinx.serialization.json.JsonPrimitive
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 106 S2b: scrobbles, watched marks, and the marks kept while the allowance is spent. */
class SimklWritesTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeSimklEngine()
    private val clock = FakeClock()
    private val client = simklClient(engine, clock)
    private val accounts = accountStore()
    private val quota = SimklQuota { clock.now }
    private val session = SimklSession(client, SimklTokens(client, TEST_CONFIG, accounts) { clock.now }, quota)
    private val pending by lazy { SimklPendingMarks(folder.root, Dispatchers.Unconfined) }
    private val refused = mutableListOf<Int>()
    private var sleep: suspend (Long) -> Unit = clock.sleep
    private val held = mutableListOf<Int>()
    private val writer by lazy {
        SimklHistoryWriter(session, pending, { clock.now }, { sleep(it) }, logRefused = { refused += it }, logHeld = { held += it })
    }
    private val scrobbler by lazy { SimklScrobbler(session, writer, MutableStateFlow(1)) }

    private val day = 24L * 60 * 60 * 1_000

    private suspend fun signedIn() =
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("at", "rt", clock.now + 7 * day, "arshad.k"))

    private fun film(imdb: String) = TrackingMediaReference(TrackingMediaKind.MOVIE, "Film $imdb", 2020, TrackingExternalIds(imdb = imdb))
    private fun episode(season: Int?, number: Int, kind: TrackingMediaKind = TrackingMediaKind.SHOW) = TrackingMediaReference(
        kind, "Show", 2021, TrackingExternalIds(tmdb = 7, kitsu = 3L.takeIf { kind == TrackingMediaKind.ANIME }),
        TrackingEpisode(season = season, number = number)
    )

    private fun json(body: String?): JsonObject = Json.parseToJsonElement(checkNotNull(body)).jsonObject

    private fun spendTheDay() = engine.reply(429, """{"error":"user_limit_exceeded"}""")

    // Bodies

    @Test
    fun `history body groups episodes by title and season with UTC watch times`() {
        val at = 1_763_366_463_000L // 2025-11-17T08:01:03Z
        val body = json(
            simklHistoryBody(
                listOf(
                    TrackingHistoryItem(film("tt1"), at),
                    TrackingHistoryItem(episode(1, 2), at),
                    TrackingHistoryItem(episode(1, 3), at),
                    TrackingHistoryItem(episode(2, 1), at),
                    TrackingHistoryItem(episode(3, 4, TrackingMediaKind.ANIME), at),
                    TrackingHistoryItem(episode(null, 25, TrackingMediaKind.ANIME).copy(ids = TrackingExternalIds(mal = 9)), at)
                ),
                withWatchedAt = true
            )
        )
        val movie = body["movies"]!!.jsonArray.single().jsonObject
        assertEquals("tt1", movie["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals("2025-11-17T08:01:03Z", movie["watched_at"]!!.jsonPrimitive.content)

        val shows = body["shows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, shows.size)
        val seasons = shows[0]["seasons"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 2), seasons.map { it["number"]!!.jsonPrimitive.content.toInt() })
        assertEquals(listOf("2", "3"), seasons[0]["episodes"]!!.jsonArray.map { it.jsonObject["number"]!!.jsonPrimitive.content })
        assertEquals("2025-11-17T08:01:03Z", seasons[0]["episodes"]!!.jsonArray[0].jsonObject["watched_at"]!!.jsonPrimitive.content)
        assertFalse("use_tvdb_anime_seasons" in shows[0])

        assertEquals("true", shows[1]["use_tvdb_anime_seasons"]!!.jsonPrimitive.content)
        assertEquals("25", shows[2]["episodes"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.content)
        assertFalse("seasons" in shows[2])
    }

    @Test
    fun `an anime episode named by its entry's own ids is sent with the entry's numbering, never TVDB's`() {
        val kitsuEpisode = TrackingMediaReference(
            TrackingMediaKind.ANIME, "AoT S2", 2017, TrackingExternalIds(kitsu = 8671), TrackingEpisode(season = 1, number = 5)
        )
        // History and its removal: flat episodes, no seasons, no TVDB flag (else Simkl reads TVDB S1E5: another entry).
        listOf(true, false).forEach { withWatchedAt ->
            val show = json(simklHistoryBody(listOf(TrackingHistoryItem(kitsuEpisode, 1_763_366_463_000L)), withWatchedAt))["shows"]!!
                .jsonArray.single().jsonObject
            assertEquals("5", show["episodes"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.content)
            assertFalse("seasons" in show)
            assertFalse("use_tvdb_anime_seasons" in show)
        }
        val scrobble = json(simklScrobbleBody(TrackingScrobbleEvent(kitsuEpisode, 50.0)))
        assertTrue("anime" in scrobble && "show" !in scrobble)
        assertFalse("season" in scrobble["episode"]!!.jsonObject)
        assertEquals("5", scrobble["episode"]!!.jsonObject["number"]!!.jsonPrimitive.content)
    }

    private fun anime(ids: TrackingExternalIds, season: Int? = 1, number: Int = 5, kind: TrackingMediaKind = TrackingMediaKind.ANIME) =
        TrackingMediaReference(kind, "AoT S2", 2017, ids, TrackingEpisode(season = season, number = number))

    @Test
    fun `only per-entry anime ids go flat, a show-wide id or no id at all keeps the TVDB seasons`() {
        fun show(media: TrackingMediaReference) =
            json(simklHistoryBody(listOf(TrackingHistoryItem(media, 1L)), withWatchedAt = true))["shows"]!!.jsonArray.single().jsonObject
        listOf(TrackingExternalIds(mal = 25777), TrackingExternalIds(anilist = 20958), TrackingExternalIds(anidb = 10944)).forEach { ids ->
            assertFalse(ids.toString(), "seasons" in show(anime(ids)))
        }
        listOf(TrackingExternalIds(kitsu = 8671, tvdb = "267440"), TrackingExternalIds(kitsu = 8671, imdb = "tt2560140"), TrackingExternalIds())
            .forEach { ids ->
                val body = show(anime(ids, season = 2))
                assertEquals(ids.toString(), "2", body["seasons"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.content)
                assertEquals(ids.toString(), "true", body["use_tvdb_anime_seasons"]!!.jsonPrimitive.content)
            }
    }

    @Test
    fun `an entry-numbered special is never sent, as a mark or a scrobble`() = runTest {
        signedIn()
        val special = anime(TrackingExternalIds(kitsu = 8671), season = 0, number = 1)
        assertEquals(1, writer.addToHistory(1, listOf(TrackingHistoryItem(special, 1L))).attemptedCount)
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(special, 95.0))
        assertTrue(engine.calls.isEmpty())
        val body = json(simklHistoryBody(listOf(TrackingHistoryItem(special, 1L), TrackingHistoryItem(film("tt1"), 1L)), withWatchedAt = true))
        assertTrue(body["shows"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `an unmark of an entry-numbered anime episode is held, nothing sent, nothing kept, a kept mark dropped (Q4)`() = runTest {
        signedIn()
        val kitsu = anime(TrackingExternalIds(kitsu = 8671))
        spendTheDay()
        writer.addToHistory(1, listOf(TrackingHistoryItem(kitsu, 1L))) // kept for later
        assertEquals(1, pending.get(1).size)
        engine.calls.clear()

        assertEquals(1, writer.removeFromHistory(1, listOf(kitsu)).attemptedCount)
        assertTrue(engine.calls.isEmpty())
        assertTrue(pending.get(1).isEmpty())
        assertEquals(listOf(1), held)

        // After the reset, a mixed unmark sends only the rest.
        clock.now += day
        engine.reply(201, "{}")
        writer.removeFromHistory(1, listOf(kitsu, episode(1, 2).copy(catalog = TrackingCatalogReference("tmdb:7", "series", "tmdb:7:1:2"))))
        val sent = json(engine.calls.single().body)["shows"]!!.jsonArray
        assertEquals(1, sent.size)
        assertEquals("7", sent.single().jsonObject["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a held unmark kept before the hold is dropped at replay, never sent`() = runTest {
        signedIn()
        pending.remove(1, listOf(anime(TrackingExternalIds(kitsu = 8671))))
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        engine.reply(201, "{}")
        writer.replayPending(1)
        assertEquals(listOf("/sync/history"), engine.calls.map { it.path })
        assertTrue(pending.get(1).isEmpty())
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `an episode whose own video id is per-entry goes flat with only that id, even under an IMDb parent (D17)`() {
        val media = buildTrackingMediaReference("series", "tt4940456", videoId = "mal:42203:7", seasonNumber = 2, episodeNumber = 19)
        val show = json(simklHistoryBody(listOf(TrackingHistoryItem(media, 1L)), withWatchedAt = false))["shows"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("mal"), show["ids"]!!.jsonObject.keys)
        assertEquals("7", show["episodes"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.content)
        assertFalse("seasons" in show || "use_tvdb_anime_seasons" in show)
        val scrobble = json(simklScrobbleBody(TrackingScrobbleEvent(media, 50.0)))
        assertEquals(setOf("mal"), scrobble["anime"]!!.jsonObject["ids"]!!.jsonObject.keys)
        assertEquals("7", scrobble["episode"]!!.jsonObject["number"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a TVDB-style anime episode is sent with its show-wide ids only (D17)`() {
        val media = anime(TrackingExternalIds(imdb = "tt2560140", kitsu = 8671, mal = 25777, simkl = 9), season = 2)
        val show = json(simklHistoryBody(listOf(TrackingHistoryItem(media, 1L)), withWatchedAt = true))["shows"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("imdb"), show["ids"]!!.jsonObject.keys)
        assertEquals("true", show["use_tvdb_anime_seasons"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an anime unmark goes by the Simkl id of the one synced entry holding the watch (D16)`() = runTest {
        signedIn()
        val holding = SimklEntry(
            type = SimklMediaType.ANIME, status = SimklListStatus.WATCHING,
            show = SimklMedia(ids = mapOf("simkl" to JsonPrimitive(1_579_947), "kitsu" to JsonPrimitive(44240))),
            seasons = listOf(SimklSeason(1, listOf(SimklEpisode(number = 1))))
        )
        val resolving = SimklHistoryWriter(session, pending, { clock.now }, clock.sleep, snapshotOf = { SimklSnapshot(entries = listOf(holding)) })
        engine.reply(201, "{}")
        resolving.removeFromHistory(1, listOf(anime(TrackingExternalIds(kitsu = 44240), number = 1)))
        val show = json(engine.calls.single().body)["shows"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("simkl"), show["ids"]!!.jsonObject.keys)
        assertEquals("1579947", show["ids"]!!.jsonObject["simkl"]!!.jsonPrimitive.content)
        assertEquals("1", show["episodes"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.content)
        assertFalse("seasons" in show || "use_tvdb_anime_seasons" in show)
    }

    @Test
    fun `the holding entry is the only synced anime entry with that id and the episode watched in season 1`() {
        fun entry(simkl: Long, kitsu: Long, season: Int = 1, episodes: List<Int> = listOf(1), type: SimklMediaType = SimklMediaType.ANIME) =
            SimklEntry(
                type = type, status = SimklListStatus.WATCHING,
                show = SimklMedia(ids = mapOf("simkl" to JsonPrimitive(simkl), "kitsu" to JsonPrimitive(kitsu))),
                seasons = listOf(SimklSeason(season, episodes.map { SimklEpisode(number = it) }))
            )
        val wanted = SimklEntryEpisode("kitsu", 44240, 1)
        assertEquals(1_579_947L, simklEntryHolding(SimklSnapshot(entries = listOf(entry(1_579_947, 44240), entry(1_120_029, 42422))), wanted))
        assertNull(simklEntryHolding(SimklSnapshot(entries = listOf(entry(1, 44240), entry(2, 44240))), wanted))
        assertNull(simklEntryHolding(SimklSnapshot(entries = listOf(entry(1, 44240, episodes = listOf(2)))), wanted))
        assertNull(simklEntryHolding(SimklSnapshot(entries = listOf(entry(1, 44240, season = 2))), wanted))
        assertNull(simklEntryHolding(SimklSnapshot(entries = listOf(entry(1, 44240, type = SimklMediaType.SHOWS))), wanted))
        assertNull(simklEntryHolding(null, wanted))
    }

    @Test
    fun `a kept mark keeps its video id, so it replays flat by its entry like the live one (D17)`() = runTest {
        signedIn()
        val media = buildTrackingMediaReference("series", "tt4940456", videoId = "mal:42203:7", seasonNumber = 2, episodeNumber = 19)
        pending.add(1, listOf(TrackingHistoryItem(media, 1L)))
        assertEquals("mal:42203:7", SimklPendingMarks(folder.root, Dispatchers.Unconfined).get(1).single().videoId)
        engine.reply(201, "{}")
        writer.replayPending(1)
        val show = json(engine.calls.single().body)["shows"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("mal"), show["ids"]!!.jsonObject.keys)
        assertEquals("7", show["episodes"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a fully held unmark counts as no send`() = runTest {
        signedIn()
        val before = writer.sendCount()
        writer.removeFromHistory(1, listOf(anime(TrackingExternalIds(kitsu = 8671))))
        assertEquals(before, writer.sendCount())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `an episode unmark by a show-wide id is held when a synced anime series carries the title (D19)`() = runTest {
        signedIn()
        val animeEntry = SimklEntry(
            type = SimklMediaType.ANIME, status = SimklListStatus.WATCHING,
            show = SimklMedia(ids = mapOf("simkl" to JsonPrimitive(1_367_345), "imdb" to JsonPrimitive("tt4940456"), "mal" to JsonPrimitive(42203)))
        )
        val guarded = SimklHistoryWriter(session, pending, { clock.now }, clock.sleep, snapshotOf = { SimklSnapshot(entries = listOf(animeEntry)) })
        // As production builds it: a catalog, its video id unknown (or only the bare parent id).
        val byImdb = buildTrackingMediaReference("series", "tt4940456", seasonNumber = 2, episodeNumber = 19)
        val byParentId = buildTrackingMediaReference("series", "tt4940456", videoId = "tt4940456", seasonNumber = 2, episodeNumber = 19)
        guarded.removeFromHistory(1, listOf(byParentId))
        guarded.removeFromHistory(1, listOf(byImdb))
        assertTrue(engine.calls.isEmpty())

        // A show Simkl files under shows, and a film, go as before.
        val show = TrackingMediaReference(TrackingMediaKind.SHOW, "BB", 2008, TrackingExternalIds(imdb = "tt0903747"), TrackingEpisode(1, 2))
        engine.reply(201, "{}")
        guarded.removeFromHistory(1, listOf(show))
        assertEquals(listOf("/sync/history/remove"), engine.calls.map { it.path })
        assertFalse(simklAnimeTitleHeld(SimklSnapshot(entries = listOf(animeEntry)), film("tt4940456")))
    }

    @Test
    fun `an unmark with a known video id that is not per-entry goes, as its mark did (Q9)`() = runTest {
        signedIn()
        val animeEntry = SimklEntry(
            type = SimklMediaType.ANIME, status = SimklListStatus.WATCHING,
            show = SimklMedia(ids = mapOf("simkl" to JsonPrimitive(1_367_345), "imdb" to JsonPrimitive("tt4940456")))
        )
        val guarded = SimklHistoryWriter(session, pending, { clock.now }, clock.sleep, snapshotOf = { SimklSnapshot(entries = listOf(animeEntry)) })
        val cinemeta = buildTrackingMediaReference("series", "tt4940456", videoId = "tt4940456:2:19", seasonNumber = 2, episodeNumber = 19)
        engine.reply(201, "{}")
        guarded.removeFromHistory(1, listOf(cinemeta))
        val show = json(engine.calls.single().body)["shows"]!!.jsonArray.single().jsonObject
        assertEquals("tt4940456", show["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals("2", show["seasons"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.content)
    }

    @Test
    fun `before the first snapshot an unmark with no video id is held, one with a video id goes (Q9)`() = runTest {
        signedIn()
        val noSnapshot = SimklHistoryWriter(session, pending, { clock.now }, clock.sleep, snapshotOf = { null })
        val bare = buildTrackingMediaReference("series", "tt0903747", seasonNumber = 1, episodeNumber = 2)
        noSnapshot.removeFromHistory(1, listOf(bare))
        assertTrue(engine.calls.isEmpty())
        engine.reply(201, "{}")
        noSnapshot.removeFromHistory(1, listOf(buildTrackingMediaReference("series", "tt0903747", videoId = "tt0903747:1:2", seasonNumber = 1, episodeNumber = 2)))
        assertEquals(listOf("/sync/history/remove"), engine.calls.map { it.path })
        assertFalse(simklAnimeTitleHeld(null, film("tt1")))
    }

    @Test
    fun `kept unmarks replay under the same guard, held when the video id is unknown, sent when it is known (Q9)`() = runTest {
        signedIn()
        val animeEntry = SimklEntry(
            type = SimklMediaType.ANIME, status = SimklListStatus.WATCHING,
            show = SimklMedia(ids = mapOf("simkl" to JsonPrimitive(1_367_345), "imdb" to JsonPrimitive("tt4940456")))
        )
        val guarded = SimklHistoryWriter(session, pending, { clock.now }, clock.sleep, snapshotOf = { SimklSnapshot(entries = listOf(animeEntry)) })
        pending.remove(1, listOf(buildTrackingMediaReference("series", "tt4940456", seasonNumber = 2, episodeNumber = 19)))
        guarded.replayPending(1)
        assertTrue(engine.calls.isEmpty())
        assertTrue(pending.get(1).isEmpty())

        pending.remove(1, listOf(buildTrackingMediaReference("series", "tt4940456", videoId = "tt4940456:2:20", seasonNumber = 2, episodeNumber = 20)))
        engine.reply(201, "{}")
        guarded.replayPending(1)
        assertEquals(listOf("/sync/history/remove"), engine.calls.map { it.path })
    }

    @Test
    fun `a removal body carries no watch times`() {
        val body = simklHistoryBody(listOf(TrackingHistoryItem(film("tt1"), 1_763_366_463_000L)), withWatchedAt = false)
        assertFalse("watched_at" in body)
    }

    @Test
    fun `scrobble body rounds progress and files anime by its numbering`() {
        val movie = json(simklScrobbleBody(TrackingScrobbleEvent(film("tt1"), 42.12567)))
        assertEquals("42.13", movie["progress"]!!.jsonPrimitive.content)
        assertTrue("movie" in movie && "episode" !in movie)

        val seasoned = json(simklScrobbleBody(TrackingScrobbleEvent(episode(2, 5, TrackingMediaKind.ANIME), 10.0)))
        assertTrue("show" in seasoned)
        assertEquals("2", seasoned["episode"]!!.jsonObject["season"]!!.jsonPrimitive.content)

        val absolute = json(simklScrobbleBody(TrackingScrobbleEvent(episode(null, 130, TrackingMediaKind.ANIME), 101.0)))
        assertTrue("anime" in absolute)
        assertEquals("100.0", absolute["progress"]!!.jsonPrimitive.content)
        assertFalse("season" in absolute["episode"]!!.jsonObject)
    }

    @Test
    fun `not_found counts every listed title`() {
        assertEquals(3, simklNotFoundCount("""{"added":{},"not_found":{"movies":[{}],"shows":[{},{}]}}"""))
        assertEquals(0, simklNotFoundCount("""{"added":{"movies":1}}"""))
        assertEquals(0, simklNotFoundCount("not json"))
    }

    // History writer

    @Test
    fun `a mark is one history call and reports what Simkl could not match`() = runTest {
        signedIn()
        engine.reply(201, """{"not_found":{"movies":[{"ids":{"imdb":"tt2"}}]}}""")

        val result = writer.addToHistory(1, listOf(TrackingHistoryItem(film("tt1"), 1L), TrackingHistoryItem(film("tt2"), 1L)))

        assertEquals("/sync/history", engine.calls.single().path)
        assertEquals(2, result.attemptedCount)
        assertEquals(1, result.notFoundCount)
    }

    @Test
    fun `a write that meets Simkl's write lock waits it out and tries once more`() = runTest {
        signedIn()
        engine.reply(400, """{"error":"rate_limit"}""")
        engine.reply(201, "{}")
        writer.removeFromHistory(1, listOf(film("tt1")))
        assertEquals(listOf("/sync/history/remove", "/sync/history/remove"), engine.calls.map { it.path })
        assertTrue(21_000L in clock.sleeps)

        engine.calls.clear()
        engine.reply(400, """{"error":"rate_limit"}""")
        engine.reply(400, """{"error":"rate_limit"}""")
        assertFailsSuspend<SimklApiException> { writer.removeFromHistory(1, listOf(film("tt1"))) }
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `a spent day keeps marks and unmarks, sends nothing more, and replays them in order after the reset`() = runTest {
        signedIn()
        spendTheDay()
        writer.addToHistory(1, listOf(TrackingHistoryItem(film("tt1"), 5L)))
        writer.addToHistory(1, listOf(TrackingHistoryItem(film("tt2"))))
        writer.removeFromHistory(1, listOf(film("tt3")))
        writer.addToHistory(1, listOf(TrackingHistoryItem(film("tt4"), 6L)))
        assertEquals(1, engine.calls.size)
        assertEquals(4, pending.get(1).size)
        assertEquals(clock.now, pending.get(1)[1].watchedAtEpochMs)

        clock.now += day
        engine.calls.clear()
        repeat(3) { engine.reply(201, "{}") }
        writer.replayPending(1)

        assertEquals(listOf("/sync/history", "/sync/history/remove", "/sync/history"), engine.calls.map { it.path })
        assertEquals(2, json(engine.calls[0].body)["movies"]!!.jsonArray.size)
        // Batches go Simkl's write lock apart, and not after the last.
        assertEquals(2, clock.sleeps.count { it == 21_000L })
        assertTrue(pending.get(1).isEmpty())
    }

    @Test
    fun `replay drops a batch Simkl refuses for good and keeps one that failed for now`() = runTest {
        signedIn()
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        pending.remove(1, listOf(film("tt2")))
        pending.add(1, listOf(TrackingHistoryItem(film("tt3"), 1L)))
        engine.reply(422, """{"error":"bad_body"}""")
        engine.fail()

        assertFailsSuspend<SimklException> { writer.replayPending(1) }
        assertEquals(listOf("tt2", "tt3"), pending.get(1).map { it.ids.imdb })
        assertEquals(listOf(1), refused)

        spendTheDay()
        assertFailsSuspend<SimklApiException> { writer.replayPending(1) }
        assertEquals(2, pending.get(1).size)
    }

    @Test
    fun `only the account, the allowance and the write lock keep a refused batch`() {
        fun refused(status: Int, error: String?) = SimklApiException(status, error, null, null).isRefusedForGood
        assertTrue(refused(400, "bad_request"))
        assertFalse(refused(400, null))
        assertFalse(refused(408, null))
        assertTrue(refused(404, null))
        assertTrue(refused(422, null))
        assertFalse(refused(400, "rate_limit"))
        assertFalse(refused(401, null))
        assertFalse(refused(403, null))
        assertFalse(refused(429, "user_limit_exceeded"))
        assertFalse(refused(500, null))
    }

    @Test
    fun `a failed live write drops only the kept opposite mark`() = runTest {
        signedIn()
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        pending.remove(1, listOf(film("tt2")))
        engine.fail()
        assertFailsSuspend<SimklException> { writer.addToHistory(1, listOf(TrackingHistoryItem(film("tt1"), 2L), TrackingHistoryItem(film("tt2"), 2L))) }
        assertEquals(listOf("tt1"), pending.get(1).map { it.ids.imdb })
    }

    @Test
    fun `a replayed batch already read goes before a live write of the same title`() = runTest {
        signedIn()
        // The replay's call stops once inside the window between reading its batch and Simkl's client lock.
        val gate = CompletableDeferred<Unit>()
        var first = true
        val gatedConfig = TEST_CONFIG.copy(clientId = { if (first) { first = false; gate.await() }; "client-v2" })
        val gatedClient = SimklClient(engine, gatedConfig, monotonicMs = { clock.now }, sleep = clock.sleep, jitterMs = { 0L })
        val gatedWriter = SimklHistoryWriter(
            SimklSession(gatedClient, SimklTokens(gatedClient, gatedConfig, accounts) { clock.now }, quota), pending, { clock.now }, clock.sleep
        )
        pending.remove(1, listOf(film("tt1")))
        engine.reply(201, "{}")
        engine.reply(201, "{}")

        val replay = launch { gatedWriter.replayPending(1) }
        runCurrent()
        val live = launch { gatedWriter.addToHistory(1, listOf(TrackingHistoryItem(film("tt1"), 2L))) }
        runCurrent()
        assertTrue(engine.calls.isEmpty())

        gate.complete(Unit)
        replay.join()
        live.join()
        assertEquals(listOf("/sync/history/remove", "/sync/history"), engine.calls.map { it.path })
        assertTrue(pending.get(1).isEmpty())
    }

    @Test
    fun `a replay meeting the write lock twice keeps the batch and throws`() = runTest {
        signedIn()
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        engine.reply(400, """{"error":"rate_limit"}""")
        engine.reply(400, """{"error":"rate_limit"}""")
        assertFailsSuspend<SimklApiException> { writer.replayPending(1) }
        assertEquals(2, engine.calls.size)
        assertEquals(listOf(21_000L), clock.sleeps.filter { it == 21_000L })
        assertEquals(1, pending.get(1).size)
    }

    @Test
    fun `a live mark that went drops the kept mark it repeats`() = runTest {
        signedIn()
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        engine.reply(201, "{}")
        writer.addToHistory(1, listOf(TrackingHistoryItem(film("tt1"), 2L)))
        assertTrue(pending.get(1).isEmpty())
    }

    @Test
    fun `a replay waits out the write lock without holding live writes back`() = runTest {
        signedIn()
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        val lockOver = CompletableDeferred<Unit>()
        sleep = { lockOver.await() }
        engine.reply(400, """{"error":"rate_limit"}""")
        engine.reply(201, "{}")
        engine.reply(201, "{}")

        val replay = launch { writer.replayPending(1) }
        runCurrent()
        writer.removeFromHistory(1, listOf(film("tt9")))
        assertEquals(listOf("/sync/history", "/sync/history/remove"), engine.calls.map { it.path })

        lockOver.complete(Unit)
        replay.join()
        assertEquals("/sync/history", engine.calls.last().path)
        assertTrue(pending.get(1).isEmpty())
    }

    @Test
    fun `a replay reads its batch again after the wait`() = runTest {
        signedIn()
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        val lockOver = CompletableDeferred<Unit>()
        sleep = { lockOver.await() }
        engine.reply(400, """{"error":"rate_limit"}""")
        engine.reply(201, "{}")

        val replay = launch { writer.replayPending(1) }
        runCurrent()
        writer.removeFromHistory(1, listOf(film("tt1")))
        lockOver.complete(Unit)
        replay.join()

        // The unmark replaced the kept mark during the wait: the replay sends nothing more.
        assertEquals(listOf("/sync/history", "/sync/history/remove"), engine.calls.map { it.path })
        assertTrue(pending.get(1).isEmpty())
    }

    @Test
    fun `a live write replaces the pending mark of the same title`() = runTest {
        signedIn()
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L), TrackingHistoryItem(film("tt2"), 1L)))
        engine.reply(201, "{}")
        writer.removeFromHistory(1, listOf(film("tt1")))
        assertEquals(listOf("tt2"), pending.get(1).map { it.ids.imdb })
    }

    @Test
    fun `pending marks go even when the automatic floor skips the pull, and a failure does not stop the sync`() = runTest {
        signedIn()
        accounts.update(1, TrackingProviderId.SIMKL) { it?.copy(lastSyncedAtEpochMs = clock.now - 60_000L) }
        val store = SimklSyncStore(folder.newFolder(), Dispatchers.Unconfined)
        store.saveIf(1, 0L, SimklSnapshot(activities = SimklActivities(all = "2026-11-01T00:00:00Z")))
        val sync = SimklSync(session, store, accounts, writer::replayPending) { clock.now }
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        engine.reply(201, "{}")

        assertFalse(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertEquals(listOf("/sync/history"), engine.calls.map { it.path })

        engine.calls.clear()
        pending.add(1, listOf(TrackingHistoryItem(film("tt2"), 1L)))
        engine.fail()
        engine.reply(200, """{"all":"2026-11-01T00:00:00Z"}""")
        // Pulled, but not settled: the screen keeps the mark Simkl has not got.
        assertFalse(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        assertEquals(listOf("/sync/history", "/sync/activities"), engine.calls.map { it.path })
        assertEquals(1, pending.get(1).size)
    }

    @Test
    fun `a replay that spends the day stops the sync before the pull`() = runTest {
        signedIn()
        val sync = SimklSync(session, SimklSyncStore(folder.newFolder(), Dispatchers.Unconfined), accounts, writer::replayPending) { clock.now }
        pending.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L)))
        spendTheDay()
        assertFalse(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        assertEquals(listOf("/sync/history"), engine.calls.map { it.path })
        assertEquals(1, pending.get(1).size)
    }

    // Scrobbler

    @Test
    fun `a stop Simkl already counted (409) is a success`() = runTest {
        signedIn()
        engine.reply(409, """{"error":"conflict"}""")
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(film("tt1"), 95.0))
        assertEquals("/scrobble/stop", engine.calls.single().path)

        engine.reply(409, "{}")
        assertFailsSuspend<SimklApiException> { scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(film("tt1"), 5.0)) }
    }

    @Test
    fun `on a spent day only a watching stop is kept, as a mark`() = runTest {
        signedIn()
        spendTheDay()
        scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(film("tt1"), 5.0))
        scrobbler.scrobble(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(film("tt1"), 40.0))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(film("tt2"), 79.9))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(episode(1, 4), 80.0))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(episode(1, 4).copy(episode = null), 99.0))

        assertEquals(1, engine.calls.size)
        val mark = pending.get(1).single()
        assertEquals(SimklPendingOp.ADD, mark.op)
        assertEquals(4, mark.episode)
        assertEquals(clock.now, mark.watchedAtEpochMs)
    }

    @Test
    fun `a watching stop Simkl refuses for the day is kept as a mark`() = runTest {
        signedIn()
        spendTheDay()
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(film("tt1"), 85.0))
        assertEquals("/scrobble/stop", engine.calls.single().path)
        assertEquals(listOf("tt1"), pending.get(1).map { it.ids.imdb })
    }

    @Test
    fun `a watching stop sent live replaces a kept unmark, and a failed call is thrown`() = runTest {
        signedIn()
        pending.remove(1, listOf(film("tt1")))
        engine.reply(201, "{}")
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(film("tt1"), 90.0))
        assertTrue(pending.get(1).isEmpty())

        engine.fail()
        assertFailsSuspend<SimklException> { scrobbler.scrobble(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(film("tt1"), 30.0)) }
        assertEquals(2, engine.calls.size)
    }
}
