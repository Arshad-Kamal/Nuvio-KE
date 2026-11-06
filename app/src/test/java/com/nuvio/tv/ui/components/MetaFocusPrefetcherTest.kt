package com.nuvio.tv.ui.components

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaFocusPrefetcherTest {

    // backgroundScope work is not run by advanceUntilIdle, so the 150 ms debounce is
    // advanced explicitly with advanceTimeBy + runCurrent.

    /**
     * Records each requested id; [resultFor] maps the 1-based call number to the result,
     * null meaning the request never completes unless cancelled.
     */
    private fun repository(
        requested: MutableList<String>,
        resultFor: (call: Int) -> NetworkResult<Meta>? = { success() }
    ): MetaRepository {
        val repository = mockk<MetaRepository>()
        every { repository.getMetaFromAllAddons(any(), any(), any()) } answers {
            val id = secondArg<String>()
            flow {
                requested += id
                emit(resultFor(requested.size) ?: awaitCancellation())
            }
        }
        return repository
    }

    private fun success(): NetworkResult<Meta> = NetworkResult.Success(mockk<Meta>(relaxed = true))

    private fun hangFirst(call: Int): NetworkResult<Meta>? = if (call == 1) null else success()

    @Test
    fun `a fetch cancelled by the next focus is retried when the card regains focus`() = runTest {
        val requested = mutableListOf<String>()
        val prefetcher = MetaFocusPrefetcher(repository(requested, ::hangFirst))
        prefetcher.bind(backgroundScope)

        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()
        prefetcher.prefetchOnFocus("b", "movie")
        advanceTimeBy(200)
        runCurrent()
        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf("a", "b", "a"), requested)
    }

    @Test
    fun `a successfully fetched id is not fetched again`() = runTest {
        val requested = mutableListOf<String>()
        val prefetcher = MetaFocusPrefetcher(repository(requested))
        prefetcher.bind(backgroundScope)

        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()
        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf("a"), requested)
    }

    @Test
    fun `refocusing the card whose fetch is in flight keeps that fetch`() = runTest {
        val requested = mutableListOf<String>()
        val prefetcher = MetaFocusPrefetcher(repository(requested, ::hangFirst))
        prefetcher.bind(backgroundScope)

        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()
        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf("a"), requested)
    }

    @Test
    fun `refocusing the card within its debounce keeps the original timer`() = runTest {
        val requested = mutableListOf<String>()
        val prefetcher = MetaFocusPrefetcher(repository(requested))
        prefetcher.bind(backgroundScope)

        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(100)
        runCurrent()
        prefetcher.prefetchOnFocus("a", "movie")
        // 160 ms after the first focus: a restarted timer would still be waiting.
        advanceTimeBy(60)
        runCurrent()

        assertEquals(listOf("a"), requested)
    }

    @Test
    fun `moving focus within the debounce fetches only the last card`() = runTest {
        val requested = mutableListOf<String>()
        val prefetcher = MetaFocusPrefetcher(repository(requested))
        prefetcher.bind(backgroundScope)

        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(50)
        runCurrent()
        prefetcher.prefetchOnFocus("b", "movie")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf("b"), requested)
    }

    @Test
    fun `a failed fetch is retried the next time the card gains focus`() = runTest {
        val requested = mutableListOf<String>()
        val prefetcher = MetaFocusPrefetcher(
            repository(requested) { call -> if (call == 1) NetworkResult.Error("offline") else success() }
        )
        prefetcher.bind(backgroundScope)

        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()
        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf("a", "a"), requested)
    }

    @Test
    fun `focus before bind fetches nothing`() = runTest {
        val requested = mutableListOf<String>()
        val prefetcher = MetaFocusPrefetcher(repository(requested))

        prefetcher.prefetchOnFocus("a", "movie")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(emptyList<String>(), requested)
    }
}
