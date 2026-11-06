package com.nuvio.tv.ui.screens.cast

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.tmdb.ACTING_DEPARTMENT
import com.nuvio.tv.domain.model.PersonCredit
import com.nuvio.tv.domain.model.PersonDepartment
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.catalogCarouselItem
import com.nuvio.tv.ui.screens.home.isSeriesType
import java.time.LocalDate
import java.time.Period
import java.util.Locale

// The person page's display model (Spec 29c, sandbox u9-cast-marquee stages 1, 3-6, 12), built in the
// VM so composition only lays it out.

/** What the person page shows: the profile facts and one tab per department, the route's first. */
@Immutable
data class PersonPage(
    val person: PersonDetail,
    /** Known-for department and the credit count ("Acting · 36 credits"); null when neither exists. */
    val eyebrow: String?,
    /** "Born 14 Mar 1983 (age 43)" or "Born 2 Jun 1931 – died 11 Jan 2019 (aged 87)"; null without dates. */
    val lifeLine: String?,
    val placeOfBirth: String?,
    /** The first two other names, " · " joined; null when TMDB has none. */
    val alsoKnownAs: String?,
    val biography: String?,
    /** The Read more sheet's facts (stage 2), in display order. */
    val facts: List<PersonFact>,
    /** The profile photo; null without one. */
    val photo: String?,
    /** The portrait's stand-in without a photo ("HB"). */
    val initials: String,
    /** The image blurred under the first frame: the photo, else the first credit's backdrop. */
    val fieldImage: String?,
    val tabs: List<PersonTab>,
    /** Card shape = Landscape: the tabs' items carry landscape art. */
    val landscape: Boolean
)

/** A Read more fact row: the screen labels [kind]; [value] is display text. */
@Immutable
data class PersonFact(val kind: PersonFactKind, val value: String)

enum class PersonFactKind { KNOWN_FOR, BORN, DIED, PLACE_OF_BIRTH, ALSO_KNOWN_AS }

@Immutable
data class PersonTab(val department: String, val cards: List<PersonCreditCard>)

/**
 * One credit card: [role] is the accent caption ("as Celia", "Director, Writer"), [detail] the muted
 * "4 eps · 2015", [heroEyebrow] the results hero's "as Tilda · Series". [item]'s hero meta carries the
 * type and episodes in its runtime slot ("2015 · Series · 4 eps", stage 3).
 */
@Immutable
data class PersonCreditCard(
    val credit: PersonCredit,
    val item: ModernCarouselItem,
    val role: String?,
    val detail: String?,
    val heroEyebrow: String
)

/** Localised copy for the page's lines; the VM backs it with string resources. */
internal interface PersonCopy {
    fun credits(count: Int): String
    fun born(date: String, age: Int): String
    fun bornDied(born: String, died: String, aged: Int): String
    fun died(date: String): String
    fun dateWithAge(date: String, age: Int): String
    fun dateWithAged(date: String, aged: Int): String
    fun asRole(roles: String): String
    fun episodes(count: Int): String
    fun type(series: Boolean): String
}

private const val SEPARATOR = " \u00B7 "
private const val ALSO_KNOWN_AS_SHOWN = 2
private val YEAR = Regex("\\d{4}")
private val WHITESPACE = Regex("\\s+")

// A self appearance reads as is ("Herself", "Self - Host"), not "as Herself": TMDB writes the
// self word first, then any qualifier.
private val SELF_ROLES = setOf("self", "himself", "herself", "themselves", "themself")
private val ROLE_WORD_END = Regex("[\\s(\\-]")

internal fun personPage(
    person: PersonDetail,
    department: String,
    landscape: Boolean,
    today: LocalDate,
    formatDate: (LocalDate) -> String,
    copy: PersonCopy
): PersonPage {
    val departments = orderedDepartments(person.departments, department)
    val photo = person.profilePhoto?.takeIf { it.isNotBlank() }
    return PersonPage(
        person = person,
        eyebrow = personEyebrow(person, copy),
        lifeLine = personLifeLine(person.birthday, person.deathday, today, formatDate, copy),
        placeOfBirth = personPlaceOfBirth(person),
        alsoKnownAs = person.alsoKnownAs.take(ALSO_KNOWN_AS_SHOWN).joinToString(SEPARATOR).takeIf { it.isNotBlank() },
        biography = person.biography?.trim()?.takeIf { it.isNotBlank() },
        facts = personFacts(person, today, formatDate, copy),
        photo = photo,
        initials = personInitials(person.name),
        fieldImage = photo
            ?: departments.firstNotNullOfOrNull { dept ->
                dept.credits.firstNotNullOfOrNull { it.preview.background?.takeIf(String::isNotBlank) }
            },
        tabs = departments.map { dept ->
            PersonTab(dept.name, dept.credits.map { personCreditCard(it, dept.name, landscape, copy) })
        },
        landscape = landscape
    )
}

/** The first letters of the first and last names, upper case ("Henk Brouwer" -> "HB"); "?" without letters. */
internal fun personInitials(name: String): String {
    val firstLetters = name.split(WHITESPACE).mapNotNull { word -> word.firstOrNull { it.isLetter() } }
    val letters = listOfNotNull(firstLetters.firstOrNull(), firstLetters.drop(1).lastOrNull())
    return letters.joinToString("").uppercase().ifEmpty { "?" }
}

