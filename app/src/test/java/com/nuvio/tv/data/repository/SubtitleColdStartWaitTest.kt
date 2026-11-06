package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6 pins for the subtitle cold-start wait (SubtitleRepositoryImpl): the first
 * addons emission can be the seeded empty list while the manifest loads from
 * disk. Tested against the extracted wait on a virtual-time dispatcher — the
 * repository body runs on Dispatchers.IO, whose real clock virtual time
 * cannot reach.
 */
class SubtitleColdStartWaitTest {

    @Test
    fun `manifest populating mid-wait proceeds before the timeout`() = runTest {
        val addons = MutableStateFlow(emptyList<Addon>())
        launch {
            delay(100)
            // A disabled addon must be filtered out on the way through.
            addons.value = listOf(addon("off", enabled = false), addon("on"))
        }

        val result = awaitInstalledAddons(addons)

        assertEquals(listOf("on"), result.map { it.id })
        assertTrue(
            "expected an early proceed, waited ${currentTime}ms of virtual time",
            currentTime < 5_000L
        )
    }

    @Test
    fun `permanently empty manifest returns empty after the full wait`() = runTest {
        // A manifest source that never emits an addon and never completes —
        // flowOf(emptyList()) would end the flow and fail first{} immediately
        // instead of exercising the timeout.
        val result = awaitInstalledAddons(MutableStateFlow(emptyList<Addon>()))

        assertTrue(result.isEmpty())
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `mid-wait cancellation rethrows instead of reading as no addons`() = runTest {
        var completedNormally = false
        val job = launch {
            awaitInstalledAddons(MutableStateFlow(emptyList<Addon>()))
            completedNormally = true
        }
        runCurrent()
        assertTrue(job.isActive)

        job.cancelAndJoin()

        assertFalse(
            "cancellation was swallowed and read as an empty manifest",
            completedNormally
        )
    }

    private fun addon(id: String, enabled: Boolean = true): Addon = Addon(
        id = id,
        name = id,
        version = "1",
        description = null,
        logo = null,
        baseUrl = "https://$id.example.test",
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList(),
        enabled = enabled
    )
}
