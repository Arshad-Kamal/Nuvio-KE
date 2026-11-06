package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse
import com.nuvio.tv.data.remote.api.TmdbFindResponse
import com.nuvio.tv.data.remote.api.TmdbFindResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Pins TmdbService's shared id lookups: one request per key, and a cancelled first
 * caller never fails the callers still awaiting the same lookup.
 */
class TmdbServiceTest {

    @Test
    fun `a cancelled first caller does not fail a second imdbToTmdb waiter`() = runTest {
        val api = mockk<TmdbApi>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { api.findByExternalId("tt0133093", TEST_TMDB_KEY, any()) } coAnswers {
            calls += 1
            gate.await()
            Response.success(TmdbFindResponse(movieResults = listOf(TmdbFindResult(id = 603))))
        }
        val service = TmdbService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        val first = async { service.imdbToTmdb("tt0133093", "movie") }
        advanceUntilIdle()
        val second = async { service.imdbToTmdb("tt0133093", "movie") }
        advanceUntilIdle()

        first.cancel()
        gate.complete(Unit)

        assertEquals(603, second.await())
        assertTrue(first.isCancelled)
        assertEquals(1, calls)
        // Both directions are cached: no further request for either lookup.
        assertEquals(603, service.imdbToTmdb("tt0133093", "movie"))
        assertEquals("tt0133093", service.tmdbToImdb(603, "movie"))
        assertEquals(1, calls)
    }

    @Test
    fun `a cancelled first caller does not fail a second tmdbToImdb waiter`() = runTest {
        val api = mockk<TmdbApi>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { api.getTvExternalIds(1399, any()) } coAnswers {
            calls += 1
            gate.await()
            Response.success(TmdbExternalIdsResponse(id = 1399, imdbId = "tt0944947"))
        }
        val service = TmdbService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        val first = async { service.tmdbToImdb(1399, "series") }
        advanceUntilIdle()
        val second = async { service.tmdbToImdb(1399, "series") }
        advanceUntilIdle()

        first.cancel()
        gate.complete(Unit)

        assertEquals("tt0944947", second.await())
        assertTrue(first.isCancelled)
        assertEquals(1, calls)
    }
}
