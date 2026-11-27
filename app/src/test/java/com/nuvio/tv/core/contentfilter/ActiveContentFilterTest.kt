package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.data.local.ContentFilterRead
import com.nuvio.tv.data.local.ProfileContentFilterStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.FilterSubject
import com.nuvio.tv.domain.contentfilter.OwnPack
import com.nuvio.tv.domain.contentfilter.OwnTag
import com.nuvio.tv.domain.contentfilter.TitleTags
import com.nuvio.tv.domain.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.assertTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Spec 117 S2: which packs the active profile's filter compiles, and its fail-closed reads. */
class ActiveContentFilterTest {
    private val profileDataStore = mockk<ProfileDataStore> { every { activeProfileId } returns flowOf(2) }
    private val stored = MutableStateFlow<ContentFilterRead>(ContentFilterRead.Stored(ContentFilterSettings()))
    private val store = mockk<ProfileContentFilterStore> { every { settingsFor(2) } returns stored }
    private var packFileReads = 0

    private fun TestScope.active() = ActiveContentFilter(
        profileDataStore,
        store,
        { packFileReads++; File(PACK_FILE).readText() },
        StandardTestDispatcher(testScheduler)
    )

    private fun ids(filter: com.nuvio.tv.domain.contentfilter.ContentFilter?) = filter?.packs?.map { it.id }

    private suspend fun ActiveContentFilter.filter() = current()?.filter

    @Test
    fun `nothing on reads null and never opens the pack file`() = runTest {
        assertNull(active().current())
        assertEquals(0, packFileReads)
    }

