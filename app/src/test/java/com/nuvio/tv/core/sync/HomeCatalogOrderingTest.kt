package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the home-catalog ordering engine shared by the TV Catalog Order screen
 * and the QR config-server page state (backlog G4-5). The web client's JS
 * mirror in AddonWebPage.kt must stay behaviorally identical to these cases.
 */
class HomeCatalogOrderingTest {

    @Test
    fun `non-follow order is saved order first with unseen appended in manifest order`() {
        val addonEntries = listOf(
            entry("a1_movie_top"), entry("a1_series_popular"), entry("a2_movie_latest")
        )
        val collectionEntries = listOf(entry("collection_c1"))

        val order = HomeCatalogOrdering.effectiveOrder(
            addonEntries = addonEntries,
            collectionEntries = collectionEntries,
            savedOrderKeys = listOf(
                "collection_c1", "a2_movie_latest", "a1_movie_top", "stale_gone_key", "a2_movie_latest"
            ),
            followAddonsOrder = false
        )

        // Saved (valid, deduped) first; unseen (a1_series_popular) appended in manifest order.
        assertEquals(
            listOf("collection_c1", "a2_movie_latest", "a1_movie_top", "a1_series_popular"),
            order
        )
    }

    @Test
    fun `follow order snaps addons to manifest order and keeps collections between blocks`() {
        val a1 = entry("a1_k", "Addon One")
        val b1 = entry("b1_k", "Addon Two")
        val c1 = entry("collection_c1", "Collection")

        // Collection saved between the two addon blocks stays between them.
        assertEquals(
            listOf("a1_k", "collection_c1", "b1_k"),
            HomeCatalogOrdering.effectiveOrder(
                addonEntries = listOf(a1, b1),
                collectionEntries = listOf(c1),
                savedOrderKeys = listOf("a1_k", "collection_c1", "b1_k"),
                followAddonsOrder = true
            )
        )

        // Saved addon order is ignored: manifest order wins, and a collection saved
        // after the last-positioned addon flushes the whole prefix before its slot.
        assertEquals(
            listOf("a1_k", "b1_k", "collection_c1"),
            HomeCatalogOrdering.effectiveOrder(
                addonEntries = listOf(a1, b1),
                collectionEntries = listOf(c1),
                savedOrderKeys = listOf("b1_k", "collection_c1", "a1_k"),
                followAddonsOrder = true
            )
        )
    }

    @Test
    fun `follow order normalizes a collection wedged inside one addon's block to the block end`() {
        val addonEntries = listOf(
            entry("a1_movie_top", "Addon One"),
            entry("a1_series_popular", "Addon One"),
            entry("a2_movie_latest", "Addon Two")
        )
        val collectionEntries = listOf(entry("collection_c1"))

        val order = HomeCatalogOrdering.effectiveOrder(
            addonEntries = addonEntries,
            collectionEntries = collectionEntries,
            // Collection between a1's two catalogs in the saved order.
            savedOrderKeys = listOf("a1_movie_top", "collection_c1", "a1_series_popular", "a2_movie_latest"),
            followAddonsOrder = true
        )

        assertEquals(
            listOf("a1_movie_top", "a1_series_popular", "collection_c1", "a2_movie_latest"),
            order
        )
    }

    @Test
    fun `follow order without a saved order puts collections last`() {
        val addonEntries = listOf(entry("a1_movie_top"), entry("a2_movie_latest"))
        val collectionEntries = listOf(entry("collection_c1"), entry("collection_c2"))

        val order = HomeCatalogOrdering.effectiveOrder(
            addonEntries = addonEntries,
            collectionEntries = collectionEntries,
            savedOrderKeys = emptyList(),
            followAddonsOrder = true
        )

        assertEquals(listOf("a1_movie_top", "a2_movie_latest", "collection_c1", "collection_c2"), order)
    }

