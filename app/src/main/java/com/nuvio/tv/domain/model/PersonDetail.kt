package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class PersonDetail(
    val tmdbId: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePhoto: String?,
    /** TMDB `known_for_department` ("Acting", "Directing", ...). */
    val knownFor: String?,
    val alsoKnownAs: List<String>,
    /** One entry per TMDB department with credits, most credits first (ties by name). */
    val departments: List<PersonDepartment>,
    /** Distinct titles the person is credited on that TMDB has no poster for (dropped from [departments]). */
    val creditsWithoutPoster: Int
)

/** A TMDB department ("Acting" for cast credits) and its credits, newest first, undated last. */
@Immutable
data class PersonDepartment(
    val name: String,
    val credits: List<PersonCredit>
)

/**
 * One title in one department. [roles] are the cast characters (Acting) or crew jobs, in TMDB order,
 * blanks dropped; [episodeCount] is the largest TV episode count among the merged credits.
 */
@Immutable
data class PersonCredit(
    val preview: MetaPreview,
    val roles: List<String>,
    val episodeCount: Int?,
    /** TMDB release or first-air date (yyyy-MM-dd), or null. */
    val date: String?
)
