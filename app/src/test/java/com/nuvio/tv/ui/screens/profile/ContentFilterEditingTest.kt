package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.data.local.ContentFilterRead
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.OwnPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Spec 117 S7a: the editor's Content filters rules (P9, P10, M1 stages 1, 2, 6; Spec 122 V5). */
class ContentFilterEditingTest {

    private val names = mapOf("war" to "War and military", "horror" to "Horror", "lgbtq" to "LGBTQ+ themes", "anime" to "Anime", "eclair" to "Éclair")
    private val packs = names.keys.map { FilterPack(it) }
    private val listed = listedPacks(packs, names::getValue, { "$it desc" }, Locale.ENGLISH)

    @Test
    fun `access is editable exactly where Live TV is, the primary included (D9)`() {
        assertEquals(ContentFilterAccess.EDIT, contentFilterAccess(liveTvEditable = true))
        assertEquals(ContentFilterAccess.NOTE, contentFilterAccess(liveTvEditable = false))
    }

    @Test
    fun `packs are listed A to Z by localized name, accents in collation order (P10)`() {
        assertEquals(listOf("Anime", "Éclair", "Horror", "LGBTQ+ themes", "War and military"), listed.map { it.name })
        assertEquals("horror desc", listed.first { it.pack.id == "horror" }.description)
    }

    @Test
    fun `enabled names list own packs first, then built-ins in page order, ignoring unknown and unprefixed ids`() {
        val settings = ContentFilterSettings(
            // "gone": no such pack; "own:x": an own id with no pack; "bad": an own pack without the prefix.
            enabledPacks = setOf("war", "own:1", "horror", "gone", "bad", "own:x"),
            myWords = listOf("wwe"),
            ownPacks = listOf(OwnPack("own:1", "Grandma's list"), OwnPack("bad", "No prefix"), OwnPack("own:2", "Off pack"))
        )
        assertEquals(listOf("Grandma's list", "Horror", "War and military"), enabledPackNames(settings, listed))
        assertEquals(emptyList<String>(), enabledPackNames(ContentFilterSettings(myWords = listOf("wwe")), listed))
    }

    @Test
    fun `the row helper names two and counts the rest`() {
        val more = { n: Int -> "+$n" }
        assertEquals("", enabledNamesSummary(emptyList(), more))
        assertEquals("A", enabledNamesSummary(listOf("A"), more))
        assertEquals("A, B", enabledNamesSummary(listOf("A", "B"), more))
        assertEquals("A, B, +3", enabledNamesSummary(listOf("A", "B", "C", "D", "E"), more))
    }

    @Test
    fun `a pack's pane lists every genre, flag, word once, keyword name and allowlisted title`() {
        val pack = FilterPack(
            id = "x",
            words = listOf(PackWord("sex", tagNameToo = false), PackWord("porn"), PackWord("porn", textToo = false)),
            genreNames = setOf("Horror"),
            keywordIds = setOf(1, 2),
            keywordNames = listOf("sexual content", "nudity"),
            adult = true,
            japaneseAnimation = true,
            allowTitleNames = listOf("The Lion King (1994)")
        )
        assertEquals(
            PackContents(
                genres = listOf("Horror", "Adult", "Japanese"),
                words = listOf("sex", "porn"),
                tags = listOf("sexual content", "nudity"),
                neverHidden = listOf("The Lion King (1994)")
            ),
            packContents(pack, adultLabel = "Adult", japaneseAnimationLabel = "Japanese")
        )
    }

    @Test
    fun `a save writes changed filters only where editable, and a read failure opens as null`() {
        val settings = ContentFilterSettings(enabledPacks = setOf("horror"))
        assertEquals(settings, contentFilterEditFor(ContentFilterAccess.EDIT, changed = true, settings))
        assertNull(contentFilterEditFor(ContentFilterAccess.EDIT, changed = false, settings))
        assertNull(contentFilterEditFor(ContentFilterAccess.NOTE, changed = true, settings))
        assertEquals(settings, ContentFilterRead.Stored(settings).storedOrNull())
        assertNull(ContentFilterRead.Unreadable.storedOrNull())
    }