    @Test
    fun `enabled built-in packs compile alone and hide their words`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror", "gambling")))
        val filter = active().filter()
        assertEquals(listOf("horror", "gambling"), ids(filter))
        assertEquals("gambling", filter!!.firstHit(FilterSubject(name = "Casino Royale")))
        assertNull(filter.firstHit(FilterSubject(name = "Paddington")))
    }

    @Test
    fun `unknown pack ids are ignored`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror", "retired_pack")))
        assertEquals(listOf("horror"), ids(active().filter()))
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("retired_pack")))
        assertNull(active().current())
    }

    @Test
    fun `my words apply alone without a switch`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(myWords = listOf("dragon ball", " ")))
        val filter = active().filter()
        assertEquals(listOf(ContentFilterSettings.MY_WORDS_ID), ids(filter))
        assertEquals(ContentFilterSettings.MY_WORDS_ID, filter!!.firstHit(FilterSubject(name = "Dragon Ball Z")))
        assertEquals(0, packFileReads)
    }

    @Test
    fun `my words that cannot match read null`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(myWords = listOf(" ", "!!!", "-")))
        assertNull(active().current())
    }

    @Test
    fun `an enabled own id with no pack and an own pack that cannot match are ignored`() = runTest {
        val empty = OwnPack("own:2", "Empty", words = listOf("!!!"))
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("own:1", "own:2"), ownPacks = listOf(empty)))
        assertNull(active().current())
    }

    @Test
    fun `an own pack's genres hide by TMDB id and by addon genre name`() = runTest {
        val own = OwnPack("own:1", "No westerns", genreIds = listOf(37), genreNames = listOf("Western"))
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("own:1"), ownPacks = listOf(own)))
        val filter = active().filter()!!
        assertEquals("own:1", filter.firstHit(FilterSubject(name = "Unforgiven", genreIds = setOf(37))))
        assertEquals("own:1", filter.firstHit(FilterSubject(name = "Unforgiven", genreNames = listOf("western"))))
        assertNull(filter.firstHit(FilterSubject(name = "Unforgiven", genreIds = setOf(18), genreNames = listOf("Drama"))))
    }

    @Test
    fun `own packs apply only when switched on and carry their tags`() = runTest {
        val own = OwnPack("own:1", "Grandma's list", words = listOf("kiss"), tags = listOf(OwnTag(1299, "monster")))
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(ownPacks = listOf(own)))
        assertNull(active().current())
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("own:1"), ownPacks = listOf(own)))
        val filter = active().filter()
        assertEquals(listOf("own:1"), ids(filter))
        val tagged = FilterSubject(name = "Plain", tags = TitleTags(emptySet(), setOf(1299), listOf("monster"), null, false))
        assertEquals("own:1", filter!!.firstHit(tagged))
    }

    @Test
    fun `an own pack without the id prefix is ignored`() = runTest {
        val own = OwnPack("horror", "Shadow", words = listOf("kiss"))
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror"), ownPacks = listOf(own)))
        assertEquals(listOf("horror"), ids(active().filter()))
    }

    @Test
    fun `unreadable settings turn every built-in pack on`() = runTest {
        stored.value = ContentFilterRead.Unreadable
        val active = active()
        val result = active.current()!!
        assertEquals(active.builtInPacks().map { it.id }, ids(result.filter))
        assertFalse(result.preview)
        assertEquals(emptyMap<String, String>(), result.ownPackNames)
    }

    @Test
    fun `an unreadable active id turns every built-in pack on`() = runTest {
        every { profileDataStore.activeProfileId } returns flow { throw IOException("disk") }
        val active = active()
        assertEquals(active.builtInPacks().map { it.id }, ids(active.filter()))
        verify(exactly = 0) { store.settingsFor(any()) }
    }

    @Test
    fun `the compiled filter is reused for equal settings and rebuilt on change`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        val active = active()
        val first = active.filter()
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        assertSame(first, active.filter())
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror", "anime")))
        assertNotSame(first, active.filter())
        assertEquals(1, packFileReads)
    }

    @Test
    fun `the active profile is never previewed and its own packs are named`() = runTest {
        val own = OwnPack("own:1", "Grandma's list", words = listOf("kiss"))
        stored.value = ContentFilterRead.Stored(
            ContentFilterSettings(enabledPacks = setOf("horror", "own:1"), ownPacks = listOf(own, OwnPack("own:2", "Off pack"), OwnPack("horror", "Unprefixed")))
        )
        val active = active().current()!!
        assertFalse(active.preview)
        assertEquals(mapOf("own:1" to "Grandma's list", "own:2" to "Off pack"), active.ownPackNames)
    }

    @Test
    fun `a cached filter never masks a later unreadable read`() = runTest {
        val active = active()
        assertNull(active.current())
        stored.value = ContentFilterRead.Unreadable
        assertEquals(active.builtInPacks().map { it.id }, ids(active.filter()))
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        assertEquals(listOf("horror"), ids(active.filter()))
    }

    @Test
    fun `a profile switch reads the new profile's settings`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        every { store.settingsFor(3) } returns flowOf(ContentFilterRead.Stored(ContentFilterSettings()))
        val active = active()
        assertEquals(listOf("horror"), ids(active.filter()))
        every { profileDataStore.activeProfileId } returns flowOf(3)
        assertNull(active.current())
    }

    // Spec 122 T1b: the primary's preview rule.

    private val primary = MutableStateFlow<ContentFilterRead>(ContentFilterRead.Stored(ContentFilterSettings()))
    private val kid = MutableStateFlow<ContentFilterRead>(ContentFilterRead.Stored(ContentFilterSettings()))

    private fun primaryPreviewing(id: Int?, enabled: Set<String> = emptySet()) {
        every { profileDataStore.activeProfileId } returns flowOf(1)
        every { profileDataStore.profilesList } returns flowOf(listOf(profile(1), profile(2), profile(3)))
        every { store.settingsFor(1) } returns primary
        every { store.settingsFor(3) } returns kid
        primary.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = enabled, previewProfileId = id))
    }

    private val kidSettings = ContentFilterSettings(
        enabledPacks = setOf("horror", "own:1"),
        myWords = listOf("wwe"),
        ownPacks = listOf(OwnPack("own:1", "Grandma's list", words = listOf("kiss")))
    )

    @Test
    fun `the primary previews another profile's whole setup`() = runTest {
        primaryPreviewing(3, enabled = setOf("gambling"))
        kid.value = ContentFilterRead.Stored(kidSettings)
        val result = active().current()!!
        assertTrue(result.preview)
        assertEquals(listOf("horror", ContentFilterSettings.MY_WORDS_ID, "own:1"), ids(result.filter))
        assertEquals(mapOf("own:1" to "Grandma's list"), result.ownPackNames)
    }

    @Test
    fun `the previewed profile itself never previews, even with the same settings cached (N1)`() = runTest {
        primaryPreviewing(3)
        kid.value = ContentFilterRead.Stored(kidSettings)
        val active = active()
        assertTrue(active.current()!!.preview)
        every { profileDataStore.activeProfileId } returns flowOf(3)
        val asKid = active.current()!!
        assertFalse(asKid.preview)
        assertEquals(listOf("horror", ContentFilterSettings.MY_WORDS_ID, "own:1"), ids(asKid.filter))
    }

    @Test
    fun `a preview id in a non-primary profile's own settings is ignored`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror"), previewProfileId = 3))
        val result = active().current()!!
        assertFalse(result.preview)
        assertEquals(listOf("horror"), ids(result.filter))
    }

    @Test
    fun `a preview id no profile has reads as preview off (N2)`() = runTest {
        primaryPreviewing(5, enabled = setOf("gambling"))
        val result = active().current()!!
        assertFalse(result.preview)
        assertEquals(listOf("gambling"), ids(result.filter))
        primary.value = ContentFilterRead.Stored(ContentFilterSettings(previewProfileId = 5))
        assertNull(active().current())
    }

    @Test
    fun `an unreadable profile list reads as preview off`() = runTest {
        primaryPreviewing(3)
        every { profileDataStore.profilesList } returns flow { throw IOException("disk") }
        assertNull(active().current())
    }

    @Test
    fun `previewing a profile that filters nothing is still a preview (R3)`() = runTest {
        primaryPreviewing(3, enabled = setOf("gambling"))
        val result = active().current()!!
        assertTrue(result.preview)
        assertNull(result.filter)
    }

    @Test
    fun `an unreadable previewed profile previews every built-in pack`() = runTest {
        primaryPreviewing(3)
        kid.value = ContentFilterRead.Unreadable
        val active = active()
        val result = active.current()!!
        assertTrue(result.preview)
        assertEquals(active.builtInPacks().map { it.id }, ids(result.filter))
    }

    @Test
    fun `a previewed store being cleared reads as preview off (R2)`() = runTest {
        primaryPreviewing(3, enabled = setOf("gambling"))
        every { store.settingsFor(3) } returns flow { throw CancellationException("store closed") }
        val result = active().current()!!
        assertFalse(result.preview)
        assertEquals(listOf("gambling"), ids(result.filter))
    }

    @Test
    fun `a cancelled caller is not turned into preview off`() = runTest {
        primaryPreviewing(3)
        every { store.settingsFor(3) } returns flow { currentCoroutineContext().cancel(); throw CancellationException("caller") }
        val active = active()
        var returned = false
        launch { active.current(); returned = true }.join()
        assertFalse(returned) // ensureActive() rethrows instead of reading as preview off
    }

    @Test
    fun `two cache slots keep the primary's and the profile's filters across a switch (R4)`() = runTest {
        primaryPreviewing(null, enabled = setOf("gambling"))
        kid.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        val active = active()
        val asPrimary = active.filter()
        every { profileDataStore.activeProfileId } returns flowOf(3)
        val asKid = active.filter()
        every { profileDataStore.activeProfileId } returns flowOf(1)
        assertSame(asPrimary, active.filter())
        every { profileDataStore.activeProfileId } returns flowOf(3)
        assertSame(asKid, active.filter())
    }

    @Test
    fun `the primary may preview its own filters`() = runTest {
        primaryPreviewing(1, enabled = setOf("gambling"))
        val result = active().current()!!
        assertTrue(result.preview)
        assertEquals(listOf("gambling"), ids(result.filter))
    }

    @Test
    fun `editing the previewed profile recompiles its filter`() = runTest {
        primaryPreviewing(3)
        kid.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        val active = active()
        assertEquals(listOf("horror"), ids(active.filter()))
        kid.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("anime")))
        assertEquals(listOf("anime"), ids(active.filter()))
    }

    // Spec 117 P5b / 122 V2 N3: what Home redraws on.

    private fun TestScope.changeCount(active: ActiveContentFilter): () -> Int {
        var count = 0
        backgroundScope.launch { active.settingsChanges().collect { count++ } }
        runCurrent()
        return { runCurrent(); count }
    }

    @Test
    fun `settings changes follow the active profile's edits`() = runTest {
        val count = changeCount(active())
        assertEquals(1, count())
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        assertEquals(2, count())
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        assertEquals(2, count())
    }

    @Test
    fun `settings changes follow the previewed profile, re-subscribed when the previewed id changes`() = runTest {
        primaryPreviewing(3)
        val other = MutableStateFlow<ContentFilterRead>(ContentFilterRead.Stored(ContentFilterSettings()))
        every { store.settingsFor(2) } returns other
        val count = changeCount(active())
        assertEquals(1, count())
        kid.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("anime")))
        assertEquals(2, count())
        primary.value = ContentFilterRead.Stored(ContentFilterSettings(previewProfileId = 2))
        assertEquals(3, count())
        kid.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        assertEquals("the old previewed profile is no longer followed", 3, count())
        other.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        assertEquals(4, count())
    }

    @Test
    fun `settings changes follow a profile switch and drop the old profile`() = runTest {
        val activeId = MutableStateFlow(2)
        every { profileDataStore.activeProfileId } returns activeId
        every { store.settingsFor(3) } returns kid
        val count = changeCount(active())
        assertEquals(1, count())
        activeId.value = 3
        assertEquals(2, count())
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        assertEquals("the old profile is no longer followed", 2, count())
        kid.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("anime")))
        assertEquals(3, count())
    }

    @Test
    fun `a non-primary profile's preview id is never followed`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(previewProfileId = 3))
        every { store.settingsFor(3) } returns kid
        val count = changeCount(active())
        kid.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("anime")))
        assertEquals(1, count())
    }

    @Test
    fun `a previewed store being cleared does not end the changes`() = runTest {
        primaryPreviewing(3)
        every { store.settingsFor(3) } returns flow { throw CancellationException("store closed") }
        val count = changeCount(active())
        assertEquals("still one first value, which Home skips as the state it read", 1, count())
        primary.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("anime")))
        assertEquals(2, count())
        primary.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        assertEquals(3, count())
    }

    // Spec 122 D4, D5: what the top-bar chip and the card marks' names read.

    @Test
    fun `the previewed id is reported only while previewing`() = runTest {
        primaryPreviewing(3, enabled = setOf("gambling"))
        assertEquals(3, active().current()!!.previewProfileId)
        primaryPreviewing(1, enabled = setOf("gambling"))
        assertEquals(1, active().current()!!.previewProfileId)
        primaryPreviewing(5, enabled = setOf("gambling"))
        assertNull("an id no profile has is preview off", active().current()!!.previewProfileId)
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror"), previewProfileId = 3))
        every { profileDataStore.activeProfileId } returns flowOf(2)
        assertNull("a non-primary profile never previews", active().current()!!.previewProfileId)
    }

    @Test
    fun `states follow preview being turned on, the previewed profile's edits and preview off`() = runTest {
        primaryPreviewing(null)
        val states = mutableListOf<ActiveFilter?>()
        backgroundScope.launch { active().states().collect { states += it } }
        runCurrent()
        assertEquals(listOf<ActiveFilter?>(null), states)
        kid.value = ContentFilterRead.Stored(kidSettings)
        primary.value = ContentFilterRead.Stored(ContentFilterSettings(previewProfileId = 3))
        runCurrent()
        assertEquals(3, states.last()!!.previewProfileId)
        assertEquals(mapOf("own:1" to "Grandma's list"), states.last()!!.ownPackNames)
        kid.value = ContentFilterRead.Stored(kidSettings.copy(ownPacks = listOf(OwnPack("own:1", "Renamed"))))
        runCurrent()
        assertEquals(mapOf("own:1" to "Renamed"), states.last()!!.ownPackNames)
        primary.value = ContentFilterRead.Stored(ContentFilterSettings())
        runCurrent()
        assertNull(states.last())
    }

    @Test
    fun `a state read cancelled by the active store being cleared emits null and the states go on`() = runTest {
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror")))
        var reads = 0
        // The first call feeds settingsChanges; the second is current()'s read, cut off by a deletion.
        every { store.settingsFor(2) } answers {
            if (++reads == 2) flow { throw CancellationException("store closed") } else stored
        }
        val states = mutableListOf<ActiveFilter?>()
        backgroundScope.launch { active().states().collect { states += it } }
        runCurrent()
        assertEquals(listOf<ActiveFilter?>(null), states)
        stored.value = ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("anime")))
        runCurrent()
        assertEquals(listOf("anime"), ids(states.last()!!.filter))
    }

    private fun profile(id: Int) = UserProfile(id = id, name = "P$id", avatarColorHex = "#1E88E5")

    private companion object {
        const val PACK_FILE = "src/main/assets/content_filter_packs.json"
    }
}
