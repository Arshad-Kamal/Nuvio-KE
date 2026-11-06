package com.nuvio.tv.livetv.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** One way to play a channel (Spec 34 D6). The origin is the owning [Channel.sourceId]. */
@Serializable
data class LiveStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** DRM channels are out of v1 scope; kept so the UI can say why a stream is skipped. */
    val drm: Boolean = false
)

/**
 * A Live TV channel as one source lists it (Spec 34 D6/D9). [key] is the stable local id
 * (see `ChannelIdentity`): favorites, recents and overrides hang off it, never off a URL or list
 * position (stream health is per stream URL, D8). [streams] keep the source's order.
 */
@Serializable
data class Channel(
    val key: String,
    val sourceId: String,
    val name: String,
    /** The M3U tvg-id as the source wrote it. */
    val tvgId: String? = null,
    /**
     * A Stremio `tv` addon's channel id (streams resolve at play time from it, D6). Kept apart from
     * [tvgId] so ids such as `tve:La1.TV` never feed iptv-org or country lookups.
     */
    val providerId: String? = null,
    /** Lower-case country code (iptv-org convention, e.g. `in`, `uk`); gates name-based EPG matches. */
    val country: String? = null,
    val logo: String? = null,
    val groups: List<String> = emptyList(),
    val channelNumber: String? = null,
    /**
     * Extra names for EPG matching, tried after [name]: the M3U `tvg-name` when it differs from the
     * title; in memory also iptv-org `name` + `alt_names` (`ChannelEnricher`).
     */
    val altNames: List<String> = emptyList(),
    /** Source order; empty for Stremio `tv` channels, whose streams resolve at play time ([providerId]). */
    val streams: List<LiveStream>,
    /** When the source's refresh stopped listing it: hidden as "Source lost", pruned later. */
    val lostAtMs: Long? = null,
    // Filled in memory from iptv-org metadata (`ChannelEnricher`), never by a source or the cache.
    /** iptv-org category ids (`sports`, `news`, ...). */
    @Transient val categories: List<String> = emptyList(),
    /** ISO 639-3 codes of the channel's feed (`eng`, `ben`). */
    @Transient val languages: List<String> = emptyList(),
    /** Adult channel: never listed (D4). */
    @Transient val nsfw: Boolean = false
) {
    /** Its source stopped listing it (D16 "Source lost"): listed in Favorites/Recents only, never tuned. */
    val lost: Boolean get() = lostAtMs != null
}

/** A channel's guide: XMLTV channel [epgChannelId] in the EPG source [epgSourceId]. */
@Serializable
data class EpgRef(val epgSourceId: String, val epgChannelId: String)

/** A user's guide choice for one channel (persisted): [ref] null means "no guide" (never auto-matched). */
@Serializable
data class EpgOverride(val ref: EpgRef?)
