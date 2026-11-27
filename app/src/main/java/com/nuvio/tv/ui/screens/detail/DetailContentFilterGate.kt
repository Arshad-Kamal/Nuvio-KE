package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.contentfilter.ActiveFilter
import com.nuvio.tv.core.contentfilter.TagPriority
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.core.contentfilter.TitleVerdict
import com.nuvio.tv.core.contentfilter.titleRef
import com.nuvio.tv.core.contentfilter.verdictOf
import com.nuvio.tv.core.contentfilter.waitsForTags
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview

/**
 * What Detail shows instead of a title its content filters keep from opening (Spec 117 P8):
 * [Hidden], the block naming the packs that hide it (M1 stage 7), or [Unchecked], "Couldn't check
 * this title" with Retry (stage 7b, D36). [profileName] names whose filters they are (null: unread).
 */
sealed interface DetailFilterBlock {
    val title: String
    val profileName: String?

    data class Hidden(
        override val title: String,
        override val profileName: String?,
        val packIds: List<String>,
        val ownPackNames: Map<String, String>
    ) : DetailFilterBlock

    data class Unchecked(override val title: String, override val profileName: String?) : DetailFilterBlock
}

/**
 * One Detail load's content filter, read when the load starts: [active] ([of]), or [broken] (the pack
 * file failed, a build defect, so nothing can be checked and every title reads
 * [DetailFilterBlock.Unchecked]).
 */
internal class DetailFilterGate private constructor(
    private val active: ActiveFilter?,
    private val broken: Boolean,
    private val profileName: String?
) {
    /**
     * The block for [meta], or null when Detail may open it. Tags not cached are fetched first in
     * line (NOW), forgetting a failed fetch's backoff (every open is a person asking, Retry included),
     * and waited for up to [budgetMs]; still undecided then: [DetailFilterBlock.Unchecked], previewing
     * too (122/N7). A hit opens normally while the primary previews (122/V2). A title already hit by its
     * words or genres is blocked at once, naming the packs known then: no tag wait for more names.
     */
    suspend fun judge(meta: Meta, lookup: TitleTagLookup, budgetMs: Long = BUDGET_MS): DetailFilterBlock? {
        if (broken) return DetailFilterBlock.Unchecked(meta.name, profileName)
        val active = active ?: return null
        val filter = active.filter ?: return null
        val item = meta.filterSubject()
        // preview = false: a hit opens either way while previewing, so only an undecided title waits.
        if (lookup.waitsForTags(filter, item, preview = false)) {
            item.titleRef()?.let { ref ->
                lookup.retry(ref)
                lookup.awaitDecided(listOf(ref), TagPriority.NOW, budgetMs)
            }
        }
        return when (val verdict = lookup.verdictOf(filter, item)) {
            TitleVerdict.Clean -> null
            is TitleVerdict.Hit ->
                if (active.preview) null
                else DetailFilterBlock.Hidden(meta.name, profileName, verdict.packIds, active.ownPackNames)
            TitleVerdict.Undecided -> DetailFilterBlock.Unchecked(meta.name, profileName)
        }
    }

    companion object {
        /** P8: Detail waits longer than a row (3 s), the title being the whole screen. */
        const val BUDGET_MS = 8_000L

        fun of(active: ActiveFilter?, profileName: String?) = DetailFilterGate(active, broken = false, profileName = profileName)

        fun broken(profileName: String?) = DetailFilterGate(null, broken = true, profileName = profileName)
    }
}

/** What the content filter judges of a Detail title: as a catalog card would carry it (id, type, name, description, genres). */
private fun Meta.filterSubject() = MetaPreview(
    id = id,
    type = type,
    rawType = rawType,
    name = name,
    poster = null,
    posterShape = posterShape,
    background = null,
    logo = null,
    description = description,
    releaseInfo = null,
    imdbRating = null,
    genres = genres
)
