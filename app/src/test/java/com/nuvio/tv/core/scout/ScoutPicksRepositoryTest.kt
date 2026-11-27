package com.nuvio.tv.core.scout

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.tmdb.TmdbApiKeySource
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ScoutPicksRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stores = HashMap<Int, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), ScoutPicksRepository.FEATURE) } answers {
            val profile = firstArg<Int>()
            stores.getOrPut(profile) { PreferenceDataStoreFactory.create { tmp.root.resolve("scout_p$profile.preferences_pb") } }
        }
    }
    private val builder = mockk<ScoutPicksBuilder>()
    private val service = mockk<TmdbService> { every { cachedTmdbId(any()) } returns null }
    private var key = "test-key"
    private val repo = ScoutPicksRepository(factory, builder, service, TmdbApiKeySource { key })

    private val history = listOf(WatchedItem(contentId = "tt1", contentType = "movie", title = "Sintel", watchedAt = 1))
    private val spring = ScoutPick(tmdbId = 500, tv = false, name = "Spring", reason = "Sintel")
    private val cosmos = ScoutPick(tmdbId = 501, tv = true, name = "Cosmos Laundromat", reason = "Sintel")
    private val day = SCOUT_REFRESH_MS
    private val t0 = 1_000_000_000L

    @Test
    fun `on by default and builds once a day`() = runTest {
        coEvery { builder.build(any(), any(), any(), "en", any()) } returns listOf(spring)

        assertTrue(repo.enabled(1).first())
        repo.refreshIfDue(1, history, emptyList(), "en", t0)
        repo.refreshIfDue(1, history, emptyList(), "en", t0 + day - 1)

        assertEquals(listOf(spring), repo.cached(1).first())
        coVerify(exactly = 1) { builder.build(any(), any(), any(), any(), any()) }

        repo.refreshIfDue(1, history, emptyList(), "en", t0 + day)
        coVerify(exactly = 2) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a new language rebuilds at once`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)

        repo.refreshIfDue(1, history, emptyList(), "en", t0)
        repo.refreshIfDue(1, history, emptyList(), "fr", t0 + 1)

        coVerify(exactly = 2) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `switched off hides the row and asks nothing`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)
        repo.refreshIfDue(1, history, emptyList(), "en", t0)

        repo.setEnabled(1, false)
        repo.refreshIfDue(1, history, emptyList(), "en", t0 + 2 * day)

        assertFalse(repo.enabled(1).first())
        assertTrue(repo.cached(1).first().isEmpty())
        coVerify(exactly = 1) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `no key, switch off or no history build nothing and keep the day's attempt`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)
        key = ""
        repo.refreshIfDue(1, history, emptyList(), "en", t0)
        repo.setEnabled(2, false)
        repo.refreshIfDue(2, history, emptyList(), "en", t0)
        key = "test-key"
        repo.refreshIfDue(3, emptyList(), emptyList(), "en", t0)
        coVerify(exactly = 0) { builder.build(any(), any(), any(), any(), any()) }

        // Key added, switch back on, first title watched: each builds at once.
        repo.setEnabled(2, true)
        listOf(1, 2, 3).forEach { repo.refreshIfDue(it, history, emptyList(), "en", t0 + 1) }
        coVerify(exactly = 3) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an empty build keeps the old list and the next try waits a day, across restarts`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)
        repo.refreshIfDue(1, history, emptyList(), "en", t0)
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns emptyList()

        repo.refreshIfDue(1, history, emptyList(), "en", t0 + day)
        // A new instance is a restarted app: the attempt is on disk, not in memory.
        val restarted = ScoutPicksRepository(factory, builder, service, TmdbApiKeySource { key })
        restarted.refreshIfDue(1, history, emptyList(), "en", t0 + 2 * day - 1)

        assertEquals(listOf(spring), restarted.cached(1).first())
        coVerify(exactly = 2) { builder.build(any(), any(), any(), any(), any()) }

        restarted.refreshIfDue(1, history, emptyList(), "en", t0 + 2 * day)
        coVerify(exactly = 3) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failed or cancelled build still used the day's attempt`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } throws CancellationException("left Home")

        try {
            repo.refreshIfDue(1, history, emptyList(), "en", t0)
        } catch (expected: CancellationException) {
        }
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)
        repo.refreshIfDue(1, history, emptyList(), "en", t0 + day - 1)

        coVerify(exactly = 1) { builder.build(any(), any(), any(), any(), any()) }
        assertTrue(repo.cached(1).first().isEmpty())
    }

    @Test
    fun `a store that cannot be read or record the attempt builds nothing`() = runTest {
        val broken = mockk<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>()
        every { broken.data } returns flow { throw IOException("unreadable") }
        stores[3] = broken
        val unwritable = mockk<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>()
        every { unwritable.data } returns flowOf(emptyPreferences())
        coEvery { unwritable.updateData(any()) } throws IOException("disk full")
        stores[4] = unwritable

        repo.refreshIfDue(3, history, emptyList(), "en", t0)
        repo.refreshIfDue(4, history, emptyList(), "en", t0)

        coVerify(exactly = 0) { builder.build(any(), any(), any(), any(), any()) }
        assertTrue(repo.cached(3).first().isEmpty())
    }

    @Test
    fun `profiles keep their own lists and attempts`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)
        repo.refreshIfDue(1, history, emptyList(), "en", t0)

        assertTrue(repo.cached(2).first().isEmpty())
        repo.refreshIfDue(2, history, emptyList(), "en", t0 + 1)
        assertEquals(listOf(spring), repo.cached(2).first())
        coVerify(exactly = 2) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the stored list carries its version, and one without it rebuilds`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)
        repo.refreshIfDue(1, history, emptyList(), "en", t0)
        val raw = factory.get(1, ScoutPicksRepository.FEATURE).data.first()[stringPreferencesKey("picks")]!!
        assertTrue(raw.contains("\"version\":$SCOUT_CACHE_VERSION"))

        val stale = raw.replace(",\"version\":$SCOUT_CACHE_VERSION", "")
        factory.get(1, ScoutPicksRepository.FEATURE).edit {
            it[stringPreferencesKey("picks")] = stale
            it.remove(stringPreferencesKey("attempt"))
        }
        assertTrue(repo.cached(1).first().isEmpty())
        repo.refreshIfDue(1, history, emptyList(), "en", t0 + 1)
        coVerify(exactly = 2) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a damaged cache reads as none and rebuilds`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)
        factory.get(1, ScoutPicksRepository.FEATURE).edit { it[stringPreferencesKey("picks")] = "{not json" }

        assertTrue(repo.cached(1).first().isEmpty())
        repo.refreshIfDue(1, history, emptyList(), "en", t0)

        assertEquals(listOf(spring), repo.cached(1).first())
    }

    @Test
    fun `titles watched since the build are filtered out on read`() {
        val watchedSince = listOf(
            WatchedItem(contentId = "tmdb:500", contentType = "movie", title = "Primavera", watchedAt = 2)
        )
        val startedSince = listOf(WatchedItem(contentId = "kitsu:9", contentType = "series", title = "Cosmos Laundromat", watchedAt = 2))

        assertEquals(listOf(cosmos), repo.visible(listOf(spring, cosmos), watchedSince, emptyList()))
        assertEquals(listOf(spring), repo.visible(listOf(spring, cosmos), startedSince, emptyList()))
    }

    @Test
    fun `rebuild is due without a recent attempt, on a new version or language, after a day or from the future`() {
        val cache = ScoutCache(builtAt = t0, language = "en", picks = listOf(spring), version = SCOUT_CACHE_VERSION)
        val attempt = ScoutAttempt(at = t0, language = "en", version = SCOUT_CACHE_VERSION)

        assertTrue(scoutRebuildDue(null, null, "en", t0))
        assertFalse(scoutRebuildDue(null, attempt, "en", t0 + day - 1))
        assertFalse(scoutRebuildDue(cache, attempt, "en", t0 + day - 1))
        assertTrue(scoutRebuildDue(cache, attempt, "en", t0 + day))
        assertTrue(scoutRebuildDue(cache, attempt, "fr", t0 + 1))
        assertTrue(scoutRebuildDue(cache, attempt, "en", t0 - 1))
        assertTrue(scoutRebuildDue(cache.copy(version = SCOUT_CACHE_VERSION + 1), attempt.copy(version = 0), "en", t0))
    }

    // Spec 100 S4: detail ratings.

    @Test
    fun `a rating is per profile, set, replaced and cleared`() = runTest {
        assertEquals(null, repo.rating(1, "tt1", null, false).first())

        assertTrue(repo.setRating(1, "tt1", false, "Sintel", ScoutRating.LOVE, tmdbId = 45745, now = 5))
        assertEquals(ScoutRating.LOVE, repo.rating(1, "tt1", null, false).first())
        assertEquals(null, repo.rating(2, "tt1", null, false).first())

        repo.setRating(1, "tt1", false, "Sintel", ScoutRating.NOT_FOR_ME, tmdbId = 45745, now = 6)
        repo.setRating(1, "tt2", false, "Spring", ScoutRating.LIKE, tmdbId = 2, now = 7)
        assertEquals(ScoutRating.NOT_FOR_ME, repo.rating(1, "tt1", null, false).first())

        repo.setRating(1, "tt1", false, "Sintel", null)
        assertEquals(null, repo.rating(1, "tt1", null, false).first())
        assertEquals(ScoutRating.LIKE, repo.rating(1, "tt2", null, false).first())
        assertFalse(repo.setRating(1, " ", false, "x", ScoutRating.LIKE, tmdbId = 1))
        // D7: a rating without its TMDB id is not saved; a clear needs none.
        assertFalse(repo.setRating(1, "tt2", false, "Spring", ScoutRating.LOVE))
        assertEquals(ScoutRating.LIKE, repo.rating(1, "tt2", null, false).first())
    }

    @Test
    fun `ratings feed the build and a rated-only profile builds`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring)
        repo.setRating(1, "tt9", false, "Big Buck Bunny", ScoutRating.LOVE, tmdbId = 9, now = 3)

        repo.refreshIfDue(1, emptyList(), emptyList(), "en", t0)

        coVerify(exactly = 1) {
            builder.build(emptyList(), emptyList(), match { it.single().contentId == "tt9" }, "en", t0)
        }
        assertEquals(listOf(spring), repo.cached(1).first())
    }

    @Test
    fun `a title rated since the build leaves the cached list at once`() = runTest {
        coEvery { builder.build(any(), any(), any(), any(), any()) } returns listOf(spring, cosmos)
        repo.refreshIfDue(1, history, emptyList(), "en", t0)

        repo.setRating(1, "tmdb:500", false, "Primavera", ScoutRating.NOT_FOR_ME, tmdbId = 500)
        assertEquals(listOf(cosmos), repo.cached(1).first())
        repo.setRating(1, "kitsu:9", true, "Cosmos Laundromat", ScoutRating.LIKE, tmdbId = 501)
        assertTrue(repo.cached(1).first().isEmpty())
        coVerify(exactly = 1) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `one title holds one rating under all its ids, and any of them finds it`() = runTest {
        repo.setRating(1, "tt1", false, "Sintel", ScoutRating.LIKE, imdbId = "tt1", tmdbId = 45745, now = 1)
        // The same film opened from a TMDB catalog page with no IMDb id.
        assertEquals(ScoutRating.LIKE, repo.rating(1, "tmdb:45745", null, false).first())
        assertEquals(null, repo.rating(1, "tmdb:45745", null, true).first())

        repo.setRating(1, "tmdb:45745", false, "Sintel", ScoutRating.NOT_FOR_ME, tmdbId = 45745, now = 2)
        assertEquals(ScoutRating.NOT_FOR_ME, repo.rating(1, "tt1", null, false).first())

        coEvery { builder.build(any(), any(), any(), any(), any()) } returns emptyList()
        // A second film to ask about: a profile with only minuses does not build.
        val more = history + WatchedItem(contentId = "tt2", contentType = "movie", title = "Spring", watchedAt = 2)
        repo.refreshIfDue(1, more, emptyList(), "en", t0)
        coVerify { builder.build(any(), any(), match { it.single().rating == ScoutRating.NOT_FOR_ME }, any(), any()) }

        repo.setRating(1, "tt1", false, "Sintel", null, imdbId = "tt1")
        assertEquals(null, repo.rating(1, "tmdb:45745", null, false).first())
    }

    @Test
    fun `a profile whose only signal is a Not for me asks nothing and keeps its day`() = runTest {
        repo.setRating(1, "tt1", false, "Sintel", ScoutRating.NOT_FOR_ME, tmdbId = 45745)

        repo.refreshIfDue(1, history, emptyList(), "en", t0)

        coVerify(exactly = 0) { builder.build(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a damaged ratings list reads as none and the next rating starts over`() = runTest {
        factory.get(1, ScoutPicksRepository.FEATURE).edit { it[stringPreferencesKey("ratings")] = "[{bad" }

        assertEquals(null, repo.rating(1, "tt1", null, false).first())
        assertTrue(repo.setRating(1, "tt1", false, "Sintel", ScoutRating.LIKE, tmdbId = 45745))
        assertEquals(ScoutRating.LIKE, repo.rating(1, "tt1", null, false).first())
    }
}
