package com.nuvio.tv.core.tracking

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 104 S2b: the tracker write queue (order, failure isolation, the bound) and source-following reads. */
class TrackingProgressRouterTest {

    private val calls = mutableListOf<String>()

    private inner class Writer(
        override val providerId: TrackingProviderId,
        private val behaviour: suspend () -> Unit = {}
    ) : TrackingHistoryWriter {
        override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
            calls += "${providerId.storageId}:add:$profileId:${items.single().media.ids.imdb}"
            behaviour()
            return TrackingMutationResult(items.size)
        }

        override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
            calls += "${providerId.storageId}:remove:$profileId:${items.single().ids.imdb}"
            behaviour()
            return TrackingMutationResult(items.size)
        }
    }

    private fun TestScope.router(vararg writers: TrackingHistoryWriter, activeProvider: kotlinx.coroutines.flow.Flow<TrackingProgressProvider?> = flowOf(null)) =
        TrackingProgressRouter(
            activeProviderFlow = activeProvider,
            hasProgressProviders = true,
            historyWriters = TrackingHistoryWriterRegistry(writers.toSet()),
            registry = TrackingProviderRegistry(
                setOf(FakeTrackingProvider(TrackingProviderId.MDBLIST, true), FakeTrackingProvider(TrackingProviderId.SIMKL, true))
            ),
            scope = backgroundScope
        )

    private fun movie(id: String) = buildTrackingMediaReference("movie", id)

    @Test
    fun `writes run in call order with the captured profile, and a failing or hung tracker never stops the queue`() = runTest {
        val router = router(
            Writer(TrackingProviderId.MDBLIST) { throw IllegalStateException("offline") },
            Writer(TrackingProviderId.SIMKL) { if (calls.none { it.contains(":remove:") }) awaitCancellation() }
        )

        router.addToHistory(7, listOf(TrackingHistoryItem(movie("tt1"))))
        router.removeFromHistory(7, listOf(movie("tt1")))
        router.awaitQueuedWrites()

        // The hung Simkl add is cut at the bound; the removal after it still runs, after both adds.
        assertEquals(setOf("mdblist:add:7:tt1", "simkl:add:7:tt1"), calls.take(2).toSet())
        assertEquals(setOf("mdblist:remove:7:tt1", "simkl:remove:7:tt1"), calls.drop(2).toSet())
        assertEquals(TRACKING_WRITE_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test
    fun `whole-series marks and unmarks are never sent`() = runTest {
        val router = router(Writer(TrackingProviderId.MDBLIST))
        router.addToHistory(1, listOf(TrackingHistoryItem(buildTrackingMediaReference("series", "tt2"))))
        router.removeFromHistory(1, listOf(buildTrackingMediaReference("anime", "kitsu:3")))
        router.awaitQueuedWrites()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `one collection of a routed read follows source switches`() = runTest {
        val provider = mockk<TrackingProgressProvider> { every { allProgress } returns flowOf(emptyList()) }
        val active = MutableStateFlow<TrackingProgressProvider?>(null)
        val router = router(activeProvider = active)
        val seen = mutableListOf<String>()
        val job = launch {
            router.route(flowOf("local")) { flowOf("tracker") }.take(3).toList(seen)
        }
        testScheduler.runCurrent()
        active.value = provider
        testScheduler.runCurrent()
        active.value = null
        job.join()
        assertEquals(listOf("local", "tracker", "local"), seen)
    }
}
