package com.nuvio.tv.livetv.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a source URL serves, detected from the response's first bytes (Spec 34 D10), never the URL;
 * [PACK] is the channel pack (Spec 73), added by name, and [XTREAM] an Xtream login (Spec 109),
 * added from its fields; neither is ever detected.
 */
@Serializable
enum class SourceKind {
    @SerialName("m3u") M3U,
    @SerialName("xmltv") XMLTV,
    @SerialName("stremio") STREMIO,
    @SerialName("pack") PACK,
    @SerialName("xtream") XTREAM
}

/**
 * One Live TV source the household added (Spec 34 D3, O2): a playlist, a guide or a Stremio `tv`
 * addon. [id] scopes the source's channels in `ChannelRepository`. [lastRefreshMs] is the last
 * successful refresh; [lastError] the newest failure since then (cleared by a success), shown on
 * the Sources route while the previous channels stay in use. [channelCount] is the playlist's
 * channels, or for a guide the channels it matched at its last refresh (D16 "guide matches").
 * [country] (guides only) gates name-based EPG matching (D9); null = tvg-id matches only.
 * [headerGuides] (playlists only) = the guides its header names that Sources may offer (Spec 55 S1).
 * [playlistAttrs] (playlists only) = its `#EXTM3U` attributes other than the guide URLs, as written
 * at the last check or refresh (catchup defaults, tvg-shift: Spec 74 D1); null when none.
 * An Xtream login's [url] is its server base without credentials (the login itself lives in
 * [XtreamAccountStore]); [account] is what the server said of the account at the last check or
 * refresh (Spec 109 S1).
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
    val country: String? = null,
    val headerGuides: HeaderGuides? = null,
    val playlistAttrs: Map<String, String>? = null,
    val account: XtreamAccountInfo? = null
)

/**
 * An Xtream account as `player_api.php` describes it (Spec 109 S1; no credential):
 * [expiresAtMs] null = no expiry sent; [maxConnections] = streams it may play at once, null when
 * not sent; [categories] = its live categories; [guideChannels] = how many of this login's channels
 * the server's guide (`xmltv.php`, cached under the source id, Spec 109 S2) covers, null when no guide
 * was read.
 */
@Serializable
data class XtreamAccountInfo(
    val expiresAtMs: Long? = null,
    val maxConnections: Int? = null,
    val trial: Boolean = false,
    val categories: Int = 0,
    val guideChannels: Int? = null
)

/**
 * The guides a playlist's header (`x-tvg-url` / `url-tvg`) names that Sources may offer (Spec 55
 * D4/D5), set at each check or refresh: [urls] passed the live probe, largest country first.
 * [byCountry] false = the header named 3 or fewer (all offered); true = one per country of the
 * playlist's channels.
 */
@Serializable
data class HeaderGuides(val urls: List<String>, val byCountry: Boolean)
