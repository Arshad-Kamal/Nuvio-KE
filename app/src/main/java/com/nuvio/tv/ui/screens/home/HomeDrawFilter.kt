package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.contentfilter.ActiveFilter
import com.nuvio.tv.core.contentfilter.TagPriority
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.core.contentfilter.TitleVerdict
import com.nuvio.tv.core.contentfilter.shown
import com.nuvio.tv.core.contentfilter.titleRef
import com.nuvio.tv.core.contentfilter.verdictOf
import com.nuvio.tv.core.contentfilter.waitsForTags
import com.nuvio.tv.data.mdblist.isMdbListHomeRow
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.stableKey

/**
 * Spec 117 P5b (design H, D19): Home's content filter, applied where Home draws. Home stores rows
 * filtered by the rating cap only; every draw judges each item as drawn (its own name, description and
 * genres) against [filter] and the cached TMDB tags, with no verdict memo (R7-M2). A hit hides (while
 * previewing, Spec 122 V2, it stays, marked); a clean title shows; an undecided one hides (D10) and is
 * queued, so a `changes` tick redraws it.
 */
internal class HomeDrawFilter(
    private val filter: ContentFilter,
    private val preview: Boolean,
    private val lookup: TitleTagLookup
) {
    /** One pass's rows, and the keys of loaded rows left with no drawn item while a title is undecided. */
    class Drawn(val rows: List<CatalogRow>, val heldRowKeys: Set<String>)

    /** [item] as Home draws it (marked when a previewed hit), or null when hidden. Map reads, no queueing. */
    fun shown(item: MetaPreview): MetaPreview? = lookup.verdictOf(filter, item).shown(item, preview)

    /**
     * Filters [rows], given in display order. Titles whose tags could still change what is drawn are
     * queued: at DRAW in the first [DRAW_PRIORITY_ROWS] rows, BACKGROUND after.
     */
    fun draw(rows: List<CatalogRow>): Drawn {
        val held = HashSet<String>()
        val drawn = rows.mapIndexed { index, row ->
            val priority = if (index < DRAW_PRIORITY_ROWS) TagPriority.DRAW else TagPriority.BACKGROUND
            var undecided = false
            var changed = false
            val items = row.items.mapNotNull { item ->
                val verdict = lookup.verdictOf(filter, item)
                if (verdict == TitleVerdict.Undecided) undecided = true
                if (lookup.waitsForTags(filter, item, preview, verdict)) {
                    item.titleRef()?.let { lookup.request(it, priority) }
                }
                verdict.shown(item, preview).also { if (it !== item) changed = true }
            }
            if (items.isEmpty() && undecided) held += row.legacyKey()
            if (changed) row.copy(items = items) else row
        }
        return Drawn(drawn, held)
    }

    companion object {
        /** Home's eagerly loaded rows (`HomeViewModel.eagerCatalogLoadCount`): their lookups go first. */
        const val DRAW_PRIORITY_ROWS = 8

        fun of(active: ActiveFilter?, lookup: TitleTagLookup): HomeDrawFilter? =
            active?.filter?.let { HomeDrawFilter(it, active.preview, lookup) }
    }
}

/** One draw's catalog rows, hero picks, and the loaded rows held as placeholders. */
internal data class DrawnCatalogRows(
    val displayRows: List<CatalogRow>,
    val heroItems: List<MetaPreview>,
    val heldRowKeys: Set<String>
)

/**
 * [releasedRows] (display order, custom titles and the unreleased filter applied) through [drawFilter].
 * The display rows match [releasedRows] one to one, in order (callers key them by position). The hero
 * picks from the drawn rows, so it never shows a title the filter hides, and skips rows the filter
 * emptied, so they cost no hero slot (rows empty before the filter keep today's slot rule). MDBList rows
 * never feed it: their titles carry no backdrop (Spec 105 S5b1), so they would only take slots.
 */
internal fun drawCatalogRows(releasedRows: List<CatalogRow>, drawFilter: HomeDrawFilter?): DrawnCatalogRows {
    val drawn = drawFilter?.draw(releasedRows)
    val heroRows = (drawn?.rows ?: releasedRows).filterIndexed { index, row ->
        !row.isMdbListHomeRow() && (row.items.isNotEmpty() || releasedRows[index].items.isEmpty())
    }
    return DrawnCatalogRows(drawn?.rows ?: releasedRows, pickHeroSlots(heroRows), drawn?.heldRowKeys.orEmpty())
}

/**
 * The Home row for one catalog key: the drawn row when it shows a title; else a placeholder under the
 * real row's keys while the filter holds it (117/P5b, R7-m2: its slot stays put until a title shows);
 * else the not-yet-loaded catalog's placeholder; else nothing (a row of hits only is dropped, as the cap
 * drops one). [HomeRow.PlaceholderCatalog.displayTitle] carries the type suffix; the presentation drops
 * it itself when the suffix is off.
 */
