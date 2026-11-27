package com.nuvio.tv.domain.contentfilter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Spec 117 P2: the built-in packs, read from `assets/content_filter_packs.json` (one readable file,
 * published with the app, P10). Word lists by match scope:
 * - `words`: title/description text and inside TMDB keyword names;
 * - `textOnlyWords`: text only (`tagNameToo = false`);
 * - `tagOnlyWords`: inside keyword names only (`textToo = false`);
 * - `wholeTagWords`: only a keyword whose whole name is the word.
 */
object BuiltInPacks {
    const val ASSET_NAME = "content_filter_packs.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** Throws IllegalArgumentException (or SerializationException) on a malformed file. */
    fun parse(text: String): List<FilterPack> =
        json.decodeFromString(PackFile.serializer(), text).packs.map(PackEntry::toFilterPack)
}

@Serializable
internal data class PackFile(
    @SerialName("version") val version: Int = 1,
    @SerialName("packs") val packs: List<PackEntry> = emptyList()
)

@Serializable
internal data class PackEntry(
    @SerialName("id") val id: String,
    @SerialName("genreIds") val genreIds: List<Int> = emptyList(),
    @SerialName("genreNames") val genreNames: List<String> = emptyList(),
    @SerialName("adult") val adult: Boolean = false,
    @SerialName("japaneseAnimation") val japaneseAnimation: Boolean = false,
    @SerialName("keywords") val keywords: List<PackKeyword> = emptyList(),
    @SerialName("words") val words: List<String> = emptyList(),
    @SerialName("textOnlyWords") val textOnlyWords: List<String> = emptyList(),
    @SerialName("tagOnlyWords") val tagOnlyWords: List<String> = emptyList(),
    @SerialName("wholeTagWords") val wholeTagWords: List<String> = emptyList(),
    @SerialName("allowTitles") val allowTitles: List<AllowedTitle> = emptyList()
) {
    fun toFilterPack(): FilterPack {
        allowTitles.forEach { require(it.kind == "movie" || it.kind == "tv") { "pack $id: allowTitles kind must be movie or tv" } }
        return toPack()
    }

    private fun toPack(): FilterPack = FilterPack(
        id = id,
        words = words.map { PackWord(it) } +
            textOnlyWords.map { PackWord(it, tagNameToo = false) } +
            tagOnlyWords.map { PackWord(it, textToo = false) } +
            wholeTagWords.map { PackWord(it, textToo = false, tagNameToo = false) },
        genreIds = genreIds.toSet(),
        genreNames = genreNames.toSet(),
        keywordIds = keywords.mapTo(LinkedHashSet()) { it.id },
        keywordNames = keywords.map { it.name },
        adult = adult,
        japaneseAnimation = japaneseAnimation,
        allowTitles = allowTitles.mapTo(HashSet()) { "${it.kind}:${it.tmdbId}" },
        allowTitleNames = allowTitles.map { it.name }
    )
}

/** A TMDB keyword: matched by [id]; [name] is what the pack's page shows (S7a). */
@Serializable
internal data class PackKeyword(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String
)

/** D30: a title a pack never hides; [name] is what the pack's page shows. */
@Serializable
internal data class AllowedTitle(
    @SerialName("kind") val kind: String,
    @SerialName("tmdbId") val tmdbId: Int,
    @SerialName("name") val name: String
)
