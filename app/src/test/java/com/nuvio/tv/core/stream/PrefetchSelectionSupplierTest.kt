package com.nuvio.tv.core.stream

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.core.player.PlayerPlaybackNetworking
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.domain.model.StreamBadge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchSelectionSupplierTest {

    // Default settings are MANUAL auto-play, so every rank publishes EMPTY.
    private val settings = mockk<PlayerSettingsDataStore>(relaxed = true).also {
        every { it.playerSettings } returns flowOf(PlayerSettings())
        // A relaxed mock's flow never emits, so rank's .first() needs a real one (R5-5).
        every { it.streamLimitInputs } returns flowOf(StreamLimitInputs(StreamLimitSettings(StreamLimitMode.OFF)))
    }

    private val addons = mockk<AddonRepository>().also {
        every { it.getInstalledAddons() } returns flowOf(emptyList())
    }

    private val preferredSources = mockk<com.nuvio.tv.data.local.PreferredSourceDataStore>().also {
        coEvery { it.get(any()) } returns null
    }

    private val presentation = mockk<StreamBadgePresentation>().also {
        coEvery { it.badgesFor(any()) } returns emptyList()
    }

    private val supplier = PrefetchSelectionSupplier(
        context = mockk(relaxed = true),
        playerSettingsDataStore = settings,
        addonRepository = addons,
        bingeGroupCacheDataStore = mockk(relaxed = true),
        preferredSourceDataStore = preferredSources,
        streamBadgePresentation = presentation,
        failedReleases = FailedReleases(
            object : FailedReleaseStorage {
                override fun load(): String? = null
                override fun save(value: String) = Unit
            },
            kotlin.time.Clock.System
        )
    )

    private suspend fun publishEmpty(uiKey: String) {
        supplier.rankAndPrewarm(groups = emptyList(), contentId = null, runtimeMinutes = null, episode = false, film = false, uiKey = uiKey)
    }

    // Mirrors PrefetchSelectionSupplier.MAX_UI_SIGNALS (private).
    private val cap = 16
    private fun key(i: Int) = "k$i"

    @Test
    fun `signals are kept per uiKey and trimmed to the newest sixteen`() = runTest {
        (0..cap).forEach { publishEmpty(key(it)) }

        val signals = supplier.uiSignals.value
        assertEquals((1..cap).map(::key), signals.keys.toList())
        assertEquals(SourcePrefetchSignal(key(1), SourcePrefetchPhase.EMPTY, null), signals[key(1)])
    }

    @Test
    fun `a back stack deeper than four keeps every page's signal`() = runTest {
        (0..<cap).forEach { publishEmpty(key(it)) }

        assertEquals((0..<cap).map(::key), supplier.uiSignals.value.keys.toList())
    }

    @Test
    fun `republishing a key makes it the newest`() = runTest {
        (0..<cap).forEach { publishEmpty(key(it)) }
        publishEmpty(key(0))
        publishEmpty("new")

        assertEquals(
            (2..<cap).map(::key) + key(0) + "new",
            supplier.uiSignals.value.keys.toList()
        )
    }

    @Test
    fun `rank tolerates an empty pool outside manual mode`() = runTest {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )

        publishEmpty("k")

        assertEquals(SourcePrefetchPhase.EMPTY, supplier.uiSignals.value["k"]?.phase)
    }

    private val oneStream = listOf(
        AddonStreams(
            addonName = "A",
            addonLogo = null,
            streams = listOf(
                Stream(
                    name = "A 2160p", title = null, description = null, url = "https://host/file.mkv",
                    ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
                    behaviorHints = null, addonName = "A", addonLogo = null
                )
            )
        )
    )

    /** True when a winner was ranked; uiKey/prewarm as the production callers pass them. */
    private suspend fun ranksAWinner(uiKey: String?, prewarm: Boolean): Boolean {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )
        return supplier.rankAndPrewarm(oneStream, contentId = null, runtimeMinutes = null, episode = false, film = false, uiKey = uiKey, prewarm = prewarm) != null
    }

    @Test
    fun `focus callers rank without a prewarm GET, hero callers still warm the winner (T30)`() = runTest {
        mockkObject(PlayerPlaybackNetworking)
        try {
            every { PlayerPlaybackNetworking.prewarmPlaybackConnection(any(), any()) } returns Unit

            // episode_focus / cw_focus: no uiKey, prewarm off
            assertTrue(ranksAWinner(uiKey = null, prewarm = false))
            verify(exactly = 0) { PlayerPlaybackNetworking.prewarmPlaybackConnection(any(), any()) }

            // details_hero: uiKey, prewarm on
            assertTrue(ranksAWinner(uiKey = "k", prewarm = true))
            verify(exactly = 1) { PlayerPlaybackNetworking.prewarmPlaybackConnection("https://host/file.mkv", any()) }
            // READY lands after the badge match, off the rank (Spec 130 round 2).
            assertEquals(SourcePrefetchPhase.READY, supplier.uiSignals.first { it["k"] != null }["k"]?.phase)
        } finally {
            unmockkObject(PlayerPlaybackNetworking)
        }
    }

    // Spec 130 round 2: the rank returns before the badge match; READY then carries the badges.
    @Test
    fun `the rank does not wait for the badge match`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val badge = StreamBadge(name = "DV", imageURL = "https://cdn/dv.png")
        coEvery { presentation.badgesFor(any()) } coAnswers { gate.await(); listOf(badge) }

        assertTrue(ranksAWinner(uiKey = "k", prewarm = false))
        assertNull(supplier.uiSignals.value["k"])

        gate.complete(Unit)
        val ready = supplier.uiSignals.first { it["k"] != null }.getValue("k")
        assertEquals(SourcePrefetchPhase.READY, ready.phase)
        assertEquals(listOf(badge), ready.badges)
    }

    /**
     * The first badgesFor call blocks without suspending, as the ICU match does, so cancel()
     * cannot stop that job: only the supersede check in publishReady keeps it from publishing.
     */
    private inner class StaleMatch(private val fresh: List<StreamBadge>) {
        val started = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        private val release = java.util.concurrent.CountDownLatch(1)
        private val calls = java.util.concurrent.atomic.AtomicInteger()

        init {
            coEvery { presentation.badgesFor(any()) } answers {
                if (calls.getAndIncrement() == 0) {
                    started.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "stale match never released" }
                    returned.complete(Unit)
                    listOf(StreamBadge(name = "stale"))
                } else {
                    fresh
                }
            }
        }

        /** Lets the stale match return, then gives its job time to (wrongly) publish. */
        suspend fun finish() {
            release.countDown()
            returned.await()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Thread.sleep(200) }
        }
    }

    @Test
    fun `a superseded ready never overwrites a newer empty`() = runTest {
        val stale = StaleMatch(fresh = emptyList())
        assertTrue(ranksAWinner(uiKey = "k", prewarm = false))
        stale.started.await()

        supplier.rankAndPrewarm(groups = emptyList(), contentId = null, runtimeMinutes = null, episode = false, film = false, uiKey = "k")
        stale.finish()

        assertEquals(SourcePrefetchPhase.EMPTY, supplier.uiSignals.value["k"]?.phase)
    }

    @Test
    fun `a superseded ready never overwrites a newer ready`() = runTest {
        val fresh = listOf(StreamBadge(name = "fresh"))
        val stale = StaleMatch(fresh = fresh)
        assertTrue(ranksAWinner(uiKey = "k", prewarm = false))
        stale.started.await()

        assertTrue(ranksAWinner(uiKey = "k", prewarm = false))
        supplier.uiSignals.first { it["k"]?.badges == fresh }
        stale.finish()

        assertEquals(fresh, supplier.uiSignals.value["k"]?.badges)
    }

    // Spec 141 D2: a new rank pending for the key drops its old signal (the line reads searching).
    @Test
    fun `clearing a key drops its ready and leaves other keys`() = runTest {
        assertTrue(ranksAWinner(uiKey = "k", prewarm = false))
        supplier.uiSignals.first { it["k"] != null }
        publishEmpty("other")

        supplier.clearSignal("k")

        assertEquals(listOf("other"), supplier.uiSignals.value.keys.toList())
    }

    @Test
    fun `clearing a key stops its pending badge match from publishing an older ready`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        coEvery { presentation.badgesFor(any()) } coAnswers { started.complete(Unit); gate.await(); emptyList() }
        assertTrue(ranksAWinner(uiKey = "k", prewarm = false))
        started.await()

        supplier.clearSignal("k")
        gate.complete(Unit)
        withContext(Dispatchers.Default) { Thread.sleep(200) }

        assertNull(supplier.uiSignals.value["k"])
    }

    @Test
    fun `a rank that throws ends its key's line on empty`() = runTest {
        coEvery { preferredSources.get(any()) } throws IllegalStateException("store failed")

        val thrown = runCatching {
            supplier.rankAndPrewarm(oneStream, contentId = "tt1", runtimeMinutes = null, episode = false, film = false, uiKey = "k")
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(SourcePrefetchPhase.EMPTY, supplier.uiSignals.value["k"]?.phase)
    }

    // Spec 51 P5: the prefetch ranks under the limit, with the runtime its caller knows.
    @Test
    fun `the rank skips a stream the limit hides and carries the set in its snapshot`() = runTest {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )
        every { settings.streamLimitInputs } returns flowOf(
            StreamLimitInputs(StreamLimitSettings(StreamLimitMode.MANUAL, manualMbps = 40))
        )
        fun sized(url: String, bytes: Long) = Stream(
            name = "A", title = null, description = null, url = url, ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "A", addonLogo = null,
            streamData = AioStreamData(size = bytes, filename = null, cached = null, parsedFile = null)
        )
        // Over 60 minutes: 133 and 13 Mbit/s.
        val big = sized("https://host/big.mkv", 60_000_000_000L)
        val small = sized("https://host/small.mkv", 6_000_000_000L)
        val groups = listOf(AddonStreams(addonName = "A", addonLogo = null, streams = listOf(big, small)))

        val limited = supplier.rankAndPrewarm(groups, contentId = null, runtimeMinutes = 60, episode = false, film = false, prewarm = false)!!
        assertEquals(small.url, limited.winner.url)
        assertEquals(setOf(big.limitKey()), limited.snapshot.inputs.hiddenKeys)

        // No runtime known and no file length: nothing is estimated, so nothing is hidden (D8).
        val unknown = supplier.rankAndPrewarm(groups, contentId = null, runtimeMinutes = null, episode = false, film = false, prewarm = false)!!
        assertEquals(big.url, unknown.winner.url)
        assertEquals(emptySet<String>(), unknown.snapshot.inputs.hiddenKeys)
        // Spec 65 D4: the film flag reaches the snapshot the press compares.
        val film = supplier.rankAndPrewarm(groups, contentId = null, runtimeMinutes = null, episode = false, film = true, prewarm = false)!!
        assertEquals(true, film.snapshot.inputs.filmSizeFloors)
        assertEquals(false, unknown.snapshot.inputs.filmSizeFloors)
    }

    // Spec 65 P6: the prefetch reads the show's preferred source as the press does, so both pick it.
    @Test
    fun `the rank takes the show's preferred source and carries it in its snapshot`() = runTest {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )
        fun named(filename: String) = Stream(
            name = "A", title = null, description = null, url = "https://host/$filename", ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = filename
            ),
            addonName = "A", addonLogo = null
        )
        val first = named("Show.S01E03.2160p.WEB-DL-FLUX.mkv")
        val wanted = named("Show.S01E03.1080p.WEB-DL-NTb.mkv")
        val groups = listOf(AddonStreams(addonName = "A", addonLogo = null, streams = listOf(first, wanted)))
        val preferred = PreferredSource.of(named("Show.S01E02.1080p.WEB-DL-NTb.mkv"))
        coEvery { preferredSources.get("tt1") } returns preferred

        val ranked = supplier.rankAndPrewarm(groups, contentId = "tt1", runtimeMinutes = null, episode = true, film = false, prewarm = false)!!
        assertEquals(wanted.url, ranked.winner.url)
        assertEquals(preferred, ranked.snapshot.inputs.preferred)
        // Another show: no preference, the mode's own pick.
        val other = supplier.rankAndPrewarm(groups, contentId = "tt2", runtimeMinutes = null, episode = true, film = false, prewarm = false)!!
        assertEquals(first.url, other.winner.url)
        assertEquals(null, other.snapshot.inputs.preferred)
        // MANUAL mode (D20): the preferred match is still the winner; without one nothing ranks.
        every { settings.playerSettings } returns flowOf(PlayerSettings())
        val manual = supplier.rankAndPrewarm(groups, contentId = "tt1", runtimeMinutes = null, episode = true, film = false, prewarm = false)!!
        assertEquals(wanted.url, manual.winner.url)
        assertEquals(null, supplier.rankAndPrewarm(groups, contentId = "tt2", runtimeMinutes = null, episode = true, film = false, prewarm = false))
    }

    // Spec 51 r1: the binge lookahead passes the player's exemption, so its rank keeps the group.
    @Test
    fun `an exempt binge group's over-limit stream stays rankable for the lookahead`() = runTest {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )
        every { settings.streamLimitInputs } returns flowOf(
            StreamLimitInputs(StreamLimitSettings(StreamLimitMode.MANUAL, manualMbps = 40))
        )
        fun sized(url: String, bytes: Long, group: String) = Stream(
            name = "A", title = null, description = null, url = url, ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null,
            behaviorHints = StreamBehaviorHints(notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null),
            addonName = "A", addonLogo = null,
            streamData = AioStreamData(size = bytes, filename = null, cached = null, parsedFile = null)
        )
        // Over 60 minutes: 133 Mbit/s in the playing group g1, 13 Mbit/s in g2.
        val sameGroup = sized("https://host/g1.mkv", 60_000_000_000L, "g1")
        val other = sized("https://host/g2.mkv", 6_000_000_000L, "g2")
        val groups = listOf(AddonStreams(addonName = "A", addonLogo = null, streams = listOf(sameGroup, other)))

        val exempt = supplier.rankAndPrewarm(
            groups, contentId = null, runtimeMinutes = 60, episode = true, film = false,
            bingeOverride = "g1", exemptBingeGroup = "g1", prewarm = false
        )!!
        assertEquals(sameGroup.url, exempt.winner.url)
        val notExempt = supplier.rankAndPrewarm(
            groups, contentId = null, runtimeMinutes = 60, episode = true, film = false, bingeOverride = "g1", prewarm = false
        )!!
        assertEquals(other.url, notExempt.winner.url)
    }
}
