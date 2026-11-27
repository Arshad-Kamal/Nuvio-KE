package com.nuvio.tv.core.tracking

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingProgressRefreshCoordinatorTest {

    private val refreshed = mutableListOf<TrackingProviderId>()

    private fun provider(id: TrackingProviderId, connected: Boolean = true, failure: Exception? = null) =
        mockk<TrackingProgressProvider> {
            every { providerId } returns id
            every { isAuthenticated } returns flowOf(connected)
            coEvery { refresh(any()) } answers {
                refreshed += id
                failure?.let { throw it }
            }
        }

    @Test
    fun `only connected trackers refresh`() = runTest {
        val coordinator = TrackingProgressRefreshCoordinator(
            TrackingProgressProviderRegistry(setOf(provider(TrackingProviderId.MDBLIST), provider(TrackingProviderId.SIMKL, connected = false)))
        )

        assertTrue(coordinator.refreshConnected(TrackingRefreshIntent.AUTOMATIC).isEmpty())
        assertEquals(listOf(TrackingProviderId.MDBLIST), refreshed)
    }

    @Test
    fun `a tracker's own cancellation or failure does not stop the other, in either mode`() = runTest {
        listOf(false, true).forEach { oneAtATime ->
            refreshed.clear()
            val coordinator = TrackingProgressRefreshCoordinator(
                TrackingProgressProviderRegistry(
                    setOf(
                        provider(TrackingProviderId.MDBLIST, failure = CancellationException("timed out")),
                        provider(TrackingProviderId.SIMKL, failure = IllegalStateException("429"))
                    )
                )
            )

            val failures = coordinator.refreshConnected(TrackingRefreshIntent.USER_INITIATED, oneAtATime)

            assertEquals("oneAtATime=$oneAtATime", listOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL), failures.map { it.providerId })
            assertEquals("oneAtATime=$oneAtATime", setOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL), refreshed.toSet())
        }
    }

    @Test
    fun `a connection check that fails stops only its own tracker`() = runTest {
        val broken = mockk<TrackingProgressProvider> {
            every { providerId } returns TrackingProviderId.MDBLIST
            every { isAuthenticated } returns kotlinx.coroutines.flow.flow { throw java.io.IOException("corrupt store") }
        }
        val coordinator = TrackingProgressRefreshCoordinator(
            TrackingProgressProviderRegistry(setOf(broken, provider(TrackingProviderId.SIMKL)))
        )

        val failures = coordinator.refreshConnected(TrackingRefreshIntent.AUTOMATIC, oneAtATime = true)

        assertEquals(listOf(TrackingProviderId.MDBLIST), failures.map { it.providerId })
        assertEquals(listOf(TrackingProviderId.SIMKL), refreshed)
    }

    @Test
    fun `one at a time keeps display order`() = runTest {
        val coordinator = TrackingProgressRefreshCoordinator(
            TrackingProgressProviderRegistry(setOf(provider(TrackingProviderId.SIMKL), provider(TrackingProviderId.MDBLIST)))
        )

        coordinator.refreshConnected(TrackingRefreshIntent.AUTOMATIC, oneAtATime = true)

        assertEquals(listOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL), refreshed)
    }

    @Test
    fun `registries order by display order and refuse duplicate ids`() {
        val writer = { id: TrackingProviderId -> mockk<TrackingHistoryWriter> { every { providerId } returns id } }
        val writers = TrackingHistoryWriterRegistry(setOf(writer(TrackingProviderId.SIMKL), writer(TrackingProviderId.MDBLIST)))
        assertEquals(listOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL), writers.writers().map { it.providerId })
        assertEquals(TrackingProviderId.SIMKL, writers.writer(TrackingProviderId.SIMKL)?.providerId)

        val progress = TrackingProgressProviderRegistry(setOf(provider(TrackingProviderId.SIMKL)))
        assertEquals(null, progress.provider(TrackingProviderId.MDBLIST))

        assertThrows { TrackingHistoryWriterRegistry(setOf(writer(TrackingProviderId.SIMKL), writer(TrackingProviderId.SIMKL))) }
        assertThrows { TrackingProgressProviderRegistry(setOf(provider(TrackingProviderId.SIMKL), provider(TrackingProviderId.SIMKL))) }
    }

    private fun assertThrows(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }

}
