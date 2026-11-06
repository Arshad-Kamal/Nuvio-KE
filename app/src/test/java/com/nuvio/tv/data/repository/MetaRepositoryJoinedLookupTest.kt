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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Spec 37 P1 (ysosrs c6129281b): a caller joining an in-flight all-addons lookup takes its
 * outcome, a failure included, instead of running the addon loop a second time.
 */
class MetaRepositoryJoinedLookupTest {

    @Test
    fun `a joined lookup that failed is shared, not re-run`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            delay(1_000)
            Response.error<MetaResponseDto>(500, "".toResponseBody(null))
        }
        val addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns MutableStateFlow(listOf(testAddon()))
        }
        val context = mockk<Context> {
            every { getString(any()) } returns "x"
            every { getString(any(), *anyVararg()) } returns "x"
        }
        val repository = MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true)
        )

        val first = async { repository.getMetaFromAllAddons("movie", "tt0000001", sourceAddonBaseUrl = null).last() }
        delay(100) // the first lookup is in flight
        val second = async { repository.getMetaFromAllAddons("movie", "tt0000001", sourceAddonBaseUrl = null).last() }

        val firstResult = first.await()
        assertTrue(firstResult is NetworkResult.Error)
        // The joiner reports the shared failure itself (same message and code).
        assertEquals(firstResult, second.await())
        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    private fun testAddon() = Addon(
        id = "cinemeta",
        name = "Cinemeta",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://cinemeta.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE),
        resources = listOf(AddonResource(name = "meta", types = listOf("movie"), idPrefixes = null))
    )
}
