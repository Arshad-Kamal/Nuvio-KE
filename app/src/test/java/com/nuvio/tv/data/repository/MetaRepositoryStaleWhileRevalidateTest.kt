package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.getOrStartShared
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Spec 95 M2 (D3-D5): an expired cached meta is served at once while one refresh runs. */
class MetaRepositoryStaleWhileRevalidateTest {

    private val contentId = "tt0944947"
    private val baseUrl = "https://addon.example"
    private val start = 1_000_000_000L
    private val pastFresh = 46 * 60 * 1000L
    private val pastStaleCap = 24L * 60 * 60 * 1000 + 1

    @Test
    fun `a stale entry is served at once and one refresh replaces it`() = runTest {
        val calls = AtomicInteger()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers { metaResponse("v${calls.incrementAndGet()}") }
        val (repository, clock) = newRepository(api)

        repository.getMetaFromAllAddons("series", contentId).last()
        clock[0] = start + pastFresh
        val emissions = repository.getMetaFromAllAddons("series", contentId).toList()

        assertEquals(listOf("v1"), emissions.map { (it as NetworkResult.Success).data.name })
        coVerify(exactly = 2, timeout = VERIFY_TIMEOUT_MS) { api.getMeta(any()) }
        awaitCachedName(repository, "v2")
        assertEquals("v2", (repository.getMetaFromAllAddons("series", contentId).last() as NetworkResult.Success).data.name)
        coVerify(exactly = 2) { api.getMeta(any()) }
    }

    @Test
    fun `an addon max-age shorter than the cache TTL also makes the entry stale`() = runTest {
        val calls = AtomicInteger()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            Response.success(
                MetaResponseDto(meta = MetaDto(id = contentId, type = "series", name = "v${calls.incrementAndGet()}")),
                okhttp3.Headers.headersOf("Cache-Control", "max-age=600")
            )
        }
        val (repository, clock) = newRepository(api)

        repository.getMetaFromPrimaryAddon("series", contentId).last()
        clock[0] = start + 11 * 60 * 1000L
        val second = repository.getMetaFromPrimaryAddon("series", contentId).last()

