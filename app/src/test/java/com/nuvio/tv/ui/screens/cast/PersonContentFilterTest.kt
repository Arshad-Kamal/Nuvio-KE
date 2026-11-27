package com.nuvio.tv.ui.screens.cast

import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.data.repository.GateFixture
import com.nuvio.tv.data.repository.tmdbPreview
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.model.PersonCredit
import com.nuvio.tv.domain.model.PersonDepartment
import com.nuvio.tv.domain.model.PersonDetail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 117 S5 (P6): a person page's credits through the content filter's gate. */
class PersonContentFilterTest {
    private val fixture = GateFixture(listOf(FilterPack("p5", words = listOf(PackWord("zombie")))))

    private fun credit(id: Int, name: String) = PersonCredit(tmdbPreview(id, name), listOf("Role"), null, null)

    private fun person(vararg departments: PersonDepartment) = PersonDetail(
        tmdbId = 7, name = "Mira", biography = null, birthday = null, deathday = null, placeOfBirth = null,
        profilePhoto = null, knownFor = "Acting", alsoKnownAs = emptyList(), departments = departments.toList(),
        creditsWithoutPoster = 0
    )

    @Test
    fun `hidden credits leave, emptied departments drop, the rest re-sort by credits left`() = runTest {
        val detail = person(
            PersonDepartment("Acting", listOf(credit(1, "Zombie One"), credit(2, "Zombie Two"), credit(3, "Spring"))),
            PersonDepartment("Writing", listOf(credit(4, "Paddington"), credit(5, "Frozen"))),
            PersonDepartment("Directing", listOf(credit(1, "Zombie One")))
        )

        val shown = detail.filteredBy(fixture.gate())

        assertEquals(listOf("Writing", "Acting"), shown.departments.map { it.name })
        assertEquals(listOf("tmdb:3"), shown.departments[1].credits.map { it.preview.id })
        assertEquals(listOf("Role"), shown.departments[1].credits.single().roles)
    }

    @Test
    fun `one wait covers every department, each title once, in department order`() = runTest {
        listOf("tmdb:4", "tmdb:3", "tmdb:5").forEach { fixture.states[it] = TagState.Undecided }
        val detail = person(
            PersonDepartment("Writing", listOf(credit(4, "Paddington"), credit(3, "Spring"))),
            PersonDepartment("Acting", listOf(credit(3, "Spring"), credit(5, "Frozen")))
        )

        val shown = detail.filteredBy(fixture.gate())

        assertEquals(listOf(listOf("tmdb:4", "tmdb:3", "tmdb:5")), fixture.waits.map { it.first })
        assertEquals("still undecided after the wait: hidden (117/D10)", emptyList<PersonDepartment>(), shown.departments)
    }

    @Test
    fun `previewing, a hit stays with its packs marked`() = runTest {
        val preview = GateFixture(listOf(FilterPack("p5", words = listOf(PackWord("zombie")))), preview = true)
        val detail = person(PersonDepartment("Acting", listOf(credit(1, "Zombie One"))))

        val shown = detail.filteredBy(preview.gate())

        assertEquals(listOf("p5"), shown.departments.single().credits.single().preview.filterHits)
    }
}
