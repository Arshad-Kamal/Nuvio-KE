package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 145 (F18): the Home first-paint gate's early release. */
class HomeGateTest {

    /** Every precondition met by default; the cases vary the catalog clause or one precondition. */
    private fun ready(
        homeCatalogsExpected: Boolean,
        collectionsLoaded: Boolean = true,
        hasCatalogContent: Boolean = false,
        addonsSeen: Boolean = true,
        isLoading: Boolean = false,
        initialCwResolved: Boolean = true,
        modernPresentationReady: Boolean = true,
    ) = homeGateReady(
        addonsSeen = addonsSeen,
        isLoading = isLoading,
        initialCwResolved = initialCwResolved,
        modernPresentationReady = modernPresentationReady,
        hasCatalogContent = hasCatalogContent,
        homeCatalogsExpected = homeCatalogsExpected,
        collectionsLoaded = collectionsLoaded,
    )

    @Test
    fun `no home catalogs expected (CW, Picked for you, collections only) releases once collections are loaded`() {
        // F18: M x A's Home; the old clause (catalogs || no addons) held this until the timeout.
        assertTrue(ready(homeCatalogsExpected = false))
    }

    @Test
    fun `no home catalogs expected holds the gate while the collections' first load is pending`() {
        // Red team r3 M1: the load's "no catalog addons" error would show before collections land.
        assertFalse(ready(homeCatalogsExpected = false, collectionsLoaded = false))
    }

    @Test
    fun `expected home catalogs hold the gate until a catalog row has items`() {
        // Also the cold-start gap: Home's rows not yet showing the queued catalogs' placeholders.
        assertFalse(ready(homeCatalogsExpected = true))
        assertTrue(ready(homeCatalogsExpected = true, hasCatalogContent = true))
        // Homes with catalogs never wait on collections.
        assertTrue(ready(homeCatalogsExpected = true, hasCatalogContent = true, collectionsLoaded = false))
    }

    @Test
    fun `each precondition holds the gate on both release branches`() {
        // Branch false: no catalogs expected, collections loaded. Branch true: a catalog row has items.
        for (catalogs in listOf(false, true)) {
            assertFalse(ready(homeCatalogsExpected = catalogs, hasCatalogContent = catalogs, addonsSeen = false))
            assertFalse(ready(homeCatalogsExpected = catalogs, hasCatalogContent = catalogs, isLoading = true))
            assertFalse(ready(homeCatalogsExpected = catalogs, hasCatalogContent = catalogs, initialCwResolved = false))
            assertFalse(
                ready(homeCatalogsExpected = catalogs, hasCatalogContent = catalogs, modernPresentationReady = false)
            )
        }
    }
}