    @Test
    fun `collection block jump moves above the previous addon block and skips adjacent collections`() {
        // addon A block, collection c1, collection c2, addon B block
        val order = listOf("a1_k1", "a1_k2", "collection_c1", "collection_c2", "b1_k1", "b1_k2")
        val addonNameOf = mapOf(
            "a1_k1" to "A", "a1_k2" to "A", "b1_k1" to "B", "b1_k2" to "B"
        )::get

        // c2 moving up: skips c1, lands at the START of A's block.
        assertEquals(0, HomeCatalogOrdering.collectionBlockJumpIndex(order, 3, -1, addonNameOf))
        // c1 moving down: lands at the END of B's block.
        assertEquals(5, HomeCatalogOrdering.collectionBlockJumpIndex(order, 2, 1, addonNameOf))
        // c1 moving up: A's block is the previous block, start = 0.
        assertEquals(0, HomeCatalogOrdering.collectionBlockJumpIndex(order, 2, -1, addonNameOf))
    }

    @Test
    fun `collection block jump returns null at the list edges`() {
        val order = listOf("collection_c1", "a1_k1")
        val addonNameOf = mapOf("a1_k1" to "A")::get

        assertNull(HomeCatalogOrdering.collectionBlockJumpIndex(order, 0, -1, addonNameOf))

        val orderBottom = listOf("a1_k1", "collection_c1")
        assertNull(HomeCatalogOrdering.collectionBlockJumpIndex(orderBottom, 1, 1, mapOf("a1_k1" to "A")::get))
    }

    @Test
    fun `buildAddonCatalogEntries filters search-only catalogs and dedupes keys`() {
        val addon = testAddon(
            id = "a1",
            baseUrl = "https://a1.example",
            catalogs = listOf(
                CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top Movies"),
                CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top Movies duplicate"),
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "search",
                    name = "Search",
                    extra = listOf(CatalogExtra(name = "search", isRequired = true))
                )
            )
        )

        val entries = HomeCatalogOrdering.buildAddonCatalogEntries(listOf(addon))

        assertEquals(listOf("a1_movie_top"), entries.map { it.key })
        assertEquals("Top Movies", entries.single().catalogName)
        assertEquals(
            "https://a1.example_movie_top_Top Movies",
            entries.single().legacyDisableKey
        )
    }

    @Test
    fun `buildCollectionEntries labels single and multiple folders`() {
        val collections = listOf(
            Collection(id = "c1", title = "Solo", folders = listOf(folder("f1"))),
            Collection(id = "c2", title = "Duo", folders = listOf(folder("f1"), folder("f2")))
        )

        val entries = HomeCatalogOrdering.buildCollectionEntries(collections)

        assertEquals(listOf("collection_c1", "collection_c2"), entries.map { it.key })
        assertEquals(listOf("1 folder", "2 folders"), entries.map { it.addonName })
        assertEquals("collection", entries.single { it.key == "collection_c1" }.typeLabel)
    }

    @Test
    fun `isDisabled matches current and legacy disable keys`() {
        val entry = HomeCatalogOrdering.CatalogEntry(
            key = "a1_movie_top",
            disableKey = "a1_movie_top",
            legacyDisableKey = "https://a1.example_movie_top_Top Movies",
            catalogName = "Top Movies",
            addonName = "A",
            typeLabel = "movie"
        )

        assertTrue(HomeCatalogOrdering.isDisabled(entry, setOf("a1_movie_top")))
        assertTrue(HomeCatalogOrdering.isDisabled(entry, setOf("https://a1.example_movie_top_Top Movies")))
        assertFalse(HomeCatalogOrdering.isDisabled(entry, emptySet()))
    }

    private fun entry(key: String, addonName: String = "Addon"): HomeCatalogOrdering.CatalogEntry =
        HomeCatalogOrdering.CatalogEntry(
            key = key,
            disableKey = key,
            legacyDisableKey = null,
            catalogName = key,
            addonName = addonName,
            typeLabel = "movie"
        )

    private fun folder(id: String) = CollectionFolder(id = id, title = id)

    private fun testAddon(
        id: String,
        baseUrl: String,
        catalogs: List<CatalogDescriptor>
    ): Addon {
        return Addon(
            id = id,
            name = id,
            displayName = id,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
        )
    }
}
