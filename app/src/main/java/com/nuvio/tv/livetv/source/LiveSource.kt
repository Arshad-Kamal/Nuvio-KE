package com.nuvio.tv.livetv.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a source URL serves, detected from the response's first bytes (Spec 34 D10), never the URL. */
@Serializable
enum class SourceKind {
    @SerialName("m3u") M3U,
    @SerialName("xmltv") XMLTV,
    @SerialName("stremio") STREMIO
}

/**
 * One Live TV source the household added (Spec 34 D3, O2): a playlist, a guide or a Stremio `tv`
 * addon. [id] scopes the source's channels in `ChannelRepository`. [lastRefreshMs] is the last
 * successful refresh; [lastError] the newest failure since then (cleared by a success), shown on
 * the Sources route while the previous channels stay in use. [channelCount] is the playlist's
 * channels, or for a guide the channels it matched at its last refresh (D16 "guide matches").
 * [country] (guides only) gates name-based EPG matching (D9); null = tvg-id matches only.
 */
@Serializable
data class LiveSource(
    val id: String,
    val kind: SourceKind,
    val name: String,
    val url: String,
    val addedAtMs: Long,
    val lastRefreshMs: Long? = null,
    val lastError: String? = null,
    val channelCount: Int = 0,
    val country: String? = null
)
