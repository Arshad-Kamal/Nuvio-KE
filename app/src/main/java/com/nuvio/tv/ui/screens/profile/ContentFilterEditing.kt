package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.data.local.ContentFilterRead
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.model.UserProfile
import java.text.Collator
import java.util.Locale

/** How the editor shows a profile's Content filters (Spec 117 P9, D9). */
internal enum class ContentFilterAccess {
    /** The row and its page (Manage profiles, primary active), for every profile, the primary included. */
    EDIT,
    /** A read-only note (any other editor: anyone with the remote reaches it, M1 stage 6). */
    NOTE
}

/** D9: editable exactly where Live TV is ([liveTvEditable]); unlike the rating limit, the primary too. */
internal fun contentFilterAccess(liveTvEditable: Boolean): ContentFilterAccess =
    if (liveTvEditable) ContentFilterAccess.EDIT else ContentFilterAccess.NOTE

/** A built-in pack as the page lists it: [name] and [description] localized. */
internal data class ListedPack(val pack: FilterPack, val name: String, val description: String)

/** P10: the built-in packs by localized name, A to Z in [locale]'s order. */
internal fun listedPacks(
    packs: List<FilterPack>,
    name: (String) -> String,
    description: (String) -> String,
    locale: Locale
): List<ListedPack> {
    val collator = Collator.getInstance(locale)
    return packs.map { ListedPack(it, name(it.id), description(it.id)) }.sortedWith(compareBy(collator) { it.name })
}

/**
 * The names of what is switched on, in page order (M1 stages 1, 6): own packs as listed, then the
 * built-in packs of [listed] (A to Z). My words have no switch and are not counted; an id nothing
 * lists (an unknown pack, an own pack without the own-pack prefix) is not named, as the reader ignores it.
 */
internal fun enabledPackNames(settings: ContentFilterSettings, listed: List<ListedPack>): List<String> =
    settings.ownPacks.filter { it.hasOwnPackId && it.id in settings.enabledPacks }.map { it.name } +
        listed.filter { it.pack.id in settings.enabledPacks }.map { it.name }

/** The row's helper (stage 1): the first [shown] names, then [more] for the rest ("+3"). */
internal fun enabledNamesSummary(names: List<String>, more: (Int) -> String, shown: Int = 2): String {
    val head = names.take(shown)
    return if (names.size > shown) (head + more(names.size - shown)).joinToString() else head.joinToString()
}

/** What a built-in pack's pane lists (stage 2, 122/V5), every entry in full (P10). */
internal data class PackContents(
    val genres: List<String>,
    val words: List<String>,
    val tags: List<String>,
    val neverHidden: List<String>
)

/**
 * [pack]'s pane contents: its genre names, then what its flags match ([adultLabel] for TMDB's adult
 * flag, [japaneseAnimationLabel] for P15); its words in file order (each once, whatever its scope);
 * its TMDB keywords by name; its allowlist (D30).
 */
internal fun packContents(pack: FilterPack, adultLabel: String, japaneseAnimationLabel: String): PackContents =
    PackContents(
        genres = pack.genreNames.toList() +
            listOfNotNull(adultLabel.takeIf { pack.adult }, japaneseAnimationLabel.takeIf { pack.japaneseAnimation }),
        words = pack.words.map { it.text }.distinct(),
        tags = pack.keywordNames,
        neverHidden = pack.allowTitleNames
    )

/** The editor's opening value from a store read: null when it could not be read (P9: shown, never written). */
internal fun ContentFilterRead.storedOrNull(): ContentFilterSettings? = (this as? ContentFilterRead.Stored)?.settings

/** Whether [reads] holds profile [profileId]'s read yet (Edit waits for it, as [ratingCapLoaded]). */
internal fun contentFiltersLoaded(profileId: Int, reads: Map<Int, ContentFilterRead>?): Boolean = reads?.containsKey(profileId) == true

/** Edit's gate (P9): profile [profileId]'s filters and the built-in packs ([packs], null until read) have loaded. */
internal fun contentFiltersReady(profileId: Int, reads: Map<Int, ContentFilterRead>?, packs: List<FilterPack>?): Boolean =
    contentFiltersLoaded(profileId, reads) && packs != null