        assertEquals("v1", (second as NetworkResult.Success).data.name)
        coVerify(exactly = 2, timeout = VERIFY_TIMEOUT_MS) { api.getMeta(any()) }
    }

    @Test
    fun `an entry past the stale cap is fetched as before`() = runTest {
        val calls = AtomicInteger()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers { metaResponse("v${calls.incrementAndGet()}") }
        val (repository, clock) = newRepository(api)

        repository.getMeta(baseUrl, "series", contentId).last()
        clock[0] = start + pastStaleCap
        val emissions = repository.getMeta(baseUrl, "series", contentId).toList()

        assertTrue(emissions.first() is NetworkResult.Loading)
        assertEquals("v2", (emissions.last() as NetworkResult.Success).data.name)
    }

    @Test
    fun `a failed refresh keeps the stale entry`() = runTest {
        val calls = AtomicInteger()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            if (calls.incrementAndGet() == 1) metaResponse("v1") else throw IOException("offline")
        }
        val (repository, clock) = newRepository(api)

        repository.getMeta(baseUrl, "series", contentId).last()
        clock[0] = start + pastFresh
        // A later read starts another refresh only once the failed one has settled, so
        // reading until a third request lands proves the failure kept the entry.
        repeat(100) {
            val read = repository.getMeta(baseUrl, "series", contentId).toList()
            assertEquals(listOf("v1"), read.map { (it as NetworkResult.Success).data.name })
            if (calls.get() >= 3) return@runTest
            Thread.sleep(20)
        }
        throw AssertionError("the failed refresh never settled")
    }

    @Test
    fun `a refresh answering not-found drops the entry`() = runTest {
        val calls = AtomicInteger()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            if (calls.incrementAndGet() == 1) metaResponse("v1") else Response.success(MetaResponseDto(meta = null))
        }
        val (repository, clock) = newRepository(api)

        repository.getMetaFromAllAddons("series", contentId).last()
        clock[0] = start + pastFresh
        repository.getMetaFromAllAddons("series", contentId).last()

        awaitCachedName(repository, null)
    }

    @Test
    fun `getCachedMeta serves a stale entry without refreshing`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("v1")
        val (repository, clock) = newRepository(api)

        repository.getMetaFromAllAddons("series", contentId).last()
        clock[0] = start + pastFresh

        assertEquals("v1", repository.getCachedMeta("series", contentId)?.name)
        Thread.sleep(100)
        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `a stale entry from an addon since removed is dropped, not refreshed`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("v1")
        val installed = MutableStateFlow(listOf(addon()))
        val (repository, clock) = newRepository(api, installed)

        repository.getMetaFromAllAddons("series", contentId).last()
        installed.value = listOf(addon(id = "other", url = "https://other.example"))
        clock[0] = start + pastFresh
        repository.getMetaFromAllAddons("series", contentId).last()

        awaitCachedName(repository, null)
        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `a primary entry refreshes only while its addon is still the primary`() = runTest {
        val calls = AtomicInteger()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers { metaResponse("v${calls.incrementAndGet()}") }
        val installed = MutableStateFlow(listOf(addon()))
        val (repository, clock) = newRepository(api, installed)

        repository.getMetaFromPrimaryAddon("series", contentId).last()
        clock[0] = start + pastFresh
        repository.getMetaFromPrimaryAddon("series", contentId).last()
        // Polls run at the first-fetch time, where every entry here reads fresh, so a poll
        // never starts a refresh of its own (Spec 127: one racing the landing could send a
        // second request).
        clock[0] = start
        awaitUntil("the refresh lands v2") {
            (repository.getMetaFromPrimaryAddon("series", contentId).last() as NetworkResult.Success).data.name == "v2"
        }
        coVerify(exactly = 2) { api.getMeta(any()) }

        // Another addon becomes primary: the next stale read drops the entry instead.
        installed.value = listOf(addon(id = "other", url = "https://other.example"), addon())
        clock[0] = start + 2 * pastFresh
        repository.getMetaFromPrimaryAddon("series", contentId).last()
        clock[0] = start
        awaitUntil("the dropped entry makes a read fetch") {
            repository.getMetaFromPrimaryAddon("series", contentId).toList().first() is NetworkResult.Loading
        }
        // The stale read sent nothing: the third request is the fetch after the drop.
        coVerify(exactly = 3) { api.getMeta(any()) }
    }

    @Test
    fun `a refreshed entry still ages out from its first fetch`() = runTest {
        val calls = AtomicInteger()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers { metaResponse("v${calls.incrementAndGet()}") }
        val (repository, clock) = newRepository(api)

        repository.getMetaFromAllAddons("series", contentId).last()
        clock[0] = start + 23 * 60 * 60 * 1000L
        repository.getMetaFromAllAddons("series", contentId).last()
        awaitCachedName(repository, "v2")
        clock[0] = start + pastStaleCap

        val emissions = repository.getMetaFromAllAddons("series", contentId).toList()

        assertTrue(emissions.first() is NetworkResult.Loading)
        assertEquals("v3", (emissions.last() as NetworkResult.Success).data.name)
    }

    @Test
    fun `concurrent stale reads share one refresh request`() = runTest {
        val id = "tt0903747"
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            val n = calls.incrementAndGet()
            if (n > 1) gate.await()
            metaResponse("v$n", id)
        }
        // Counts the callers holding a shared request, so the gate opens only once all three
        // refreshes joined the one in flight (Spec 127: a refresh reaching the in-flight map
        // after the request finished but before it landed sends its own). The static mock is
        // JVM-wide: this test's own id keeps other callers (Tmdb, earlier tests' leftover
        // refreshes) out of the count.
        val joins = AtomicInteger()
        mockkStatic(IN_FLIGHT_REQUESTS)
        try {
            every {
                any<ConcurrentHashMap<Any, Deferred<Any?>>>().getOrStartShared(
                    any(),
                    match { it.toString().endsWith(":$id") },
                    any()
                )
            } answers { callOriginal().also { joins.incrementAndGet() } }
            val (repository, clock) = newRepository(api)

            repository.getMetaFromAllAddons("series", id).last()
            val joinsBefore = joins.get()
            clock[0] = start + pastFresh
            repeat(3) { repository.getMetaFromAllAddons("series", id).last() }
            awaitUntil("three refreshes join the request") { joins.get() >= joinsBefore + 3 }
            coVerify(exactly = 2, timeout = VERIFY_TIMEOUT_MS) { api.getMeta(any()) }
            gate.complete(Unit)

            awaitCachedName(repository, "v2", id)
            coVerify(exactly = 2) { api.getMeta(any()) }
        } finally {
            unmockkStatic(IN_FLIGHT_REQUESTS)
        }
    }

    @Test
    fun `a refresh landing after clearCache writes nothing`() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            val n = calls.incrementAndGet()
            if (n > 1) gate.await()
            metaResponse("v$n")
        }
        val (repository, clock) = newRepository(api)

        repository.getMetaFromAllAddons("series", contentId).last()
        clock[0] = start + pastFresh
        repository.getMetaFromAllAddons("series", contentId).last()
        coVerify(exactly = 2, timeout = VERIFY_TIMEOUT_MS) { api.getMeta(any()) }
        repository.clearCache()
        gate.complete(Unit)
        Thread.sleep(100)

        assertEquals(null, repository.getCachedMeta("series", contentId))
    }

    /** Polls [condition] in real time: refreshes run in the repository's own IO scope. */
    private inline fun awaitUntil(what: String, condition: () -> Boolean) {
        repeat(250) {
            if (condition()) return
            Thread.sleep(20)
        }
        if (!condition()) throw AssertionError("timed out: $what")
    }

    private fun awaitCachedName(repository: MetaRepositoryImpl, name: String?, id: String = contentId) =
        awaitUntil("cached name $name") { repository.getCachedMeta("series", id)?.name == name }

    private fun metaResponse(name: String, id: String = contentId): Response<MetaResponseDto> =
        Response.success(MetaResponseDto(meta = MetaDto(id = id, type = "series", name = name)))

    private fun addon(id: String = "test.addon", url: String = baseUrl) = Addon(
        id = id,
        name = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = url,
        catalogs = emptyList(),
        types = listOf(ContentType.SERIES),
        rawTypes = listOf("series"),
        resources = listOf(AddonResource(name = "meta", types = listOf("series"), idPrefixes = null)),
        idPrefixes = listOf("tt")
    )

    private fun newRepository(
        api: AddonApi,
        installed: Flow<List<Addon>> = flowOf(listOf(addon()))
    ): Pair<MetaRepositoryImpl, LongArray> {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "Meta error"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns installed
        }
        val clock = longArrayOf(start)
        val repository = MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true)
        )
        repository.nowMs = { clock[0] }
        return repository to clock
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 2_000L
        const val IN_FLIGHT_REQUESTS = "com.nuvio.tv.core.util.InFlightRequestsKt"
    }
}
