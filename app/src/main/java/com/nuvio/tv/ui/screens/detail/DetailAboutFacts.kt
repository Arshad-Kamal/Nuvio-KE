package com.nuvio.tv.ui.screens.detail

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.ACTING_DEPARTMENT
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaReleaseDateCountry
import java.time.LocalDate
import java.util.Locale

// The About sheet's facts (sandbox data-additions-marquee stage 4), kept out of the composable.

private val YEAR = Regex("\\d{4}")

/** A lead credit: the TMDB department its person page opens on and its About chip's breadcrumb section. */
private class LeadCredit(val department: String, @param:StringRes val sectionRes: Int)

// Keyed by the lowercased role, in the sheet's crew order. Creator is TMDB's own department for creators.
private val LEAD_CREDITS = linkedMapOf(
    "creator" to LeadCredit("Creator", R.string.detail_credit_created_by),
    "director" to LeadCredit("Directing", R.string.detail_credit_directed_by),
    "writer" to LeadCredit("Writing", R.string.detail_credit_written_by)
)

private fun leadCredit(role: String?): LeadCredit? = role?.trim()?.lowercase(Locale.ROOT)?.let(LEAD_CREDITS::get)

/** Creator, director or writer: the lead credits listed apart from the cast. */
internal fun isLeadCreditRole(role: String?): Boolean = leadCredit(role) != null

/** The TMDB department a credit opens the person page on (Spec 29 Q2): the lead credit's, else Acting. */
internal fun personPageDepartment(role: String?): String = leadCredit(role)?.department ?: ACTING_DEPARTMENT

/** The person page breadcrumb's section for an About chip: "Directed by" etc. for lead credits, else Cast. */
@StringRes
internal fun personChipSectionRes(role: String?): Int = leadCredit(role)?.sectionRes ?: R.string.detail_section_cast

/** One dated release (TMDB release type 1 Premiere ... 6 TV, else null) in the sheet's country. */
@Immutable
internal data class AboutRelease(val type: Int?, val date: LocalDate, val note: String?)

/**
 * The country whose releases the sheet lists: the first of [regions] (the TMDB language's
 * preferred regions, Spec 26 R2) with a dated release, else the first country with one.
 */
internal fun selectReleaseCountry(
    countries: List<MetaReleaseDateCountry>,
    regions: List<String>
): MetaReleaseDateCountry? {
    val dated = countries.filter { country -> country.releaseDates.any { releaseLocalDate(it.releaseDate) != null } }
    val byRegion = dated.associateBy { it.countryCode.uppercase(Locale.US) }
    return regions.firstNotNullOfOrNull { byRegion[it.uppercase(Locale.US)] } ?: dated.firstOrNull()
}

/** [country]'s dated releases, earliest first, one per type and date. */
internal fun aboutReleases(country: MetaReleaseDateCountry?): List<AboutRelease> =
    country?.releaseDates.orEmpty()
        .mapNotNull { release ->
            releaseLocalDate(release.releaseDate)?.let { date ->
                AboutRelease(release.type, date, release.note?.trim()?.takeIf { it.isNotBlank() })
            }
        }
        .distinctBy { it.type to it.date }
        .sortedBy { it.date }

/** The calendar date as written ("2012-09-26T00:00:00.000Z" -> 26 Sep), never shifted by the viewer's zone. */
internal fun releaseLocalDate(raw: String?): LocalDate? =
    raw?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** A person chip: [tmdbId] makes it open the person page (Spec 26 R4); [role] is the credit or character. */
@Immutable
internal data class AboutPerson(val name: String, val role: String?, val tmdbId: Int?)

@Immutable
internal data class AboutPeople(val crew: List<AboutPerson>, val cast: List<AboutPerson>)

/**
 * The sheet's people: creators, directors, writers first (up to [maxCrew]), then the cast with
 * their characters (up to [maxCast]). From the meta's credits when it has them (TMDB, ids
 * included), else the addon's name lists (Cinemeta: names only).
 */
internal fun aboutPeople(meta: Meta, maxCrew: Int = 4, maxCast: Int = 8): AboutPeople {
    val members = meta.castMembers.ifEmpty {
        meta.director.map { MetaCastMember(name = it, character = "Director") } +
            meta.writer.map { MetaCastMember(name = it, character = "Writer") } +
            meta.cast.map { MetaCastMember(name = it) }
    }.filter { it.name.isNotBlank() }
    val people = members.map { AboutPerson(it.name.trim(), it.character?.trim()?.takeIf(String::isNotBlank), it.tmdbId) }
    val crew = LEAD_CREDITS.keys
        .flatMap { role -> people.filter { it.role.equals(role, ignoreCase = true) } }
        .distinctBy { it.name.lowercase() to it.role?.lowercase() }
        .take(maxCrew)
    val cast = people
        .filterNot { isLeadCreditRole(it.role) }
        .distinctBy { it.tmdbId?.toString() ?: it.name.lowercase() }
        .take(maxCast)
    return AboutPeople(crew, cast)
}

/** "2006–2023" (or one year) across the collection's titles; null when none carries a year. */
internal fun collectionYears(items: List<MetaPreview>): String? {
    val years = items.mapNotNull { item -> item.releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() } }
    val first = years.minOrNull() ?: return null
    val last = years.max()
    return if (first == last) "$first" else "$first\u2013$last"
}