    @Test
    fun `a profile's filters count as loaded only once its own read is in the map`() {
        assertEquals(false, contentFiltersLoaded(2, null))
        assertEquals(false, contentFiltersLoaded(2, mapOf(1 to ContentFilterRead.Unreadable)))
        assertEquals(true, contentFiltersLoaded(1, mapOf(1 to ContentFilterRead.Unreadable)))
    }

    @Test
    fun `Edit waits for the profile's read and the built-in packs, a failed pack file included`() {
        val reads = mapOf(1 to ContentFilterRead.Stored(ContentFilterSettings()))
        assertEquals(false, contentFiltersReady(1, reads, packs = null))
        assertEquals(false, contentFiltersReady(2, reads, packs = packs))
        assertEquals(true, contentFiltersReady(1, reads, packs = packs))
        // A failed pack file loads as an empty list: Edit opens, the row then reads as unreadable.
        assertEquals(true, contentFiltersReady(1, reads, packs = emptyList()))
    }

    @Test
    fun `filters are shown and changeable only when read and the pack file gave packs`() {
        assertEquals(true, contentFiltersReadable(ContentFilterSettings(), listed))
        assertEquals(false, contentFiltersReadable(null, listed))
        assertEquals(false, contentFiltersReadable(ContentFilterSettings(), emptyList()))
    }

    @Test
    fun `the read-only note names five and counts the rest`() {
        val names = (1..7).map { "P$it" }
        assertEquals("P1, P2, P3, P4, P5, +2", enabledNamesSummary(names, { "+$it" }, shown = NOTE_NAMES_SHOWN))
    }

    // Spec 122 D3: the primary's "Preview filters of" picker.

    private fun profile(id: Int) = UserProfile(id = id, name = "P$id", avatarColorHex = "#1E88E5")
    private val profiles = listOf(profile(2), profile(1), profile(3), profile(4), profile(5))

    @Test
    fun `preview choices are Off, the primary's own, then each other profile with what it has on`() {
        val reads = mapOf(
            1 to ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("war"))),
            2 to ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("war", "horror", "gone"))),
            3 to ContentFilterRead.Stored(ContentFilterSettings(myWords = listOf("wwe"))),
            4 to ContentFilterRead.Unreadable,
            5 to ContentFilterRead.Stored(ContentFilterSettings())
        )
        assertEquals(
            listOf(
                PreviewChoice(null, null),
                PreviewChoice(profile(1), null),
                PreviewChoice(profile(2), PreviewSummary.On(2)),
                PreviewChoice(profile(3), PreviewSummary.MyWordsOnly),
                PreviewChoice(profile(4), PreviewSummary.Unreadable),
                PreviewChoice(profile(5), PreviewSummary.NothingOn)
            ),
            previewChoices(profiles, reads, listed)
        )
        assertNull("still loading: no status (D35)", previewChoices(profiles, null, listed)[2].summary)
        assertNull("still loading: no status (D35)", previewChoices(profiles, reads - 2, listed)[2].summary)
    }

    @Test
    fun `the picked choice is the primary's stored id while a profile has it, else Off`() {
        fun primaryPreviewing(id: Int?) = mapOf(1 to ContentFilterRead.Stored(ContentFilterSettings(previewProfileId = id)))
        assertEquals(3, pickedPreview(primaryPreviewing(3), profiles))
        assertEquals(1, pickedPreview(primaryPreviewing(1), profiles))
        assertNull(pickedPreview(primaryPreviewing(9), profiles))
        assertNull(pickedPreview(primaryPreviewing(null), profiles))
        assertNull(pickedPreview(mapOf(1 to ContentFilterRead.Unreadable), profiles))
        assertNull(pickedPreview(null, profiles))
        assertNull("another profile's stored id is never the pick", pickedPreview(mapOf(2 to ContentFilterRead.Stored(ContentFilterSettings(previewProfileId = 3))), profiles))
    }

    @Test
    fun `only the primary's own editable page gets the picker`() {
        assertTrue(previewPickerShown(profile(1), ContentFilterAccess.EDIT))
        assertFalse(previewPickerShown(profile(1), ContentFilterAccess.NOTE))
        assertFalse(previewPickerShown(profile(2), ContentFilterAccess.EDIT))
        assertFalse("a new profile", previewPickerShown(null, ContentFilterAccess.EDIT))
    }
}
