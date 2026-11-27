package com.nuvio.tv.core.contentfilter

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Spec 117 S3: the shared title-tag cache (TTL, cap, file, load guard). */
class TitleTagCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun TestScope.cache(file: File = File(folder.root, "tags.json"), maxEntries: Int = 100) =
        TitleTagCache(file, { now }, backgroundScope, StandardTestDispatcher(testScheduler), writeDelayMs = 10, maxEntries = maxEntries)

    private val sample = FetchedTags(
        genreIds = listOf(80),
        keywords = listOf(9826 to "Murder", 10391 to "mafia", 9826 to "murder"),
        originalLanguage = "en",
        adult = false
    )

    private val movie = TitleRef(TitleKind.MOVIE, "tmdb:603")

    @Test
    fun `reads see nothing before load, then stored tags`() = runTest {
        val cache = cache()
        cache.putTags(TitleKind.MOVIE, 603, sample)
        assertEquals(CachedTags.Missing, cache.read(movie))
        cache.ensureLoaded()
        val tags = (cache.read(movie) as CachedTags.Tags).tags
        assertEquals(setOf(9826, 10391), tags.keywordIds)
        assertEquals(listOf("murder", "mafia"), tags.keywordNames)
        assertEquals(setOf(80), tags.genreIds)
    }

    @Test
    fun `movie and tv with the same id are different titles`() = runTest {
        val cache = cache().apply { ensureLoaded() }
        cache.putTags(TitleKind.MOVIE, 603, sample)
        assertEquals(CachedTags.Missing, cache.read(TitleRef(TitleKind.TV, "tmdb:603")))
    }

    @Test
    fun `tags expire after 30 days, words-only after 7`() = runTest {
        val cache = cache().apply { ensureLoaded() }
        val kitsuLike = TitleRef(TitleKind.MOVIE, "tt0000001")
        cache.putTags(TitleKind.MOVIE, 603, sample)
        cache.putWordsOnly(kitsuLike)
        now += 7 * day + 1
        assertEquals(CachedTags.Missing, cache.read(kitsuLike))
        assertTrue(cache.read(movie) is CachedTags.Tags)
        now += 23 * day
        assertEquals(CachedTags.Missing, cache.read(movie))
    }

    @Test
    fun `an unresolvable id is words-only without any entry`() = runTest {
        val cache = cache().apply { ensureLoaded() }
        assertEquals(CachedTags.WordsOnly, cache.read(TitleRef(TitleKind.TV, "kitsu:12")))
    }

    @Test
    fun `an imdb id reads through its alias`() = runTest {
        val cache = cache().apply { ensureLoaded() }
        cache.putAlias(TitleKind.MOVIE, "tt0133093", 603)
        cache.putTags(TitleKind.MOVIE, 603, sample)
        assertTrue(cache.read(TitleRef(TitleKind.MOVIE, "tt0133093")) is CachedTags.Tags)
        assertEquals(CachedTags.Missing, cache.read(TitleRef(TitleKind.TV, "tt0133093")))
    }

    @Test
    fun `over the cap the oldest entries go`() = runTest {
        val cache = cache(maxEntries = 50).apply { ensureLoaded() }
        repeat(60) { id ->
            now += 1
            cache.putTags(TitleKind.MOVIE, id + 1, sample)
        }
        // Trimmed back to the cap once it passed the margin (cap + 1); the first ones are gone.
        assertEquals(CachedTags.Missing, cache.read(TitleRef(TitleKind.MOVIE, "tmdb:1")))
        assertTrue(cache.read(TitleRef(TitleKind.MOVIE, "tmdb:60")) is CachedTags.Tags)
        val kept = (1..60).count { cache.read(TitleRef(TitleKind.MOVIE, "tmdb:$it")) is CachedTags.Tags }
        assertTrue("kept $kept", kept in 50..51)
    }

    @Test
    fun `a snapshot round-trips through the file, dropping expired entries`() = runTest {
        val file = File(folder.root, "tags.json")
        val first = cache(file).apply { ensureLoaded() }
        first.putTags(TitleKind.MOVIE, 603, sample)
        first.putAlias(TitleKind.TV, "tt0944947", 1399)
        first.putWordsOnly(TitleRef(TitleKind.MOVIE, "tt0000002"))
        first.flush()

        val second = cache(file).apply { ensureLoaded() }
        assertEquals(listOf("murder", "mafia"), (second.read(movie) as CachedTags.Tags).tags.keywordNames)
        assertEquals(1399, second.aliasFor(TitleKind.TV, "tt0944947"))
        assertEquals(CachedTags.WordsOnly, second.read(TitleRef(TitleKind.MOVIE, "tt0000002")))

        now += 31 * day
        val third = cache(file).apply { ensureLoaded() }
        assertEquals(CachedTags.Missing, third.read(movie))
        assertEquals(null, third.aliasFor(TitleKind.TV, "tt0944947"))
        assertEquals(CachedTags.Missing, third.read(TitleRef(TitleKind.MOVIE, "tt0000002")))
    }

    @Test
    fun `the debounced write lands after its delay`() = runTest {
        val file = File(folder.root, "tags.json")
        val cache = cache(file).apply { ensureLoaded() }
        cache.putTags(TitleKind.MOVIE, 603, sample)
        testScheduler.advanceTimeBy(5)
        testScheduler.runCurrent()
        assertTrue("not before the delay", !file.exists())
        testScheduler.advanceTimeBy(10)
        testScheduler.runCurrent()
        assertTrue(file.exists())
        assertTrue(cache(file).apply { ensureLoaded() }.read(movie) is CachedTags.Tags)
    }

    @Test
    fun `a flush before the load keeps the file's entries`() = runTest {
        val file = File(folder.root, "tags.json")
        cache(file).apply {
            ensureLoaded()
            putTags(TitleKind.MOVIE, 1, sample)
            flush()
        }
        cache(file).apply {
            putTags(TitleKind.MOVIE, 2, sample)
            flush()
        }
        val reread = cache(file).apply { ensureLoaded() }
        assertTrue(reread.read(TitleRef(TitleKind.MOVIE, "tmdb:1")) is CachedTags.Tags)
        assertTrue(reread.read(TitleRef(TitleKind.MOVIE, "tmdb:2")) is CachedTags.Tags)
    }

    @Test
    fun `aliases and words-only verdicts are trimmed at the cap too`() = runTest {
        val cache = cache(maxEntries = 10).apply { ensureLoaded() }
        repeat(20) {
            now += 1
            cache.putAlias(TitleKind.MOVIE, "tt$it", it + 1)
            cache.putWordsOnly(TitleRef(TitleKind.MOVIE, "x:$it"))
        }
        assertEquals(null, cache.aliasFor(TitleKind.MOVIE, "tt0"))
        assertEquals(20, cache.aliasFor(TitleKind.MOVIE, "tt19"))
        assertEquals(CachedTags.WordsOnly, cache.read(TitleRef(TitleKind.MOVIE, "x:19")))
    }

    @Test
    fun `an oversized file reads as empty`() = runTest {
        val file = File(folder.root, "tags.json")
        cache(file).apply {
            ensureLoaded()
            putTags(TitleKind.MOVIE, 603, sample)
            flush()
        }
        val small = TitleTagCache(file, { now }, backgroundScope, StandardTestDispatcher(testScheduler), maxFileBytes = 10)
        small.ensureLoaded()
        assertEquals(CachedTags.Missing, small.read(movie))
    }

    @Test
    fun `a corrupt file reads as empty`() = runTest {
        val file = File(folder.root, "tags.json").apply { writeText("{not json") }
        val cache = cache(file).apply { ensureLoaded() }
        assertEquals(CachedTags.Missing, cache.read(movie))
        cache.putTags(TitleKind.MOVIE, 603, sample)
        assertTrue(cache.read(movie) is CachedTags.Tags)
    }

    @Test
    fun `a put that races the load is kept over the file's older copy`() = runTest {
        val file = File(folder.root, "tags.json")
        cache(file).apply {
            ensureLoaded()
            putTags(TitleKind.MOVIE, 603, sample)
            flush()
        }
        val cache = cache(file)
        cache.putTags(TitleKind.MOVIE, 603, sample.copy(keywords = listOf(1 to "fresh")))
        cache.ensureLoaded()
        assertEquals(listOf("fresh"), (cache.read(movie) as CachedTags.Tags).tags.keywordNames)
    }

    @Test
    fun `concurrent puts and flushes leave a readable file with every entry`() = runTest {
        val file = File(folder.root, "tags.json")
        val cache = cache(file).apply { ensureLoaded() }
        (1..40).map { id ->
            async {
                cache.putTags(TitleKind.TV, id, sample)
                if (id % 10 == 5) cache.flush() // never after the last puts
            }
        }.awaitAll()
        // No closing flush: the debounced write must capture every entry by itself.
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        val reread = cache(file).apply { ensureLoaded() }
        assertEquals(40, (1..40).count { reread.read(TitleRef(TitleKind.TV, "tmdb:$it")) is CachedTags.Tags })
    }
}
