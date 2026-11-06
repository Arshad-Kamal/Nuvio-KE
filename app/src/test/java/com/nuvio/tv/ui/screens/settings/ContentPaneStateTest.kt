package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentPaneStateTest {

    private fun catalog(id: String, searchOnly: Boolean = false) = CatalogDescriptor(
        type = ContentType.MOVIE,
        id = id,
        name = id,
        extra = if (searchOnly) listOf(CatalogExtra(name = "search", isRequired = true)) else emptyList()
    )

    private fun addon(id: String, catalogs: List<CatalogDescriptor>, enabled: Boolean = true) = Addon(
        id = id,
        name = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = catalogs,
        types = listOf(ContentType.MOVIE),
        resources = emptyList(),
        enabled = enabled
    )

    private val collections = listOf(Collection(id = "c1", title = "One"), Collection(id = "c2", title = "Two"))

    @Test
    fun `counts every addon, Home catalog rows and collections`() {
        val state = contentPaneState(
            addons = listOf(
                addon("a", listOf(catalog("top"), catalog("find", searchOnly = true))),
                addon("b", listOf(catalog("new")), enabled = false),
                addon("c", emptyList())
            ),
            collections = collections,
            readOnly = false
        )
        assertEquals(3, state.addonCount)
        // a/top + the two collections: search-only and disabled-addon catalogs are not Home rows.
        assertEquals(3, state.homeCatalogRows)
        assertEquals(2, state.collectionCount)
        assertTrue(state.showCatalogOrder)
    }

    @Test
    fun `catalog order hides without an addon catalog on Home, collections alone included`() {
        val state = contentPaneState(
            addons = listOf(addon("a", listOf(catalog("find", searchOnly = true)))),
            collections = collections,
            readOnly = false
        )
        assertFalse(state.showCatalogOrder)
    }

    @Test
    fun `catalog order hides for a read-only profile`() {
        val state = contentPaneState(
            addons = listOf(addon("a", listOf(catalog("top")))),
            collections = emptyList(),
            readOnly = true
        )
        assertFalse(state.showCatalogOrder)
        assertTrue(state.readOnly)
    }
}
