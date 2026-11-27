package com.nuvio.tv.data.repository

import android.os.SystemClock
import com.nuvio.tv.core.contentfilter.ActiveContentFilter
import com.nuvio.tv.core.contentfilter.TagPriority
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.core.contentfilter.shown
import com.nuvio.tv.core.contentfilter.titleRef
import com.nuvio.tv.core.contentfilter.verdictOf
import com.nuvio.tv.core.contentfilter.waitsForTags
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 117 P5: the content-filter gate of an addon catalog load (Search, Discover, folders, See all);
 * since S5 (P6) also of TMDB list sources, entity rails and person pages, as one-shot loads alike.
 * Runs after the rating cap, so a title the cap removed is never judged, looked up or previewed.
 * Each page waits for its titles' TMDB tags within one [BUDGET_MS] shared by the whole load, then
 * hides hits and the still-undecided titles (117/D14, D10); while previewing (Spec 122 V2) hits stay,
 * marked with their packs.
 */
@Singleton
class CatalogContentFilter internal constructor(
    private val activeContentFilter: ActiveContentFilter,
    private val lookup: TitleTagLookup,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(activeContentFilter: ActiveContentFilter, lookup: TitleTagLookup) :
        this(activeContentFilter, lookup, SystemClock::elapsedRealtime)

    /**
     * The gate for one load, the active filter read once; null when nothing would hide or mark a title.
     * Never maps a failed read to null (that would fail open): [ActiveContentFilter.current] throws only
     * on a broken pack file.
     */
    suspend fun open(): Gate? {
        val active = activeContentFilter.current() ?: return null
        val filter = active.filter ?: return null
        return Gate(filter, active.preview)
    }

    /**
     * A gated row and whether a title in it still waits for its tags: hidden as undecided or, while
     * previewing, marked from its words alone.
     */
    data class Gated(val row: CatalogRow, val heldUndecided: Boolean) {
        /** Same titles with the same marks: a re-emission would change nothing on screen. */
        fun showsSameAs(other: Gated): Boolean =
            row.items.map { it.id to it.filterHits } == other.row.items.map { it.id to it.filterHits }
    }

    /** One per load, used by that load's collection only (its budget is not shared across coroutines). */
    inner class Gate internal constructor(private val filter: ContentFilter, private val preview: Boolean) {
        private var budgetLeftMs = BUDGET_MS

        /**
         * [row] as the filter leaves it. With [wait] the undecided titles' lookups are awaited for what is
         * left of the load's budget; without it (a disk row painted before its revalidation) they are only
         * queued.
         */
        suspend fun apply(row: CatalogRow, wait: Boolean): Gated {
            awaitTags(row.items, wait)
            return Gated(row.copy(items = judge(row.items)), heldUndecided = row.items.any(::waitsForTags))
        }

        /**
         * Each of [lists] as the filter leaves it, after one wait (what is left of the budget) for the
         * undecided titles of all of them, queued in list order: a page of several rails or departments.
         */
        suspend fun applyAll(lists: List<List<MetaPreview>>): List<List<MetaPreview>> {
            awaitTags(lists.flatten(), wait = true)
            return lists.map(::judge)
        }

        /**
         * A load-more page and what it shows: while the filter empties it and the source has more, up to
         * [MAX_HIDDEN_PAGE_ADVANCE] further pages are fetched (P5's advance rule: an empty load-more page
         * gets no focus target, so the UI never asks for the next one). Page 1 is never advanced.
         */
        suspend fun <P> showLoadMore(
            first: P,
            itemsOf: (P) -> List<MetaPreview>,
            hasMore: (P) -> Boolean,
            fetchNext: suspend (P) -> P
        ): Pair<P, List<MetaPreview>> {
            var page = first
            var shown = applyAll(listOf(itemsOf(page))).single()
            var advanced = 0
            while (shown.isEmpty() && hasMore(page) && advanced < MAX_HIDDEN_PAGE_ADVANCE) {
                page = fetchNext(page)
                shown = applyAll(listOf(itemsOf(page))).single()
                advanced++
            }
            return page to shown
        }

        private suspend fun awaitTags(items: List<MetaPreview>, wait: Boolean) {
            val pending = items.filter(::waitsForTags).mapNotNullTo(LinkedHashSet()) { it.titleRef() }
            if (pending.isEmpty()) return
            val budget = if (wait) budgetLeftMs else 0L
            val start = nowMs()
            lookup.awaitDecided(pending, TagPriority.PAGE, budget)
            if (budget > 0) budgetLeftMs = (budgetLeftMs - (nowMs() - start).coerceAtLeast(0)).coerceAtLeast(0)
        }

        private fun judge(items: List<MetaPreview>): List<MetaPreview> =
            items.mapNotNull { item -> lookup.verdictOf(filter, item).shown(item, preview) }

        private fun waitsForTags(item: MetaPreview): Boolean = lookup.waitsForTags(filter, item, preview)
    }

    companion object {
        internal const val BUDGET_MS = 3_000L

        /** Spec 64 K1b / 117 P5: extra pages fetched after a load-more page the cap or the packs hide entirely. */
        const val MAX_HIDDEN_PAGE_ADVANCE = 3
    }
}
