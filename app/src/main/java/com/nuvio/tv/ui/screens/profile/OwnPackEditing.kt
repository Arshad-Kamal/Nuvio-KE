package com.nuvio.tv.ui.screens.profile

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.OwnPack
import com.nuvio.tv.domain.contentfilter.OwnTag
import java.util.Locale

/** The longest word or phrase the keyboard takes for My words and own packs. */
internal const val FILTER_WORD_MAX = 60

/** The longest own-pack name. */
internal const val OWN_PACK_NAME_MAX = 30

/**
 * [input]'s words as the keyboard's Done adds them (Spec 117 P9, M1 stage 3: commas add several at
 * once): trimmed, inner spaces collapsed, each at most [FILTER_WORD_MAX]; a part with no letter or
 * digit (it would never match, P3) and one already in [existing] or earlier in [input], compared
 * case-insensitively as the matcher does, are dropped. [existing] first, then the new ones in order.
 */
internal fun withAddedWords(existing: List<String>, input: String): List<String> {
    val seen = existing.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    val added = input.split(',')
        .map { it.trim().replace(WHITESPACE, " ").take(FILTER_WORD_MAX).trim() }
        .filter { it.isNotEmpty() && ContentFilter.tokenize(it).isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
    return existing + added
}

private val WHITESPACE = Regex("\\s+")

/** [settings] with [input]'s words added to My words ([withAddedWords]). */
internal fun ContentFilterSettings.addMyWords(input: String): ContentFilterSettings = copy(myWords = withAddedWords(myWords, input))

/** [settings] without My word [word] (OK on its chip, M1 stage 3). */
internal fun ContentFilterSettings.removeMyWord(word: String): ContentFilterSettings = copy(myWords = myWords - word)

/**
 * [settings] with a new own pack named [name] (trimmed, at most [OWN_PACK_NAME_MAX]) and id
 * [OwnPack.ID_PREFIX] + [newId], listed after the profile's other own packs and switched on (stage 4b);
 * unchanged when the name is blank. The new pack's id comes back as the second value, null when none was made.
 */
internal fun ContentFilterSettings.withNewOwnPack(name: String, newId: String): Pair<ContentFilterSettings, String?> {
    val trimmed = cleanPackName(name)
    if (trimmed.isEmpty()) return this to null
    val id = OwnPack.ID_PREFIX + newId
    return copy(ownPacks = ownPacks + OwnPack(id = id, name = trimmed), enabledPacks = enabledPacks + id) to id
}

/** A pack name as stored: trimmed, at most [OWN_PACK_NAME_MAX]. */
private fun cleanPackName(name: String): String = name.trim().take(OWN_PACK_NAME_MAX).trim()

/** [settings] without own pack [id], its switch included (Delete pack, stage 4). */
internal fun ContentFilterSettings.deleteOwnPack(id: String): ContentFilterSettings =
    copy(ownPacks = ownPacks.filterNot { it.id == id }, enabledPacks = enabledPacks - id)

/** [settings] with own pack [id] changed by [change]; unchanged when no own pack has that id. */
internal fun ContentFilterSettings.updateOwnPack(id: String, change: (OwnPack) -> OwnPack): ContentFilterSettings =
    copy(ownPacks = ownPacks.map { if (it.id == id) change(it) else it })

/** [pack] renamed to [name] (trimmed, at most [OWN_PACK_NAME_MAX]); unchanged when blank (a pack keeps a name). */
internal fun OwnPack.renamed(name: String): OwnPack {
    val trimmed = cleanPackName(name)
    return if (trimmed.isEmpty()) this else copy(name = trimmed)
}

/** [pack] with [input]'s words added ([withAddedWords]). */
internal fun OwnPack.withWords(input: String): OwnPack = copy(words = withAddedWords(words, input))

/** [pack] without [word]. */
internal fun OwnPack.withoutWord(word: String): OwnPack = copy(words = words - word)

/** [pack] with [tag] added after its others; unchanged when it holds that keyword id already. */
internal fun OwnPack.withTag(tag: OwnTag): OwnPack = if (tags.any { it.id == tag.id }) this else copy(tags = tags + tag)

/** [pack] without the tag of keyword [id]. */
internal fun OwnPack.withoutTag(id: Int): OwnPack = copy(tags = tags.filterNot { it.id == id })

/**
 * A genre an own pack can pick (stage 4's grid): what it matches, TMDB genre [ids] (movie and TV
 * lists) and addon genre [names] (English, as addons send them; matched case-insensitively). [ids]'
 * first entry is this genre's own and marks it picked; a later entry may be shared (TMDB's TV
 * "Action & Adventure" is under both Action and Adventure), as the built-in pack file shares
 * "War & Politics". Sport has no TMDB genre; the built-in sports pack covers it.
 */
internal enum class OwnPackGenre(@StringRes val label: Int, val ids: List<Int>, val names: List<String>) {
    ACTION(R.string.own_pack_genre_action, listOf(28, 10759), listOf("Action", "Action & Adventure")),
    ADVENTURE(R.string.own_pack_genre_adventure, listOf(12, 10759), listOf("Adventure", "Action & Adventure")),
    ANIMATION(R.string.own_pack_genre_animation, listOf(16), listOf("Animation")),
    COMEDY(R.string.own_pack_genre_comedy, listOf(35), listOf("Comedy")),
    CRIME(R.string.own_pack_genre_crime, listOf(80), listOf("Crime")),
    DOCUMENTARY(R.string.own_pack_genre_documentary, listOf(99), listOf("Documentary")),
    DRAMA(R.string.own_pack_genre_drama, listOf(18), listOf("Drama")),
    FAMILY(R.string.own_pack_genre_family, listOf(10751), listOf("Family")),
    FANTASY(R.string.own_pack_genre_fantasy, listOf(14, 10765), listOf("Fantasy", "Sci-Fi & Fantasy")),
    HISTORY(R.string.own_pack_genre_history, listOf(36), listOf("History")),
    HORROR(R.string.own_pack_genre_horror, listOf(27), listOf("Horror")),
    KIDS(R.string.own_pack_genre_kids, listOf(10762), listOf("Kids")),
    MUSIC(R.string.own_pack_genre_music, listOf(10402), listOf("Music", "Musical")),
    MYSTERY(R.string.own_pack_genre_mystery, listOf(9648), listOf("Mystery")),
    NEWS(R.string.own_pack_genre_news, listOf(10763), listOf("News")),
    REALITY(R.string.own_pack_genre_reality, listOf(10764), listOf("Reality", "Reality-TV")),
    ROMANCE(R.string.own_pack_genre_romance, listOf(10749), listOf("Romance")),
    SCIENCE_FICTION(R.string.own_pack_genre_science_fiction, listOf(878, 10765), listOf("Science Fiction", "Sci-Fi", "Sci-Fi & Fantasy")),
    SOAP(R.string.own_pack_genre_soap, listOf(10766), listOf("Soap")),
    TALK(R.string.own_pack_genre_talk, listOf(10767), listOf("Talk", "Talk-Show")),
    THRILLER(R.string.own_pack_genre_thriller, listOf(53), listOf("Thriller")),
    WAR(R.string.own_pack_genre_war, listOf(10752, 10768), listOf("War", "War & Politics")),
    WESTERN(R.string.own_pack_genre_western, listOf(37), listOf("Western"));

    /** Whether [pack] has this genre picked: it holds the genre's own id. */
    internal fun isPickedIn(pack: OwnPack): Boolean = ids.first() in pack.genreIds
}

/** The genres picked in [pack], in grid order. */
internal fun OwnPack.pickedGenres(): List<OwnPackGenre> = OwnPackGenre.entries.filter { it.isPickedIn(this) }

/**
 * [pack] with [genre] flipped: its ids and names are rebuilt from the picked set, so dropping one
 * genre keeps an id or name another picked genre shares. Names or ids the grid does not offer (none
 * today) are dropped by the rebuild.
 */
internal fun OwnPack.withGenreToggled(genre: OwnPackGenre): OwnPack {
    val picked = pickedGenres().toMutableSet().apply { if (!add(genre)) remove(genre) }
    val ordered = OwnPackGenre.entries.filter { it in picked }
    return copy(
        genreIds = ordered.flatMap { it.ids }.distinct(),
        genreNames = ordered.flatMap { it.names }.distinct()
    )
}

/** What an own pack's row says under its name (stage 4): "Your pack" with its word, genre and tag counts. */
internal data class OwnPackCounts(val words: Int, val genres: Int, val tags: Int)

internal fun OwnPack.counts(): OwnPackCounts = OwnPackCounts(words.size, pickedGenres().size, tags.size)

/**
 * The keyword search's matches as Find a tag lists them (stage 4c): TMDB's order, each id once,
 * blank names and tags [pack] holds already dropped, at most [limit].
 */
internal fun tagMatches(results: List<OwnTag>, pack: OwnPack, limit: Int = TAG_MATCHES_SHOWN): List<OwnTag> {
    val held = pack.tags.mapTo(HashSet()) { it.id }
    return results.filter { it.isUsable && held.add(it.id) }.take(limit)
}

/** Whether a keyword search result can be shown and added: it has a name. */
internal val OwnTag.isUsable: Boolean get() = name.isNotBlank()

internal const val TAG_MATCHES_SHOWN = 20
