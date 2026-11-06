package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * T9 (0.9.0 relay) meta NotFound classification: when every addon was asked and every
 * one answered "no such item", the error carries META_NOT_FOUND_CODE — a FINAL answer
 * callers may cache. Any request failure keeps a plain (retryable) Error.
 */
class MetaRepositoryNotFoundClassificationTest {

    private val contentId = "tt0944947"

    @Test
    fun `every candidate answering 404 is final not-found`() = runTest {
        val api = apiAnswering(404)
        val repository = newRepository(
            api,
            addon(metaTypes = listOf("series")),
            addon(metaTypes = listOf("series"), id = "test.addon2", baseUrl = "https://addon2.example")
        )

        val result = repository.getMetaFromAllAddons("tv", contentId).last()

        assertEquals(
            NetworkResult.META_NOT_FOUND_CODE,
            (result as NetworkResult.Error).code
        )
    }

    @Test
    fun `a request failure among the answers stays retryable`() = runTest {
        // First candidate times out (REQUEST_FAILED), second answers 404.
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            if (firstArg<String>().startsWith("https://addon.example")) {
                throw IOException("timeout")
            } else {
                Response.error(404, errorBody())
            }
        }
        val repository = newRepository(
            api,
            addon(metaTypes = listOf("series")),
            addon(metaTypes = listOf("series"), id = "test.addon2", baseUrl = "https://addon2.example")
        )

        val result = repository.getMetaFromAllAddons("tv", contentId).last()

        assertNull("mixed failure must not be classified final", (result as NetworkResult.Error).code)
    }

    @Test
    fun `no addon declaring the type is final not-found`() = runTest {
        val api = mockk<AddonApi>()
        val repository = newRepository(api, addon(metaTypes = listOf("movie")))

        val result = repository.getMetaFromAllAddons("tv", contentId).last()

        assertEquals(
            NetworkResult.META_NOT_FOUND_CODE,
            (result as NetworkResult.Error).code
        )
    }

    @Test
    fun `legacy fallback candidates all missing is final not-found`() = runTest {
        // Fallback path: no declared meta type matches tv/series, but rawTypes
        // carries the requested type (legacy behavior) — the addon is attempted
        // there, answers 404, and the outcome is final.
        val api = apiAnswering(404)
        val repository = newRepository(api, addon(metaTypes = listOf("channel"), rawTypes = listOf("tv")))

        val result = repository.getMetaFromAllAddons("tv", contentId).last()

        assertEquals(
            NetworkResult.META_NOT_FOUND_CODE,
            (result as NetworkResult.Error).code
        )
    }

    @Test
    fun `legacy fallback with a request failure stays retryable`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } throws IOException("offline")

        val repository = newRepository(api, addon(metaTypes = listOf("channel"), rawTypes = listOf("tv")))

        val result = repository.getMetaFromAllAddons("tv", contentId).last()

        assertNull((result as NetworkResult.Error).code)
    }

    @Test
    fun `a definitive not-found is remembered and not re-requested`() = runTest {
        val api = apiAnswering(404)
        val repository = newRepository(api, addon(metaTypes = listOf("series")))

        repository.getMetaFromAllAddons("tv", contentId).last()
        val replay = repository.getMetaFromAllAddons("tv", contentId).last()

        assertEquals(NetworkResult.META_NOT_FOUND_CODE, (replay as NetworkResult.Error).code)
        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `a request failure is not remembered as not-found`() = runTest {
        // T31 (P36): one timeout must not make the title "not found" for the miss TTL.
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } throws IOException("timeout")
        val repository = newRepository(api, addon(metaTypes = listOf("series")))

        repository.getMetaFromAllAddons("tv", contentId).last()
        val retry = repository.getMetaFromAllAddons("tv", contentId).last()

        assertNull((retry as NetworkResult.Error).code)
        coVerify(exactly = 2) { api.getMeta(any()) }
    }

    private fun errorBody(): ResponseBody = "".toResponseBody()

    private fun apiAnswering(code: Int): AddonApi = mockk<AddonApi>().also { api ->
        coEvery { api.getMeta(any()) } returns Response.error(code, errorBody())
    }

    private fun addon(
        metaTypes: List<String>,
        id: String = "test.addon",
        baseUrl: String = "https://addon.example",
        rawTypes: List<String> = listOf("movie", "series")
    ) = Addon(
        id = id,
        name = "Test Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = rawTypes,
        resources = listOf(AddonResource(name = "meta", types = metaTypes, idPrefixes = null)),
        idPrefixes = listOf("tt")
    )

    private fun newRepository(api: AddonApi, vararg addons: Addon): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(addons.toList())
        }
        return MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true)
        )
    }
}
