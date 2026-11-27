package com.nuvio.tv.ui.components.marquee

import com.nuvio.tv.domain.contentfilter.BuiltInPacks
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.OwnPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Spec 122 T2: the filter band's label (V4, N4) and the built-in pack names. */
class FilterHitBandTest {

    private val names = mapOf("war" to "War and military", "violence" to "Graphic violence and gore")

    private fun label(vararg hits: String, own: Map<String, String> = emptyMap()) =
        filterBadgeLabel(hits.toList(), names::get, own, myWords = "My words", unknown = "Filtered")

    @Test
    fun `no hits, no band`() {
        assertNull(label())
    }

    @Test
    fun `the first hitting pack names the band and the rest count as more`() {
        assertEquals(FilterBadgeLabel("Graphic violence and gore", 1), label("violence", "war"))
        assertEquals(FilterBadgeLabel("War and military", 0), label("war"))
    }

    @Test
    fun `My words and own packs are named`() {
        assertEquals(FilterBadgeLabel("My words", 0), label(ContentFilterSettings.MY_WORDS_ID))
        val ownId = "${OwnPack.ID_PREFIX}abc"
        assertEquals(FilterBadgeLabel("Cartoons", 2), label(ownId, "war", "violence", own = mapOf(ownId to "Cartoons")))
    }

    @Test
    fun `an id nothing names reads Filtered`() {
        assertEquals(FilterBadgeLabel("Filtered", 0), label("${OwnPack.ID_PREFIX}gone"))
        assertEquals(FilterBadgeLabel("Filtered", 1), label("no_such_pack", "war"))
    }

    @Test
    fun `every shipped pack has its own name string, and no other id has one`() {
        val packs = BuiltInPacks.parse(File("src/main/assets/${BuiltInPacks.ASSET_NAME}").readText())
        val names = packs.map { assertNotNull(it.id, builtInPackNameRes(it.id)); builtInPackNameRes(it.id) }
        assertEquals(packs.size, names.toSet().size)
        assertNull(builtInPackNameRes(ContentFilterSettings.MY_WORDS_ID))
        assertNull(builtInPackNameRes("${OwnPack.ID_PREFIX}war"))
        val descriptions = packs.map { assertNotNull(it.id, builtInPackDescriptionRes(it.id)); builtInPackDescriptionRes(it.id) }
        // Each pack its own description, none of them a name string.
        assertEquals(packs.size * 2, (descriptions + names).toSet().size)
        assertNull(builtInPackDescriptionRes("${OwnPack.ID_PREFIX}war"))
    }

    @Test
    fun `a pack is named by one rule, for the band and Detail's block`() {
        val own = mapOf("own:1" to "Grandma's list")
        val builtIn = { id: String -> if (id == "p5") "Horror" else null }
        assertEquals("Horror", filterPackName("p5", builtIn, own, "My words", "Filtered"))
        assertEquals("My words", filterPackName(ContentFilterSettings.MY_WORDS_ID, builtIn, own, "My words", "Filtered"))
        assertEquals("Grandma's list", filterPackName("own:1", builtIn, own, "My words", "Filtered"))
        assertEquals("Filtered", filterPackName("own:9", builtIn, own, "My words", "Filtered"))
    }
}
