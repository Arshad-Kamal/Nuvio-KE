package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave C S4 C4-1: the Home catalog load signature and its skip gate (no pins existed). */
class HomeCatalogLoadGateTest {

    private fun catalog(id: String, showInHome: Boolean = true, extra: List<CatalogExtra> = emptyList()) =
        CatalogDescriptor(type = ContentType.MOVIE, id = id, name = "Cat $id", showInHome = showInHome, extra = extra)

    private fun addon(id: String, catalogs: List<CatalogDescriptor>) = Addon(
        id = id,
        name = "Addon $id",
        version = "1.0.0",
        description = "desc",
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = catalogs,
        types = listOf(ContentType.MOVIE),
        resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null))
    )

    private val a = addon("a", listOf(catalog("top"), catalog("new")))
    private val b = addon("b", listOf(catalog("pop")))
    private val base = homeCatalogLoadSignature(listOf(a, b), setOf("k1", "k2"))

    @Test
    fun `signature ignores addon, catalog and disabled-key order`() {
        val reordered = homeCatalogLoadSignature(
            listOf(b, a.copy(catalogs = a.catalogs.reversed())),
            linkedSetOf("k2", "k1")
        )
        assertEquals(base, reordered)
    }

    @Test
    fun `signature changes with catalogs, extras, showInHome and disabled keys`() {
        val added = homeCatalogLoadSignature(listOf(a, b.copy(catalogs = b.catalogs + catalog("x"))), setOf("k1", "k2"))
        val removed = homeCatalogLoadSignature(listOf(a, b.copy(catalogs = emptyList())), setOf("k1", "k2"))
        val extra = homeCatalogLoadSignature(
            listOf(a, b.copy(catalogs = listOf(catalog("pop", extra = listOf(CatalogExtra(name = "genre")))))),
            setOf("k1", "k2")
        )
        val hidden = homeCatalogLoadSignature(listOf(a, b.copy(catalogs = listOf(catalog("pop", showInHome = false)))), setOf("k1", "k2"))
        val disabled = homeCatalogLoadSignature(listOf(a, b), setOf("k1"))
        listOf(added, removed, extra, hidden, disabled).forEach { assertNotEquals(base, it) }
    }

    @Test
    fun `signature ignores display-only addon fields (rename relabels, does not reload)`() {
        val renamed = b.copy(displayName = "Renamed", logo = "https://logo", description = "other")
        assertEquals(base, homeCatalogLoadSignature(listOf(a, renamed), setOf("k1", "k2")))
    }

    @Test
    fun `gate truth table`() {
        fun skip(force: Boolean = false, active: String? = "s", loads: Boolean = false, rows: Boolean = false) =
            shouldSkipCatalogLoad(force, "s", active, loads) { rows }
        assertFalse("force overrides", skip(force = true, loads = true, rows = true))
        assertFalse("new signature loads", skip(active = "other", loads = true, rows = true))
        assertFalse("first load (no active signature) loads", skip(active = null, loads = true))
        assertTrue("same signature, loads active", skip(loads = true))
        assertTrue("same signature, rows present", skip(rows = true))
        assertFalse("same signature, nothing loaded: retry", skip())
    }

    @Test
    fun `gate reads rows only when the other clauses do not decide`() {
        var reads = 0
        shouldSkipCatalogLoad(false, "s", "s", hasActiveLoads = true) { reads++; true }
        shouldSkipCatalogLoad(true, "s", "s", hasActiveLoads = false) { reads++; true }
        assertEquals(0, reads)
        shouldSkipCatalogLoad(false, "s", "s", hasActiveLoads = false) { reads++; true }
        assertEquals(1, reads)
    }

    private fun row(addonId: String, addonName: String, baseUrl: String = "https://$addonId.example") = CatalogRow(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = baseUrl,
        catalogId = "top",
        catalogName = "Top",
        type = ContentType.MOVIE,
        items = emptyList()
    )

    @Test
    fun `a renamed addon relabels its rows (C4-R2)`() {
        val names = addonDisplayNames(listOf(a, b.copy(displayName = "Renamed")))

        assertEquals("Renamed", row("b", "Addon b").withAddonNameFrom(names).addonName)
    }

    @Test
    fun `relabel keeps the same row when the name is unchanged or the addon is unknown`() {
        val names = addonDisplayNames(listOf(a, b))
        val unchanged = row("a", a.displayName)
        val unknown = row("gone", "Gone")
        // Same id at another URL (a second install of the addon) is a different addon.
        val otherInstall = row("b", "Addon b", baseUrl = "https://b-second.example")

        assertSame(unchanged, unchanged.withAddonNameFrom(names))
        assertSame(unknown, unknown.withAddonNameFrom(names))
        assertSame(otherInstall, otherInstall.withAddonNameFrom(addonDisplayNames(listOf(b.copy(displayName = "X")))))
    }

    private fun placeholder(addonId: String, addonName: String) = HomeViewModel.PlaceholderDescriptor(
        catalogKey = "${addonId}_movie_top",
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = "https://$addonId.example",
        catalogId = "top",
        catalogName = "Top",
        apiType = "movie",
        displayTitle = "Top - Movie"
    )

    @Test
    fun `relabel rewrites rows and placeholders in place and reports a change`() {
        val rows = linkedMapOf("a_movie_top" to row("a", a.displayName), "b_movie_top" to row("b", "Addon b"))
        val placeholders = mutableListOf(placeholder("a", a.displayName), placeholder("b", "Addon b"))
        val names = addonDisplayNames(listOf(a, b.copy(displayName = "Renamed")))

        assertTrue(relabelAddonNames(rows, placeholders, names))
        assertEquals(listOf(a.displayName, "Renamed"), rows.values.map { it.addonName })
        assertEquals(listOf(a.displayName, "Renamed"), placeholders.map { it.addonName })

        assertFalse("nothing left to change", relabelAddonNames(rows, placeholders, names))
    }
}
