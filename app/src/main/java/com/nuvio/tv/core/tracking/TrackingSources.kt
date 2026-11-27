package com.nuvio.tv.core.tracking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Where a profile's Continue Watching (resume points, Up next) or Library is read from and written to (Spec 104
 * C1/C2). One enum serves both pickers: each lists Nuvio plus every connected tracker. [storageId] is the persisted
 * value (synced with the profile settings, D10): never rename it.
 */
enum class TrackingSource(val storageId: String, val providerId: TrackingProviderId?) {
    NUVIO("nuvio", null),
    MDBLIST("mdblist", TrackingProviderId.MDBLIST),
    SIMKL("simkl", TrackingProviderId.SIMKL);

    companion object {
        val DEFAULT: TrackingSource = NUVIO

        /** Unknown or missing values read as [DEFAULT], so a value written by a newer build never breaks an older one. */
        fun fromStorage(value: String?): TrackingSource =
            entries.firstOrNull { it.storageId.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * The source actually used: the chosen one while its tracker is connected, else Nuvio. The choice itself is kept, so
 * reconnecting brings it back (Spec 104 mockup C3); connecting never changes a choice by itself (D9).
 */
fun effectiveTrackingSource(
    requested: TrackingSource,
    connectedProviderIds: Set<TrackingProviderId>
): TrackingSource {
    val providerId = requested.providerId ?: return requested
    return if (providerId in connectedProviderIds) requested else TrackingSource.NUVIO
}

/** The picker's options, in display order: Nuvio, then each connected tracker. */
fun availableTrackingSources(connectedProviderIds: Set<TrackingProviderId>): List<TrackingSource> =
    TrackingSource.entries.filter { source -> source.providerId == null || source.providerId in connectedProviderIds }

/**
 * [local] while this flow says null (Nuvio is the source), else [tracker] of the tracker it names; follows source
 * switches. In a build with no tracker the flow is a constant null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <P : Any, T> Flow<P?>.routeTo(local: Flow<T>, tracker: (P) -> Flow<T>): Flow<T> =
    flatMapLatest { provider -> provider?.let(tracker) ?: local }
