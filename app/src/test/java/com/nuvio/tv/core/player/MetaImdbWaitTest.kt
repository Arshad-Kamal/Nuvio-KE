package com.nuvio.tv.core.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * awaitMetaImdbIdIfNeeded: the subtitle fetch waits (bounded) for the async
 * meta fetch only when the IMDb id is still unknown for non-IMDb content.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetaImdbWaitTest {

    @Test
    fun `no wait for imdb content ids`() = runTest {
        val job = Job()
        awaitMetaImdbIdIfNeeded(contentId = "tt0944947", metaImdbId = null, metaJob = job)
        assertEquals(0L, currentTime)
        job.cancel()
    }

    @Test
    fun `no wait once the imdb id is known or the job is done`() = runTest {
        val job = Job()
        awaitMetaImdbIdIfNeeded(contentId = "tmdb:1399", metaImdbId = "tt0944947", metaJob = job)
        awaitMetaImdbIdIfNeeded(contentId = "tmdb:1399", metaImdbId = null, metaJob = Job().apply { complete() })
        awaitMetaImdbIdIfNeeded(contentId = "tmdb:1399", metaImdbId = null, metaJob = null)
        assertEquals(0L, currentTime)
        job.cancel()
    }

    @Test
    fun `waits until the meta job completes`() = runTest {
        val metaJob = launch { delay(1_200) }
        awaitMetaImdbIdIfNeeded(contentId = "tmdb:1399", metaImdbId = null, metaJob = metaJob)
        assertEquals(1_200L, currentTime)
    }

    @Test
    fun `gives up at the timeout`() = runTest {
        val never = CompletableDeferred<Unit>()
        awaitMetaImdbIdIfNeeded(contentId = "kitsu:1376", metaImdbId = null, metaJob = never)
        assertEquals(META_FETCH_JOIN_TIMEOUT_MS, currentTime)
        never.cancel()
    }
}
