package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.contentfilter.ActiveFilter
import com.nuvio.tv.core.contentfilter.TagPriority
import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.core.contentfilter.TitleRef
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.contentfilter.TitleTags
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 117 P7 + Spec 122 V2/V3: Continue Watching under the active profile's content filters. */
class ContinueWatchingContentFilterGateTest {
    private fun inProgress(id: String, name: String = id, genres: List<String> = emptyList()) =
        ContinueWatchingItem.InProgress(
            WatchProgress(
                contentId = id, contentType = "movie", name = name, poster = null, backdrop = null, logo = null,
                videoId = id, season = null, episode = null, episodeTitle = null, position = 1, duration = 10,
                lastWatched = 1
            ),
            genres = genres
        )

    private fun nextUp(id: String, name: String = id) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = id, contentType = "series", name = name, poster = null, backdrop = null, logo = null,
            videoId = "$id:1:2", season = 1, episode = 2, episodeTitle = null, thumbnail = null,
            lastWatched = 1, sortTimestamp = 1
        )
    )

    private val horror = FilterPack("p5", words = listOf(PackWord("zombie")), genreNames = setOf("Horror"))
    private val states = HashMap<String, TagState>()
    private val requested = mutableListOf<Pair<String, TagPriority>>()
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val lookup = mockk<TitleTagLookup> {
        every { state(any()) } answers { states[firstArg<TitleRef>().id] ?: TagState.WordsOnly }
        every { tmdbKey(any()) } returns null
        every { request(any(), any()) } answers { requested += firstArg<TitleRef>().id to secondArg<TagPriority>() }
        every { changes } returns ticks
    }

    private fun TestScope.gate(
        source: StateFlow<HomeUiState>,
        verdictDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        read: suspend () -> ActiveFilter?
    ): StateFlow<HomeUiState> =
        ContinueWatchingContentFilterGate(backgroundScope, read, lookup, verdictDispatcher).gate(source)

    /** Runs the verdict work only when asked: the window before a re-judge stays visible to a test. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private fun active(preview: Boolean = false) = ActiveFilter(ContentFilter(listOf(horror)), preview)

    private fun StateFlow<HomeUiState>.ids() =
        (value.continueWatchingItems + value.upcomingItems).map { it.contentId() }

    @Test
    fun `no filter passes the state through as the same instance`() = runTest {
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("tt1", "Zombie Town"))))
        val gated = gate(source) { null }
        runCurrent()
        assertSame(source.value, gated.value)
        val nothingOn = gate(source) { ActiveFilter(null, preview = true) }
        runCurrent()
        assertSame(source.value, nothingOn.value)
    }

    @Test
    fun `continue watching stays hidden until the filter is read, and for good when the read fails`() = runTest {
        val read = CompletableDeferred<ActiveFilter?>()
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("tt1"))))
        val gated = gate(source) { read.await() }
        runCurrent()
        assertTrue(gated.ids().isEmpty())
        read.complete(active())
        runCurrent()
        assertEquals(listOf("tt1"), gated.ids())

        val broken = gate(source) { error("broken pack file") }
        runCurrent()
        assertTrue(broken.ids().isEmpty())
    }

    @Test
    fun `hits and undecided titles hide, undecided ones are looked up now, main row and upcoming alike`() = runTest {
        states["tt3"] = TagState.Undecided
        val source = MutableStateFlow(
            HomeUiState(
                continueWatchingItems = listOf(
                    inProgress("tt1", "Zombie Town"),
                    inProgress("tt2", "Night", genres = listOf("Horror")),
                    inProgress("tt3", "Paddington"),
                    inProgress("tt4", "Toy Story")
                ),
                upcomingItems = listOf(nextUp("tt5", "Zombie Days"), nextUp("tt6", "Bluey"))
            )
        )
        val gated = gate(source) { active() }
        runCurrent()

        assertEquals(listOf("tt4", "tt6"), gated.ids())
        assertEquals(listOf("tt3" to TagPriority.NOW), requested)
    }

    @Test
    fun `a title appears once its tags land and a tick comes`() = runTest {
        states["tt3"] = TagState.Undecided
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("tt3", "Paddington"))))
        val gated = gate(source) { active() }
        runCurrent()
        assertTrue(gated.ids().isEmpty())

        states["tt3"] = TagState.Tagged(TitleTags(setOf(35), emptySet(), emptyList(), "en", false))
        ticks.emit(Unit)
        runCurrent()
        assertEquals(listOf("tt3"), gated.ids())
    }

    @Test
    fun `previewing, a hit stays marked with its packs and an undecided title stays hidden`() = runTest {
        states["tt3"] = TagState.Undecided
        val source = MutableStateFlow(
            HomeUiState(
                continueWatchingItems = listOf(inProgress("tt1", "Zombie Town"), inProgress("tt3", "Paddington")),
                upcomingItems = listOf(nextUp("tt5", "Zombie Days"))
            )
        )
        val gated = gate(source) { active(preview = true) }
        runCurrent()

        assertEquals(listOf("tt1", "tt5"), gated.ids())
        assertEquals(listOf("p5"), gated.value.continueWatchingItems.single().filterHits)
        assertEquals(listOf("p5"), gated.value.upcomingItems.single().filterHits)
    }

    @Test
    fun `a clean state with no marks is re-emitted as the same instance`() = runTest {
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("tt4", "Toy Story"))))
        val gated = gate(source) { active() }
        runCurrent()
        assertSame(source.value, gated.value)
    }

    @Test
    fun `a renamed title is hidden until judged again, never shown on its old verdict`() = runTest {
        // Verdicts run on their own scheduler, advanced by hand: the window before the re-judge is visible.
        val verdicts = HeldDispatcher()
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("tt1", "Untitled"))))
        val gated = gate(source, verdicts) { active() }
        runCurrent()
        verdicts.runAll()
        runCurrent()
        assertEquals(listOf("tt1"), gated.ids())

        source.value = HomeUiState(continueWatchingItems = listOf(inProgress("tt1", "Zombie Town")))
        runCurrent()
        assertTrue("the old Clean verdict must not show the renamed title", gated.ids().isEmpty())

        source.value = HomeUiState(continueWatchingItems = listOf(inProgress("tt1", "Paddington")))
        runCurrent()
        assertTrue(gated.ids().isEmpty())
        verdicts.runAll()
        runCurrent()
        assertEquals(listOf("tt1"), gated.ids())
    }
}
