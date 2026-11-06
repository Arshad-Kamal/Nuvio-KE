package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream

internal fun findCurrentStreamIndex(
    streams: List<Stream>,
    currentStreamInfoHash: String?,
    currentStreamFileIdx: Int?,
    currentStreamAddonName: String?,
    currentStreamUrl: String?
): Int {
    if (streams.isEmpty()) return -1

    // Strategy 1: match by infoHash + fileIdx + addonName (most precise for debrid streams).
    // One file cached on two debrid services gives two rows with the same hash: the one whose
    // URL is playing wins, so the marker, the failover and the limit's playing key (Spec 51
    // R5-7) name the copy that plays.
    if (!currentStreamInfoHash.isNullOrBlank()) {
        val hashMatches = streams.indices.filter { i ->
            val stream = streams[i]
            val streamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash
            val streamFileIdx = stream.fileIdx ?: stream.clientResolve?.fileIdx
            streamInfoHash.equals(currentStreamInfoHash, ignoreCase = true) &&
                (currentStreamFileIdx == null || streamFileIdx == currentStreamFileIdx) &&
                (currentStreamAddonName == null || stream.addonName == currentStreamAddonName)
        }
        if (hashMatches.isNotEmpty()) {
            return hashMatches.firstOrNull { !currentStreamUrl.isNullOrBlank() && streams[it].getStreamUrl() == currentStreamUrl }
                ?: hashMatches.first()
        }
    }

    // Strategy 2: match by addon + URL (works for non-debrid HTTP streams)
    if (!currentStreamUrl.isNullOrBlank() && !currentStreamAddonName.isNullOrBlank()) {
        val urlMatch = streams.indexOfFirst { stream ->
            stream.addonName == currentStreamAddonName &&
                stream.getStreamUrl() == currentStreamUrl
        }
        if (urlMatch >= 0) return urlMatch
    }

    // Fallback: match by URL only (without addon filter)
    if (!currentStreamUrl.isNullOrBlank()) {
        val urlMatch = streams.indexOfFirst { stream ->
            stream.getStreamUrl() == currentStreamUrl
        }
        if (urlMatch >= 0) return urlMatch
    }

    return -1
}
