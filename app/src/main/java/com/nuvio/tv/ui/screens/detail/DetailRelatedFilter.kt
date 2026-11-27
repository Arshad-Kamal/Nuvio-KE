package com.nuvio.tv.ui.screens.detail

import android.util.Log
import com.nuvio.tv.data.repository.CatalogContentFilter
import com.nuvio.tv.domain.model.MetaPreview
import java.io.IOException

/**
 * Spec 117 P8 (S8): a Detail row (More like this, the TMDB collection) through the content filter's
 * one-shot gate ([open] = [CatalogContentFilter.open]; its 3 s budget, hits hidden, undecided titles
 * hidden for this load, previewed hits kept and marked, Spec 122 V3). No gate (nothing filtered): the
 * row as it is. A broken pack file (a build defect, PackFileTest) hides the row rather than fail open.
 */
internal suspend fun filterDetailRow(
    items: List<MetaPreview>,
    open: suspend () -> CatalogContentFilter.Gate?
): List<MetaPreview> {
    if (items.isEmpty()) return items
    // IOException: pack asset unreadable; IllegalArgumentException (incl. SerializationException): malformed.
    // Anything else, cancellation included, propagates.
    val gate = try {
        open()
    } catch (e: IOException) {
        return hiddenRow(e)
    } catch (e: IllegalArgumentException) {
        return hiddenRow(e)
    } ?: return items
    return gate.applyAll(listOf(items)).single()
}

private fun hiddenRow(e: Exception): List<MetaPreview> {
    Log.w("DetailRelatedFilter", "Content filter unreadable, row hidden: ${e.javaClass.simpleName}")
    return emptyList()
}