/**
 * Whether the editor can show and change the filters: they were read ([original] non-null) and the
 * pack file gave packs ([listed] not empty). Otherwise the row dims and opens nothing, the note says so.
 */
internal fun contentFiltersReadable(original: ContentFilterSettings?, listed: List<ListedPack>): Boolean =
    original != null && listed.isNotEmpty()

/** How many names the read-only note spells out before "+N" (stage 6 shows five; more would run off screen). */
internal const val NOTE_NAMES_SHOWN = 5

/**
 * What an edit's save writes (P9): with [ContentFilterAccess.EDIT], the draft's [settings] when they
 * changed; else nothing (a read failure never writes, [changed] is false then).
 */
internal fun contentFilterEditFor(access: ContentFilterAccess, changed: Boolean, settings: ContentFilterSettings): ContentFilterSettings? =
    settings.takeIf { access == ContentFilterAccess.EDIT && changed }

/** What a "Preview filters of" choice shows beside a profile's name (122/D3, mockup spec-122-filter-preview stage 1). */
internal sealed interface PreviewSummary {
    /** [count] packs on, as the profile's own row counts them ("5 on"). */
    data class On(val count: Int) : PreviewSummary
    /** No pack on, but My words: the preview still catches titles. */
    data object MyWordsOnly : PreviewSummary
    data object NothingOn : PreviewSummary
    /** Its read failed: a preview of it catches every built-in pack (122/V1; D35, almost never shown). */
    data object Unreadable : PreviewSummary
}

/**
 * A "Preview filters of" choice: [profileId] null is Off; no [summary] for Off, the primary's own,
 * and a profile still loading (D35: "Couldn't read" only on a failed read).
 */
internal data class PreviewChoice(val profile: UserProfile?, val summary: PreviewSummary?) {
    val profileId: Int? get() = profile?.id
}

/** Whether the editor's page gets the picker (122/D3): only the primary's own page, where filters are editable. */
internal fun previewPickerShown(edited: UserProfile?, access: ContentFilterAccess): Boolean =
    edited?.isPrimary == true && access == ContentFilterAccess.EDIT

/**
 * The picker's choices (122/D3): Off, the primary's own filters, then every other profile in
 * [profiles] order with what it has on ([reads], counted against [listed] as the profile's row does).
 */
internal fun previewChoices(
    profiles: List<UserProfile>,
    reads: Map<Int, ContentFilterRead>?,
    listed: List<ListedPack>
): List<PreviewChoice> {
    val primary = profiles.firstOrNull { it.isPrimary }
    return buildList {
        add(PreviewChoice(null, null))
        if (primary != null) add(PreviewChoice(primary, null))
        profiles.filter { !it.isPrimary }.forEach { profile ->
            add(PreviewChoice(profile, previewSummary(reads?.get(profile.id), listed)))
        }
    }
}

private fun previewSummary(read: ContentFilterRead?, listed: List<ListedPack>): PreviewSummary? {
    val settings = when (read) {
        null -> return null
        ContentFilterRead.Unreadable -> return PreviewSummary.Unreadable
        is ContentFilterRead.Stored -> read.settings
    }
    val count = enabledPackNames(settings, listed).size
    return when {
        count > 0 -> PreviewSummary.On(count)
        settings.myWords.isNotEmpty() -> PreviewSummary.MyWordsOnly
        else -> PreviewSummary.NothingOn
    }
}

/**
 * The choice the picker checks: the primary's stored `previewProfileId` (from [reads]) when a
 * profile in [profiles] has it, else Off (null), as the reader treats an id no profile has (122/V1 N2).
 */
internal fun pickedPreview(reads: Map<Int, ContentFilterRead>?, profiles: List<UserProfile>): Int? {
    val primary = profiles.firstOrNull { it.isPrimary } ?: return null
    return reads?.get(primary.id)?.storedOrNull()?.previewProfileId?.takeIf { id -> profiles.any { it.id == id } }
}
