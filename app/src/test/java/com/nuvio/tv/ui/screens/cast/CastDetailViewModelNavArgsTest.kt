package com.nuvio.tv.ui.screens.cast

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CastDetailViewModelNavArgsTest {

    // StandardTestDispatcher as Main: the init loads are queued and never run here.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `person name keeps plus signs and percent escapes`() {
        // Navigation has already decoded the arg once; a second decode would give "Tom Jerry A".
        val viewModel = viewModel(mapOf("personId" to "7", "personName" to "Tom+Jerry %41"))

        assertEquals("Tom+Jerry %41", viewModel.personName)
        assertEquals("", viewModel.department)
        assertEquals("", viewModel.sourceTitle)
        assertEquals("", viewModel.sectionLabel)
        assertEquals(emptyList<String>(), viewModel.breadcrumb)
    }

    @Test
    fun `department and breadcrumb come from the route`() {
        val viewModel = viewModel(
            mapOf(
                "personId" to "7", "personName" to "A", "department" to "Writing",
                "sourceTitle" to "Tears+of Steel", "sectionLabel" to "Written by"
            )
        )

        assertEquals("Writing", viewModel.department)
        assertEquals("Tears+of Steel", viewModel.sourceTitle)
        assertEquals("Written by", viewModel.sectionLabel)
        assertEquals(listOf("Tears+of Steel", "Written by"), viewModel.breadcrumb)
    }

    private fun viewModel(args: Map<String, Any>) = CastDetailViewModel(
        context = mockk(relaxed = true),
        tmdbMetadataService = mockk(relaxed = true),
        tmdbSettingsDataStore = mockk(relaxed = true),
        tmdbApiKeys = mockk(relaxed = true),
        layoutPreferenceDataStore = mockk(relaxed = true),
        watchProgressRepository = mockk(relaxed = true),
        watchedSeriesStateHolder = mockk(relaxed = true),
        posterOptions = mockk(relaxed = true),
        savedStateHandle = SavedStateHandle(args)
    )
}
