package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

/**
 * One title in a tracker's library as the server holds it (Spec 104 S4b): [externalIds] = every id the service gave for
 * it (`tt...`, `tmdb:N`; the entry's own id need not be repeated), so a transfer can match it whichever id the other
 * side stored. The entry's type is `movie` or `series` (anime as series, or movie for an anime film).
 */
data class TrackingLibraryEntry(val entry: LibraryEntry, val externalIds: Set<String> = emptySet())

/**
 * A tracker as the Library source (Spec 104 C2, C4) and a Transfer library end (S4). KE's Library is one toggle, so the
 * contract is one membership: on any of the lists the tracker shows as its Library (MDBList: its watchlist; Simkl: all
 * five statuses, Spec 106 G1). [items], [readLibrary], [isInLibrary] and [setInLibrary] all mean that membership (a
 * Library transfer copies what they show; it never removes from a tracker, Spec 104 D14). An add puts the title on the
 * tracker's default list (Simkl plan to watch) and never moves a title already listed; a removal the tracker refuses
 * (Simkl's would wipe watch history) throws with nothing changed. Whether the tracker is connected
 * comes from [TrackingProviderRegistry], not from here. A provider sets [TrackingAccount.accountName] on connect: a
 * transfer uses it to notice a switch to another account mid-run.
 */
interface TrackingLibraryProvider {
    val providerId: TrackingProviderId

    val isRefreshing: Flow<Boolean>

    /** The tracker's library for the active profile, newest first (may carry optimistic changes not yet on the server). */
    val items: Flow<List<LibraryEntry>>

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean>

    /** Brings [item] to the wanted membership on [profileId]'s account; no call when it is already there. */
    suspend fun setInLibrary(profileId: Int, item: LibraryEntryInput, inLibrary: Boolean)

    suspend fun refresh(intent: TrackingRefreshIntent)

    /**
     * [profileId]'s library as the server holds it now (Library transfer): every page, no optimistic or cached state,
     * never the active profile's account if that differs. Each page is bounded (60 s) by the provider. Throws on any
     * failure, a partial read included: a transfer removes nothing on a failed read.
     */
    suspend fun readLibrary(profileId: Int): List<TrackingLibraryEntry>

    /**
     * Adds [items] to [profileId]'s library; returns the ones the server accepted (by [LibraryEntryInput.itemId]). The
     * default adds one at a time, each bounded at 30 s; a service with a bulk endpoint overrides it (MDBList's free tier
     * counts requests). Other failures of one item leave it out. Throws [TrackingRateLimitException] when the service
     * says to slow down, carrying what was accepted and sent before it.
     */
    suspend fun addAll(profileId: Int, items: List<LibraryEntryInput>): Set<String> {
        val accepted = mutableSetOf<String>()
        val sent = mutableSetOf<String>()
        for (item in items) {
            sent += item.itemId
            val failure = trackerCallFailure {
                withTimeout(TRACKING_WRITE_TIMEOUT_MS) { setInLibrary(profileId, item, inLibrary = true) }
            }
            if (failure is TrackingRateLimitException) throw TrackingRateLimitException(accepted.toSet(), sent.toSet())
            if (failure == null) accepted += item.itemId
        }
        return accepted
    }
}

/**
 * The service asked to slow down (HTTP 429 or its own signal): a transfer stops and says so. From [TrackingLibraryProvider.addAll]
 * it carries the ids accepted before it and the ids [sent] (null = the whole call was sent, a bulk request).
 */
class TrackingRateLimitException(
    val accepted: Set<String> = emptySet(),
    val sent: Set<String>? = null
) : Exception("rate limited")

@Singleton
class TrackingLibraryProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingLibraryProvider>
) {
    private val providersById: Map<TrackingProviderId, TrackingLibraryProvider> =
        providers.associateBy(TrackingLibraryProvider::providerId)

    init {
        require(providersById.size == providers.size) { "Two library providers share an id" }
    }

    fun providers(): List<TrackingLibraryProvider> = providersById.values.sortedBy { it.providerId.ordinal }

    fun provider(id: TrackingProviderId): TrackingLibraryProvider? = providersById[id]
}
