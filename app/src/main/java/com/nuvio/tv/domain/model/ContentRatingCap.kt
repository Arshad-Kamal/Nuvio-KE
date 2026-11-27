package com.nuvio.tv.domain.model

import java.util.Locale

/**
 * A profile's maximum age rating (Spec 64 D4): the US-board ladder, lowest first, so a cap
 * allows its own tier and every tier below it. [storageKey] is the persisted form; never
 * rename one (a renamed key reads back as junk, which fails closed to [ALL_AGES]).
 */
enum class ContentRatingCap(val storageKey: String, val ratings: Set<String>) {
    ALL_AGES("all_ages", setOf("TV-Y", "TV-G", "G")),
    OLDER_KIDS("older_kids", setOf("TV-Y7", "TV-Y7-FV", "PG", "TV-PG")),
    TEENS("teens", setOf("PG-13", "TV-14")),
    MATURE("mature", setOf("R", "TV-MA")),
    ADULTS_ONLY("adults_only", setOf("NC-17"));

    companion object {
        /** The cap a read failure or an unknown stored value fails closed to (Spec 64 D1). */
        val STRICTEST: ContentRatingCap = ALL_AGES

        /**
         * The tier of a US-board [rating] (trimmed, case-insensitive), or null when it is
         * unknown: absent, `NR`/unrated, or any non-US code (Spec 64 D1, D4; non-US boards are
         * read only through [effectiveTierOf], Spec 102).
         */
        fun tierOf(rating: String?): ContentRatingCap? {
            val code = usRatingCode(rating) ?: return null
            return entries.first { code in it.ratings }
        }

        /** [rating] as its US-board code (trimmed, uppercased), or null when [tierOf] would not know it. */
        fun usRatingCode(rating: String?): String? {
            val code = rating?.trim()?.uppercase(Locale.ROOT) ?: return null
            return code.takeIf { entries.any { tier -> code in tier.ratings } }
        }

        /**
         * The tier [isRatingAllowed] decides on (Spec 102 R1): the US [rating], else a known US code inside
         * [nonUsRatings], else [NonUsRatingBoards.tierOf] the non-US boards; null when none places it.
         */
        fun effectiveTierOf(rating: String?, nonUsRatings: Map<String, String>): ContentRatingCap? =
            usTierOf(rating, nonUsRatings) ?: NonUsRatingBoards.tierOf(nonUsRatings)

        /** R1 alone: the US [rating], else a known US code inside [nonUsRatings]; null when neither is known. */
        fun usTierOf(rating: String?, nonUsRatings: Map<String, String>): ContentRatingCap? =
            tierOf(rating) ?: NonUsRatingBoards.usTierOf(nonUsRatings)

        /** The cap stored as [key]; null for an absent or unknown key (the store decides what junk means). */
        fun fromStorageKey(key: String?): ContentRatingCap? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Whether a title rated [rating] may be shown under [cap]. No cap allows everything; under a
 * cap an unplaceable rating is hidden (fail closed, Spec 64 D1). [rating] must be a US-board value
 * (the addon's `app_extras.certification`): a bare code cannot say which board issued it, and
 * several non-US boards reuse US spellings ("PG", "G"). Non-US codes arrive only keyed by country
 * in [nonUsRatings] (`app_extras.certifications`), read when [rating] is unknown (Spec 102).
 */
fun isRatingAllowed(
    rating: String?,
    cap: ContentRatingCap?,
    nonUsRatings: Map<String, String> = emptyMap()
): Boolean {
    if (cap == null) return true
    val tier = ContentRatingCap.effectiveTierOf(rating, nonUsRatings) ?: return false
    return tier <= cap
}
