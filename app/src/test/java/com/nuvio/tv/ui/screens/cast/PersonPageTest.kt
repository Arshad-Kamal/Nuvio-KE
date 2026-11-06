package com.nuvio.tv.ui.screens.cast

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonCredit
import com.nuvio.tv.domain.model.PersonDepartment
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.home.ModernPayload
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Person page model (Spec 29c): tab order, eyebrow, life line, captions, card keys. */
class PersonPageTest {

    private val copy = object : PersonCopy {
        override fun credits(count: Int) = if (count == 1) "1 credit" else "$count credits"
        override fun born(date: String, age: Int) = "Born $date (age $age)"
        override fun bornDied(born: String, died: String, aged: Int) = "Born $born \u2013 died $died (aged $aged)"
        override fun died(date: String) = "Died $date"
        override fun dateWithAge(date: String, age: Int) = "$date (age $age)"
        override fun dateWithAged(date: String, aged: Int) = "$date (aged $aged)"
        override fun asRole(roles: String) = "as $roles"
        override fun episodes(count: Int) = if (count == 1) "1 ep" else "$count eps"
        override fun type(series: Boolean) = if (series) "Series" else "Movie"
    }
    private val today = LocalDate.of(2026, 10, 16)
    private val dates = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)::format

    @Test
    fun `the route's department opens first, the rest keep count order`() {
        val acting = department("Acting", 3)
        val writing = department("Writing", 2)
        val directing = department("Directing", 1)
        val all = listOf(acting, writing, directing)

        assertEquals(listOf(directing, acting, writing), orderedDepartments(all, " directing "))
        assertEquals(all, orderedDepartments(all, "Creator"))
        assertEquals(all, orderedDepartments(all, ""))
    }

    @Test
    fun `eyebrow is the known-for department and the credit total`() {
        val person = person(departments = listOf(department("Acting", 31), department("Writing", 3)), knownFor = "Acting")
        assertEquals("Acting \u00B7 34 credits", personEyebrow(person, copy))
        assertEquals("Writing \u00B7 1 credit", personEyebrow(person(listOf(department("Writing", 1)), knownFor = " "), copy))
        assertEquals("Acting", personEyebrow(person(emptyList(), knownFor = "Acting"), copy))
        assertNull(personEyebrow(person(emptyList(), knownFor = null), copy))
    }

    @Test
    fun `life line gives the age today, or the age at death`() {
        assertEquals("Born 14 Mar 1983 (age 43)", personLifeLine("1983-03-14", null, today, dates, copy))
        assertEquals("Born 17 Oct 1983 (age 42)", personLifeLine("1983-10-17", null, today, dates, copy))
        assertEquals(
            "Born 2 Jun 1931 \u2013 died 11 Jan 2019 (aged 87)",
            personLifeLine("1931-06-02", "2019-01-11", today, dates, copy)
        )
        assertEquals("Died 11 Jan 2019", personLifeLine("", "2019-01-11", today, dates, copy))
        assertNull(personLifeLine("1990", null, today, dates, copy))
        assertNull(personLifeLine(null, null, today, dates, copy))
    }

    @Test
    fun `acting roles read as the character, crew roles as the jobs, self as is`() {
        assertEquals("as Celia", creditRole(credit(roles = listOf("Celia")), "Acting", copy))
        assertEquals("as Tilda, Ellie", creditRole(credit(roles = listOf("Tilda", "Ellie")), "Acting", copy))
        assertEquals("Herself", creditRole(credit(roles = listOf("Herself")), "Acting", copy))
        assertEquals("Self - Host, Self", creditRole(credit(roles = listOf("Self - Host", "Self")), "Acting", copy))
        assertEquals("Himself (archive footage)", creditRole(credit(roles = listOf("Himself (archive footage)")), "Acting", copy))
        assertEquals("as Selfridge", creditRole(credit(roles = listOf("Selfridge")), "Acting", copy))
        assertEquals("Director, Writer", creditRole(credit(roles = listOf("Director", "Writer")), "Directing", copy))
        assertNull(creditRole(credit(roles = emptyList()), "Acting", copy))
    }

    @Test
    fun `detail is episodes then year`() {
        assertEquals("4 eps \u00B7 2015", creditDetail(credit(episodes = 4, date = "2015-09-01"), copy))
        assertEquals("2012", creditDetail(credit(date = "2012-09-26"), copy))
        assertEquals("2010", creditDetail(credit(date = null, releaseInfo = "2010"), copy))
        assertEquals("1 ep", creditDetail(credit(episodes = 1, date = null), copy))
        assertNull(creditDetail(credit(episodes = 0, date = null), copy))
    }

    @Test
    fun `a movie and a series sharing a TMDB number get distinct card and focus keys`() {
        val movie = personCreditCard(credit(id = "tmdb:5", type = ContentType.MOVIE), "Acting", landscape = false, copy)
        val series = personCreditCard(credit(id = "tmdb:5", type = ContentType.SERIES), "Acting", landscape = false, copy)

        assertNotEquals(movie.item.key, series.item.key)
        assertNotEquals(
            (movie.item.payload as ModernPayload.Catalog).focusKey,
            (series.item.payload as ModernPayload.Catalog).focusKey
        )
    }

    @Test
    fun `hero eyebrow is role and type, hero meta the type and episodes`() {
        val series = personCreditCard(
            credit(type = ContentType.SERIES, roles = listOf("Tilda"), episodes = 4), "Acting", landscape = false, copy
        )
        assertEquals("as Tilda \u00B7 Series", series.heroEyebrow)
        assertEquals("Series \u00B7 4 eps", series.item.heroPreview.runtimeText)

        val movie = personCreditCard(credit(roles = emptyList()), "Directing", landscape = false, copy)
        assertEquals("Movie", movie.heroEyebrow)
        assertEquals("Movie", movie.item.heroPreview.runtimeText)
    }

    @Test
    fun `page carries the first two other names and the card shape`() {
        val person = person(
            departments = listOf(department("Acting", 1), department("Directing", 1)),
            knownFor = "Acting",
            alsoKnownAs = listOf("Mira Halden-Vos", "Mira H.", "M. Halden")
        )
        val page = personPage(person, "Directing", landscape = true, today, dates, copy)

        assertEquals(listOf("Directing", "Acting"), page.tabs.map { it.department })
        assertEquals("Mira Halden-Vos \u00B7 Mira H.", page.alsoKnownAs)
        assertEquals("backdrop", page.tabs.first().cards.single().item.imageUrl)
        assertEquals("Zagreb, Croatia", page.placeOfBirth)
    }

    @Test
    fun `read more facts are known for with department counts, born, died, place and every other name`() {
        val living = person(
            departments = listOf(department("Acting", 31), department("Writing", 3)),
            knownFor = "Acting",
            alsoKnownAs = listOf("Mira Halden-Vos", "Mira H.", "M. Halden")
        )
        val page = personPage(living, "", landscape = false, today, dates, copy)
        assertEquals(
            listOf(
                PersonFact(PersonFactKind.KNOWN_FOR, "Acting \u00B7 34 credits (Acting 31, Writing 3)"),
                PersonFact(PersonFactKind.BORN, "14 Mar 1983 (age 43)"),
                PersonFact(PersonFactKind.PLACE_OF_BIRTH, "Zagreb, Croatia"),
                PersonFact(PersonFactKind.ALSO_KNOWN_AS, "Mira Halden-Vos \u00B7 Mira H. \u00B7 M. Halden")
            ),
            page.facts
        )

        val died = person(listOf(department("Directing", 9)), "Directing").copy(birthday = "1931-06-02", deathday = "2019-01-11")
        assertEquals(
            listOf(
                PersonFact(PersonFactKind.KNOWN_FOR, "Directing \u00B7 9 credits"),
                PersonFact(PersonFactKind.BORN, "2 Jun 1931"),
                PersonFact(PersonFactKind.DIED, "11 Jan 2019 (aged 87)"),
                PersonFact(PersonFactKind.PLACE_OF_BIRTH, "Zagreb, Croatia")
            ),
            personFacts(died, today, dates, copy)
        )

        val bare = person(emptyList(), null).copy(birthday = "1990", deathday = "2019-01-11", placeOfBirth = " ")
        assertEquals(
            listOf(PersonFact(PersonFactKind.DIED, "11 Jan 2019")),
            personFacts(bare, today, dates, copy)
        )
    }

    @Test
    fun `initials are the first and last names' first letters`() {
        assertEquals("HB", personInitials("Henk Brouwer"))
        assertEquals("JV", personInitials("  joris  van  Vos "))
        assertEquals("C", personInitials("Cher"))
        assertEquals("?", personInitials(" "))
    }

    @Test
    fun `the field image is the photo, else the first credit's backdrop`() {
        val departments = listOf(department("Acting", 1))
        val noPhoto = personPage(person(departments, "Acting"), "", landscape = false, today, dates, copy)
        assertEquals("backdrop", noPhoto.fieldImage)
        assertEquals(null, noPhoto.photo)
        assertEquals("MH", noPhoto.initials)
        assertEquals(null, noPhoto.biography)

        val withPhoto = person(departments, "Acting").copy(profilePhoto = "photo", biography = " Bio. ")
        val page = personPage(withPhoto, "", landscape = false, today, dates, copy)
        assertEquals("photo", page.fieldImage)
        assertEquals("photo", page.photo)
        assertEquals("Bio.", page.biography)
    }

    private fun department(name: String, count: Int) =
        PersonDepartment(name, List(count) { credit(id = "tmdb:${name.length * 100 + it}") })

    private fun person(
        departments: List<PersonDepartment>,
        knownFor: String?,
        alsoKnownAs: List<String> = emptyList()
    ) = PersonDetail(
        tmdbId = 1, name = "Mira Halden", biography = null, birthday = "1983-03-14", deathday = null,
        placeOfBirth = " Zagreb, Croatia ", profilePhoto = null, knownFor = knownFor, alsoKnownAs = alsoKnownAs,
        departments = departments, creditsWithoutPoster = 0
    )

    private fun credit(
        id: String = "tmdb:1",
        type: ContentType = ContentType.MOVIE,
        roles: List<String> = listOf("Celia"),
        episodes: Int? = null,
        date: String? = "2012-09-26",
        releaseInfo: String? = null
    ) = PersonCredit(
        preview = MetaPreview(
            id = id, type = type, name = "Title", poster = "poster", posterShape = PosterShape.POSTER,
            background = "backdrop", logo = null, description = null, releaseInfo = releaseInfo, imdbRating = null,
            genres = emptyList()
        ),
        roles = roles,
        episodeCount = episodes,
        date = date
    )
}
