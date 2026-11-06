package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the short-form manifest extras gap: AddonMapper only parses long-form
 * `extra` into CatalogDescriptor.extra, so supportsExtra had to consult
 * extraSupported/extraRequired directly or short-form catalogs never paginated
 * and never appeared in search.
 */
class CatalogDescriptorExtensionsTest {

    @Test
    fun `supportsExtra reads long-form extra list`() {
        val catalog = descriptor(
            extra = listOf(CatalogExtra(name = "skip"), CatalogExtra(name = "genre"))
        )
        assertTrue(catalog.supportsExtra("skip"))
        assertTrue(catalog.supportsExtra("genre"))
        assertFalse(catalog.supportsExtra("search"))
    }

    @Test
    fun `supportsExtra reads short-form extraSupported`() {
        val catalog = descriptor(extraSupported = listOf("search", "skip"))
        assertTrue(catalog.supportsExtra("skip"))
        assertTrue(catalog.supportsExtra("SKIP"))
        assertFalse(catalog.supportsExtra("genre"))
    }

    @Test
    fun `supportsExtra reads short-form extraRequired`() {
        val catalog = descriptor(extraRequired = listOf("search"))
        assertTrue(catalog.supportsExtra("search"))
        assertFalse(catalog.supportsExtra("skip"))
    }

    @Test
    fun `supportsExtra is false when nothing declares the extra`() {
        assertFalse(descriptor().supportsExtra("skip"))
    }

    @Test
    fun `skipStep prefers declared pageSize`() {
        assertEquals(20, descriptor(pageSize = 20).skipStep())
    }

    @Test
    fun `skipStep infers the step from skip options`() {
        val catalog = descriptor(
            extra = listOf(
                CatalogExtra(name = "skip", options = listOf("0", "50", "100", "150"))
            )
        )
        assertEquals(50, catalog.skipStep())
    }

    @Test
    fun `skipStep ignores non-numeric and negative skip options`() {
        val catalog = descriptor(
            extra = listOf(
                CatalogExtra(name = "skip", options = listOf("all", "-10", "0", "25"))
            )
        )
        assertEquals(25, catalog.skipStep())
    }

    @Test
    fun `skipStep falls back to the default with a single skip option`() {
        val catalog = descriptor(
            extra = listOf(CatalogExtra(name = "skip", options = listOf("0")))
        )
        assertEquals(100, catalog.skipStep())
    }

    @Test
    fun `skipStep falls back to the default with no pageSize and no options`() {
        assertEquals(100, descriptor().skipStep())
    }

    @Test
    fun `pageSize wins over inferable skip options`() {
        val catalog = descriptor(
            pageSize = 20,
            extra = listOf(
                CatalogExtra(name = "skip", options = listOf("0", "50", "100"))
            )
        )
        assertEquals(20, catalog.skipStep())
    }

    @Test
    fun `isSearchable rejects a short-form required non-search extra`() {
        val catalog = descriptor(extraSupported = listOf("search"), extraRequired = listOf("genre"))
        assertFalse(catalog.isSearchable())
        assertTrue(descriptor(extraRequired = listOf("search")).isSearchable())
    }

    @Test
    fun `required extra without options or default is unsatisfiable in either form`() {
        assertTrue(
            descriptor(extra = listOf(CatalogExtra(name = "calendarvideosids", isRequired = true)))
                .hasUnsatisfiableRequiredExtra()
        )
        assertTrue(descriptor(extraRequired = listOf("calendarVideosIds")).hasUnsatisfiableRequiredExtra())
        // Search-only catalogs cannot be browsed either.
        assertTrue(descriptor(extraRequired = listOf("search")).hasUnsatisfiableRequiredExtra())
        assertTrue(
            descriptor(extra = listOf(CatalogExtra(name = "search", isRequired = true)))
                .hasUnsatisfiableRequiredExtra()
        )
    }

