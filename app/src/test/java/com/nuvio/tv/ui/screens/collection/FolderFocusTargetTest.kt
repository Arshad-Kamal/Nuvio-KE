package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderFocusTargetTest {

    private val a = AddonCatalogCollectionSource(addonId = "a", type = "movie", catalogId = "top")
    private val b = AddonCatalogCollectionSource(addonId = "b", type = "series", catalogId = "top")

    @Test
    fun `a source target resolves to the source now at that index`() {
        assertEquals(collectionSourceKey(b), resolveFolderFocusTarget(sourceFocusTarget(1), listOf(a, b)))
    }

    @Test
    fun `past the end it falls back to the last source, then to Add catalog`() {
        assertEquals(collectionSourceKey(a), resolveFolderFocusTarget(sourceFocusTarget(1), listOf(a)))
        assertEquals(FOLDER_ADD_CATALOG_TARGET, resolveFolderFocusTarget(sourceFocusTarget(0), emptyList()))
    }

    @Test
    fun `other targets are themselves`() {
        assertEquals(FOLDER_ADD_CATALOG_TARGET, resolveFolderFocusTarget(FOLDER_ADD_CATALOG_TARGET, listOf(a)))
    }
}
