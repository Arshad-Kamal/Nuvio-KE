package com.nuvio.tv.domain.contentfilter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One profile's content filters (Spec 117 P1, D4, D5): stored on this TV only, never synced.
 * [enabledPacks] holds the ids of the switched-on built-in packs and own packs; an id this build
 * does not know is ignored. [myWords] always apply while non-empty (they have no switch, M1 stage 2).
 * [previewProfileId] (Spec 122 D3) is read only from the primary profile's settings: the profile whose
 * filters the primary previews (null: off; may be the primary itself).
 */
@Serializable
data class ContentFilterSettings(
    @SerialName("enabledPacks") val enabledPacks: Set<String> = emptySet(),
    @SerialName("myWords") val myWords: List<String> = emptyList(),
    @SerialName("ownPacks") val ownPacks: List<OwnPack> = emptyList(),
    @SerialName("previewProfileId") val previewProfileId: Int? = null
) {
    companion object {
        /** The pack id My words match under (what a hit reports). */
        const val MY_WORDS_ID = "my_words"
    }
}

/**
 * A pack the profile made (D4, M1 stage 4): whole words, genres, and TMDB keyword tags. Its [id]
 * starts with [ID_PREFIX], so it never collides with a built-in pack's id or [ContentFilterSettings.MY_WORDS_ID].
 */
@Serializable
data class OwnPack(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("words") val words: List<String> = emptyList(),
    @SerialName("genreIds") val genreIds: List<Int> = emptyList(),
    @SerialName("genreNames") val genreNames: List<String> = emptyList(),
    @SerialName("tags") val tags: List<OwnTag> = emptyList()
) {
    /** Whether [id] has the own-pack prefix; one without it is ignored everywhere (never named, never applied). */
    val hasOwnPackId: Boolean get() = id.startsWith(ID_PREFIX)

    fun toFilterPack(): FilterPack = FilterPack(
        id = id,
        words = words.filter { ContentFilter.tokenize(it).isNotEmpty() }.map { PackWord(it) },
        genreIds = genreIds.toSet(),
        genreNames = genreNames.toSet(),
        keywordIds = tags.mapTo(HashSet()) { it.id }
    )

    companion object {
        const val ID_PREFIX = "own:"
    }
}

/** A TMDB keyword picked from `search/keyword`: matched by [id], [name] shown on the pack's page. */
@Serializable
data class OwnTag(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String
)
