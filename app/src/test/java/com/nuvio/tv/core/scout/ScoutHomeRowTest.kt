package com.nuvio.tv.core.scout

import com.nuvio.tv.core.contentfilter.ActiveContentFilter
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.CatalogContentFilter
import com.nuvio.tv.data.repository.GateFixture
import com.nuvio.tv.data.repository.noContentFilter
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutHomeRowTest {

    private val spring = ScoutPick(tmdbId = 500, tv = false, name = "Spring", reason = "Sintel", posterPath = "/s.jpg", year = "2019", rating = 7.6)
    private val cosmos = ScoutPick(tmdbId = 500, tv = true, name = "Zombie Laundromat", reason = "Tears of Steel")
    private val watched = MutableStateFlow<List<WatchedItem>>(emptyList())
    private val progress = MutableStateFlow<List<WatchProgress>>(emptyList())
    private val watchHistory = mockk<WatchProgressRepository> {
        every { watchedItems } returns watched
        every { allProgress } returns progress
    }
    private val keyState = MutableStateFlow<TmdbKeyState?>(TmdbKeyState(userKey = "k", effectiveKey = "k"))

    private val profileId = MutableStateFlow(1)
    private var capNow: ContentRatingCap? = null
    // replay 1: a tick emitted before the row subscribes still arrives (no race with the first draw).
    private val tagTicks = MutableSharedFlow<Unit>(replay = 1)
    private val repository = mockk<ScoutPicksRepository>(relaxUnitFun = true)

    private fun row(
        cap: ContentRatingCap? = null,
        filter: CatalogContentFilter = noContentFilter(),
        picks: List<ScoutPick> = listOf(spring, cosmos)
    ): ScoutHomeRow {
        capNow = cap
        repository.apply {
            every { cached(any()) } returns flowOf(picks)
            every { visible(any(), any(), any()) } answers {
                val list = firstArg<List<ScoutPick>>()
                val seen = secondArg<List<WatchedItem>>().map { it.title }.toSet()
                list.filterNot { it.name in seen }
            }
        }
        val history = watchHistory
        val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns profileId
            every { activeProfileReady } returns MutableStateFlow(true)
        }
        val keys = mockk<TmdbApiKeyProvider> { every { state } returns keyState }
        val settings = mockk<TmdbSettingsDataStore> { every { storedSettings } returns flowOf(TmdbSettings(language = "fr")) }
        val cap = mockk<ActiveRatingCap> { coEvery { current() } answers { capNow } }
        val filterSettings = mockk<ActiveContentFilter> { every { settingsChanges() } returns emptyFlow() }
        val tags = mockk<TitleTagLookup> { every { changes } returns tagTicks }
        return ScoutHomeRow(repository, history, profiles, keys, settings, cap, filter, filterSettings, tags)
    }

    @Test
    fun `picks become tmdb catalog titles with their reasons, movie and show kept apart`() = runTest {
        val items = row().items().first()

        assertEquals(listOf("Sintel", "Tears of Steel"), items.map { it.reason })
        val film = items.first().meta
        assertEquals("tmdb:500", film.id)
        assertEquals(ContentType.MOVIE, film.type)
        assertEquals("https://image.tmdb.org/t/p/w500/s.jpg", film.poster)
        assertEquals(7.6f, film.tmdbRating)
        assertEquals(ContentType.SERIES, items[1].meta.type)
    }

    @Test
    fun `a capped profile sees no row (D5, no age rating on TMDB picks)`() = runTest {
        assertTrue(row(cap = ContentRatingCap.STRICTEST).items().first().isEmpty())
    }

    @Test
    fun `no TMDB key hides the row`() = runTest {
        keyState.value = TmdbKeyState(userKey = "", effectiveKey = "")

        assertTrue(row().items().first().isEmpty())
    }

    @Test
    fun `the content filter hides a pick and a broken pack file hides the row`() = runTest {
        val gate = GateFixture(listOf(FilterPack("p5", words = listOf(PackWord("zombie")))))
        assertEquals(listOf("Spring"), row(filter = gate.contentFilter).items().first().map { it.meta.name })

        val broken = CatalogContentFilter(
            mockk<ActiveContentFilter> { coEvery { current() } throws IllegalStateException("broken pack file") },
            mockk(),
            { 0L }
        )
        assertTrue(row(filter = broken).items().first().isEmpty())
    }

    @Test
    fun `a limit set after the row is shown empties it on the next refresh`() = runTest {
        val home = row()
        val last = MutableStateFlow(-1)
        backgroundScope.launch { home.items().collect { last.value = it.size } }
        last.first { it == 2 }

        capNow = ContentRatingCap.STRICTEST
        home.refresh()

        last.first { it == 0 }
    }

    @Test
    fun `a tag tick re-judges the row`() = runTest {
        val gate = GateFixture(listOf(FilterPack("p5", words = listOf(PackWord("zombie")))))
        gate.states["tmdb:500"] = TagState.Undecided
        val home = row(filter = gate.contentFilter, picks = listOf(spring))
        val last = MutableStateFlow(-1)
        backgroundScope.launch { home.items().collect { last.value = it.size } }
        last.first { it == 0 }

        gate.states["tmdb:500"] = TagState.WordsOnly
        tagTicks.emit(Unit)

        last.first { it == 1 }
    }

    @Test
    fun `refresh builds for the active profile with the stored language, never on a capped profile`() = runTest {
        watched.value = listOf(WatchedItem(contentId = "tt1", contentType = "movie", title = "Sintel", watchedAt = 1))
        val home = row()
        home.refresh()
        coVerify(timeout = 2_000) { repository.refreshIfDue(1, watched.value, emptyList(), "fr", any()) }

        capNow = ContentRatingCap.STRICTEST
        home.refresh()
        Thread.sleep(200)
        coVerify(exactly = 1) { repository.refreshIfDue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a profile switch while reading history builds nothing`() = runTest {
        val switching = MutableStateFlow<List<WatchedItem>>(emptyList())
        val home = row()
        every { watchHistory.watchedItems } returns switching.onStart { profileId.value = 2 }
        home.refresh()
        Thread.sleep(300)

        coVerify(exactly = 0) { repository.refreshIfDue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a title watched since the build leaves the row`() = runTest {
        watched.value = listOf(WatchedItem(contentId = "tmdb:500", contentType = "movie", title = "Spring", watchedAt = 1))

        assertEquals(listOf("Zombie Laundromat"), row().items().first().map { it.meta.name })
    }
}