/** [departments] (most credits first) with [preferred] moved to the front when it has credits (Spec 29 Q2). */
internal fun orderedDepartments(departments: List<PersonDepartment>, preferred: String): List<PersonDepartment> {
    val first = departments.firstOrNull { it.name.equals(preferred.trim(), ignoreCase = true) } ?: return departments
    return listOf(first) + (departments - first)
}

/** Known-for department (else the largest department) · the credit total across departments. */
internal fun personEyebrow(person: PersonDetail, copy: PersonCopy): String? {
    val department = person.knownFor?.trim()?.takeIf { it.isNotBlank() } ?: person.departments.firstOrNull()?.name
    val total = person.departments.sumOf { it.credits.size }
    return listOfNotNull(department, total.takeIf { it > 0 }?.let(copy::credits))
        .joinToString(SEPARATOR)
        .takeIf { it.isNotBlank() }
}

/** Birth date with the age today, or birth and death with the age at death; TMDB dates are yyyy-MM-dd. */
internal fun personLifeLine(
    birthday: String?,
    deathday: String?,
    today: LocalDate,
    formatDate: (LocalDate) -> String,
    copy: PersonCopy
): String? {
    val born = tmdbDate(birthday)
    val died = tmdbDate(deathday)
    return when {
        born != null && died != null ->
            copy.bornDied(formatDate(born), formatDate(died), Period.between(born, died).years)
        born != null -> copy.born(formatDate(born), Period.between(born, today).years)
        died != null -> copy.died(formatDate(died))
        else -> null
    }
}

private fun personPlaceOfBirth(person: PersonDetail): String? = person.placeOfBirth?.trim()?.takeIf { it.isNotBlank() }

/**
 * The Read more facts (stage 2): known for (the page eyebrow, then each department's count when there
 * are several), born (with the age today unless died), died (with the age at death when the birth is
 * known), place of birth, every other name.
 */
internal fun personFacts(
    person: PersonDetail,
    today: LocalDate,
    formatDate: (LocalDate) -> String,
    copy: PersonCopy
): List<PersonFact> {
    val born = tmdbDate(person.birthday)
    val died = tmdbDate(person.deathday)
    val departmentCounts = person.departments.takeIf { it.size > 1 }
        ?.joinToString(", ", prefix = " (", postfix = ")") { "${it.name} ${it.credits.size}" }
        .orEmpty()
    return listOfNotNull(
        personEyebrow(person, copy)?.let { PersonFact(PersonFactKind.KNOWN_FOR, it + departmentCounts) },
        born?.let {
            val date = formatDate(it)
            PersonFact(PersonFactKind.BORN, if (died == null) copy.dateWithAge(date, Period.between(it, today).years) else date)
        },
        died?.let {
            val date = formatDate(it)
            PersonFact(PersonFactKind.DIED, if (born != null) copy.dateWithAged(date, Period.between(born, it).years) else date)
        },
        personPlaceOfBirth(person)?.let { PersonFact(PersonFactKind.PLACE_OF_BIRTH, it) },
        person.alsoKnownAs.joinToString(SEPARATOR).takeIf { it.isNotBlank() }
            ?.let { PersonFact(PersonFactKind.ALSO_KNOWN_AS, it) }
    )
}

private fun tmdbDate(raw: String?): LocalDate? =
    raw?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun personCreditCard(
    credit: PersonCredit,
    department: String,
    landscape: Boolean,
    copy: PersonCopy
): PersonCreditCard {
    val preview = credit.preview
    val catalogItem = catalogCarouselItem(
        item = preview,
        // The type is in the row key: movie and TV ids share one number space (Q9), and the card
        // key and focus key both derive from the row key + id.
        rowKey = "person_${department}_${preview.apiType}",
        addonBaseUrl = "",
        useLandscapePosters = landscape,
        occurrence = 0
    )
    val role = creditRole(credit, department, copy)
    val type = copy.type(isSeriesType(preview.apiType))
    val item = catalogItem.copy(heroPreview = catalogItem.heroPreview.copy(runtimeText = creditTypeFacts(credit, type, copy)))
    return PersonCreditCard(
        credit = credit,
        item = item,
        role = role,
        detail = creditDetail(credit, copy),
        heroEyebrow = listOfNotNull(role, type).joinToString(SEPARATOR)
    )
}

/** The type, then the episodes for a series ("Series · 4 eps"). */
internal fun creditTypeFacts(credit: PersonCredit, type: String, copy: PersonCopy): String =
    listOfNotNull(type, credit.episodeCount?.takeIf { it > 0 }?.let(copy::episodes)).joinToString(SEPARATOR)

/** "as Celia" for an acting credit (self roles as is), else the jobs ("Director, Writer"); null without roles. */
internal fun creditRole(credit: PersonCredit, department: String, copy: PersonCopy): String? {
    val roles = credit.roles.joinToString(", ").takeIf { it.isNotBlank() } ?: return null
    val allSelf = credit.roles.all { role ->
        role.trim().split(ROLE_WORD_END, limit = 2).first().lowercase(Locale.ROOT) in SELF_ROLES
    }
    return if (department == ACTING_DEPARTMENT && !allSelf) copy.asRole(roles) else roles
}

/** Episodes (series) · year; null when neither is known. */
internal fun creditDetail(credit: PersonCredit, copy: PersonCopy): String? {
    val year = (credit.date ?: credit.preview.releaseInfo)?.let { YEAR.find(it)?.value }
    return listOfNotNull(credit.episodeCount?.takeIf { it > 0 }?.let(copy::episodes), year)
        .joinToString(SEPARATOR)
        .takeIf { it.isNotBlank() }
}
