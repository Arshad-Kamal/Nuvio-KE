package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

/** prioritizedMetaCandidates: request order and per-resource type + prefix pairing (B8, L8-25). */
class MetaCandidatesTest {

    private fun addon(
        id: String,
        resources: List<AddonResource>,
        idPrefixes: List<String> = emptyList()
    ) = Addon(
        id = id,
        name = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        resources = resources,
        idPrefixes = idPrefixes
    )

    private fun meta(types: List<String>, prefixes: List<String>? = null) =
        AddonResource(name = "meta", types = types, idPrefixes = prefixes)

    private fun candidates(addons: List<Addon>, requested: String, inferred: String, id: String) =
        prioritizedMetaCandidates(addons, requested, inferred, id).map { (a, t) -> "${a.id}:$t" }

    @Test
    fun `type and id prefix come from the same meta resource`() {
        val split = addon(
            "split",
            listOf(meta(listOf("movie"), listOf("tt")), meta(listOf("series"), listOf("kitsu:")))
        )
        assertEquals(listOf("split:series"), candidates(listOf(split), "series", "series", "kitsu:1"))
        assertEquals(listOf("split:movie"), candidates(listOf(split), "movie", "movie", "tt1"))
        // Its series resource does not take tt ids, so another addon that does wins alone
        // (the first meta resource's "tt" used to make it a series candidate too).
        val cinemeta = addon("cinemeta", listOf(meta(listOf("movie", "series"), listOf("tt"))))
        assertEquals(listOf("cinemeta:series"), candidates(listOf(split, cinemeta), "series", "series", "tt1"))
        val scoped = split.copy(idPrefixes = listOf("tt", "kitsu:"))
        assertEquals(emptyList<String>(), candidates(listOf(scoped), "series", "series", "tt1"))
    }

    @Test
    fun `a resource without prefixes uses the addon's`() {
        val a = addon("a", listOf(meta(listOf("series"))), idPrefixes = listOf("tmdb:"))
        assertEquals(listOf("a:series"), candidates(listOf(a), "series", "series", "TMDB:1"))
        assertEquals(emptyList<String>(), candidates(listOf(a), "series", "series", "tt1"))
    }

    @Test
    fun `requested type ranks before inferred, in installed order`() {
        val tv = addon("tv", listOf(meta(listOf("tv"))))
        val series = addon("series", listOf(meta(listOf("series"))))
        val both = addon("both", listOf(meta(listOf("tv", "series"))))
        assertEquals(
            listOf("tv:tv", "both:tv", "series:series", "both:series"),
            candidates(listOf(tv, series, both), "tv", "series", "tt1")
        )
    }

    @Test
    fun `fallback only when nothing matched the id, inferred type for the top meta addon`() {
        val prefixed = addon("p", listOf(meta(listOf("series"), listOf("kitsu:"))), idPrefixes = listOf("kitsu:"))
        val loose = addon("loose", listOf(meta(listOf("series"), listOf("kitsu:"))))
        // "loose" declares no addon-level prefixes: requested "tv" unsupported, so it
        // enters as the top meta addon under the inferred "series".
        assertEquals(listOf("loose:series"), candidates(listOf(prefixed, loose), "tv", "series", "tt1"))
        // Requested type supported: the first fallback branch takes every such addon.
        val loose2 = addon("loose2", listOf(meta(listOf("series"), listOf("kitsu:"))))
        assertEquals(
            listOf("loose:series", "loose2:series"),
            candidates(listOf(prefixed, loose, loose2), "series", "series", "tt1")
        )
    }
}