internal fun catalogHomeRow(
    catalogRow: CatalogRow?,
    held: Boolean,
    lazyPlaceholder: HomeViewModel.PlaceholderDescriptor?,
    strTypeMovie: String,
    strTypeSeries: String
): HomeRow? = when {
    catalogRow != null && catalogRow.items.isNotEmpty() -> HomeRow.Catalog(catalogRow)
    catalogRow != null && held -> HomeRow.PlaceholderCatalog(
        catalogKey = catalogRow.legacyKey(),
        stableCatalogKey = catalogRow.stableKey(),
        addonId = catalogRow.addonId,
        addonName = catalogRow.addonName,
        addonBaseUrl = catalogRow.addonBaseUrl,
        catalogId = catalogRow.catalogId,
        catalogName = catalogRow.catalogName,
        apiType = catalogRow.apiType,
        displayTitle = catalogRowTitle(
            catalogRow.catalogName, catalogRow.apiType, showCatalogTypeSuffix = true, strTypeMovie, strTypeSeries
        )
    )
    lazyPlaceholder != null -> HomeRow.PlaceholderCatalog(
        catalogKey = lazyPlaceholder.catalogKey,
        stableCatalogKey = catalogRowStableKey(
            lazyPlaceholder.addonId,
            lazyPlaceholder.addonBaseUrl,
            lazyPlaceholder.apiType,
            lazyPlaceholder.catalogId
        ),
        addonId = lazyPlaceholder.addonId,
        addonName = lazyPlaceholder.addonName,
        addonBaseUrl = lazyPlaceholder.addonBaseUrl,
        catalogId = lazyPlaceholder.catalogId,
        catalogName = lazyPlaceholder.catalogName,
        apiType = lazyPlaceholder.apiType,
        displayTitle = lazyPlaceholder.displayTitle
    )
    else -> null
}

/** Whether Home keeps [item] in view (clean, or a previewed hit); always with no filter. */
internal fun HomeDrawFilter?.keeps(item: MetaPreview): Boolean = this == null || shown(item) != null

/**
 * The hero copy to publish for a focused, already-prefetched title: the first stored copy Home keeps (a
 * copy may have been enriched into a hit since the last draw), the focused [item] when none is stored;
 * null means publish nothing and redraw.
 */
internal fun prefetchedHeroCopy(storedCopies: List<MetaPreview>, item: MetaPreview, drawFilter: HomeDrawFilter?): MetaPreview? =
    if (storedCopies.isEmpty()) item.takeIf { drawFilter.keeps(it) } else storedCopies.firstOrNull { drawFilter.keeps(it) }

/**
 * What an enrichment does once [itemId]'s meta is merged into its [storedCopies] (117/P5b R8-M2, 122/V2
 * M1): [patch] for the drawn rows, and [hero] to publish (with the trailer retry), null to skip both.
 */
internal data class EnrichedOutcome(val patch: EnrichedPatch, val hero: MetaPreview?)

/**
 * New genres or a new description can make a copy a hit (or, through its allowlist, undecided): each
 * drawn copy is judged merged ([patchEnrichedItem]), and one that would hide or change its mark leaves
 * the rows to a redraw. A copy hidden before the enrichment has no drawn copy and asks for nothing. The
 * hero gets the first kept stored copy; with none kept (or none stored), nothing.
 */
internal fun enrichedOutcome(
    storedCopies: List<MetaPreview>,
    drawnRows: List<CatalogRow>,
    itemId: String,
    drawFilter: HomeDrawFilter?,
    merge: (MetaPreview) -> MetaPreview
): EnrichedOutcome {
    val patch = patchEnrichedItem(drawnRows, itemId, drawFilter, merge)
    return EnrichedOutcome(patch, storedCopies.firstOrNull { drawFilter.keeps(it) })
}

/** What the enrichment does with the drawn rows once an item's meta is merged (117/P5b, 122/V2 M1). */
internal sealed interface EnrichedPatch {
    /** No drawn copy changed. */
    data object Unchanged : EnrichedPatch

    data class Patched(val rows: List<CatalogRow>) : EnrichedPatch

    /** A merged copy would hide or change its mark: leave the rows to the next draw. */
    data object Redraw : EnrichedPatch
}

/**
 * Patches every drawn copy of [itemId] in [rows] with [merge]. With a [drawFilter] each merged copy is
 * judged first: one that would hide, or whose mark would change, asks for a redraw instead.
 */
internal fun patchEnrichedItem(
    rows: List<CatalogRow>,
    itemId: String,
    drawFilter: HomeDrawFilter?,
    merge: (MetaPreview) -> MetaPreview
): EnrichedPatch {
    var changed = false
    val patched = rows.map { row ->
        val itemIndex = row.items.indexOfFirst { it.id == itemId }
        if (itemIndex < 0) return@map row
        val drawnItem = row.items[itemIndex]
        val merged = merge(drawnItem)
        val shown = if (drawFilter == null) merged else {
            drawFilter.shown(merged)?.takeIf { it.filterHits == drawnItem.filterHits } ?: return EnrichedPatch.Redraw
        }
        if (shown == drawnItem) return@map row
        changed = true
        row.copy(items = row.items.toMutableList().also { it[itemIndex] = shown })
    }
    return if (changed) EnrichedPatch.Patched(patched) else EnrichedPatch.Unchanged
}
