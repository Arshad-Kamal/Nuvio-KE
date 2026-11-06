package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Regression test for the ke16 cold-start incident (2026-09-05): a meta lookup
 * fired in the first seconds of a cold start races the disk-cache load of the
 * installed-addon list. On Shield the list lands ~1.2 s after process spawn,
 * so [MetaRepositoryImpl.INSTALLED_ADDONS_WAIT_MS] must exceed that window or
 * the all-addons dispatch resolves against the empty StateFlow seed and raises
 * error_meta_no_addon_for_id ("No installed addon could provide metadata…")
 * for fetchable content. The single-addon getMeta cannot cover this: it
 * requests as-is when the addon is unresolved, by design.
 */
class MetaRepositoryAddonWaitTest {

    @Test
    fun `lookup waits past the cold-start cache window for the addon list to populate`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse()
        val installedAddons = MutableStateFlow<List<Addon>>(emptyList())
        val addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns installedAddons
        }
        val context = mockk<Context> {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "no installed addon"
        }
        val repository = MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true)
        )

        // The addon list arrives at 1.5 s (virtual): past the old 750 ms window,
        // inside the measured ~1.2 s Shield cold-start window's headroom.
        launch {
            delay(1_500)
            installedAddons.value = listOf(testAddon())
        }

        val result = repository.getMetaFromAllAddons("series", "tt3711878", sourceAddonBaseUrl = null).last()

        assertTrue("expected Success but was $result", result is NetworkResult.Success)
    }

    private fun testAddon() = Addon(
        id = "cinemeta",
        name = "Cinemeta",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://cinemeta.example",
        catalogs = emptyList(),
        types = listOf(ContentType.SERIES),
        resources = listOf(
            AddonResource(name = "meta", types = listOf("series", "movie"), idPrefixes = null)
        )
    )

    private fun metaResponse(): Response<MetaResponseDto> =
        Response.success(
            MetaResponseDto(
                meta = MetaDto(id = "tt3711878", type = "series", name = "Waited Meta")
            )
        )
}