    @Test
    fun `skip options and default make required extras satisfiable`() {
        val catalog = descriptor(
            extra = listOf(
                CatalogExtra(name = "skip", isRequired = true),
                CatalogExtra(name = "genre", isRequired = true, options = listOf("Action")),
                CatalogExtra(name = "year", isRequired = true, defaultValue = "2024"),
                CatalogExtra(name = "optional")
            ),
            // A short-form name the long form declares with options is satisfiable.
            extraRequired = listOf("skip", "Genre")
        )
        assertFalse(catalog.hasUnsatisfiableRequiredExtra())
    }

    @Test
    fun `required defaults prefer default then first option and skip optional and app-supplied extras`() {
        val catalog = descriptor(
            extra = listOf(
                CatalogExtra(name = "genre", isRequired = true, options = listOf("Action", "Drama"), defaultValue = "Drama"),
                CatalogExtra(name = "year", isRequired = true, options = listOf("2024", "2023")),
                CatalogExtra(name = "rating", options = listOf("G")),
                CatalogExtra(name = "skip", isRequired = true, options = listOf("0", "50")),
                CatalogExtra(name = "search", isRequired = true)
            )
        )
        assertEquals(mapOf("genre" to "Drama", "year" to "2024"), catalog.requiredExtraDefaults())
    }

    @Test
    fun `required defaults never send a None value`() {
        val catalog = descriptor(
            extra = listOf(
                CatalogExtra(name = "genre", isRequired = true, options = listOf("None", "Action")),
                CatalogExtra(name = "year", isRequired = true, options = listOf("2024"), defaultValue = "none")
            )
        )
        assertEquals(emptyMap<String, String>(), catalog.requiredExtraDefaults())
        assertTrue(isNoFilterExtraValue(" NONE "))
        assertFalse(isNoFilterExtraValue("Nonexistent"))
    }

    @Test
    fun `mixed-form required extra is required by both predicates`() {
        // Short-form extraRequired names genre; the long-form entry carries the
        // options but isRequired=false. Both predicates must treat it as required.
        val satisfiable = descriptor(
            extra = listOf(CatalogExtra(name = "genre", options = listOf("Action", "Drama"))),
            extraRequired = listOf("Genre")
        )
        assertFalse(satisfiable.hasUnsatisfiableRequiredExtra())
        assertEquals(mapOf("genre" to "Action"), satisfiable.requiredExtraDefaults())

        val unsatisfiable = descriptor(
            extra = listOf(CatalogExtra(name = "genre")),
            extraRequired = listOf("genre")
        )
        assertTrue(unsatisfiable.hasUnsatisfiableRequiredExtra())
        assertEquals(emptyMap<String, String>(), unsatisfiable.requiredExtraDefaults())
    }

    @Test
    fun `sync home list keeps catalogs that browse surfaces hide`() {
        // Sync contract: shouldShowOnHome (with requiresSearchExtra) defines the
        // cloud-sync list and stays unchanged; browse hides these catalogs via
        // hasUnsatisfiableRequiredExtra.
        val calendar = descriptor(
            extra = listOf(CatalogExtra(name = "calendarvideosids", isRequired = true))
        )
        val shortFormSearch = descriptor(extraRequired = listOf("search"))
        listOf(calendar, shortFormSearch).forEach { catalog ->
            assertTrue(catalog.shouldShowOnHome())
            assertTrue(catalog.hasUnsatisfiableRequiredExtra())
        }
    }

    private fun descriptor(
        extra: List<CatalogExtra> = emptyList(),
        pageSize: Int? = null,
        extraSupported: List<String> = emptyList(),
        extraRequired: List<String> = emptyList()
    ): CatalogDescriptor = CatalogDescriptor(
        type = ContentType.MOVIE,
        id = "top",
        name = "Top",
        extra = extra,
        pageSize = pageSize,
        extraSupported = extraSupported,
        extraRequired = extraRequired
    )
}
