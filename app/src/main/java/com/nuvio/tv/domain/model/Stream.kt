package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents a stream source from a Stremio addon
 */
@Immutable
data class Stream(
    val name: String?,
    val title: String?,
    val description: String?,
    val url: String?,
    val ytId: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val externalUrl: String?,
    val behaviorHints: StreamBehaviorHints?,
    val addonName: String,
    val addonLogo: String?,
    val sources: List<String>? = null,
    val quality: String? = null,
    val qualityValue: Int = -1,
    val clientResolve: StreamClientResolve? = null,
    val badges: List<StreamBadge> = emptyList(),
    /** The stream's own subtitles (Stremio `stream.subtitles`), http(s) urls only; labelled with this stream's addon. */
    val subtitles: List<Subtitle> = emptyList(),
    /** AIOStreams' structured release data (X1); null for every other addon. */
    val streamData: AioStreamData? = null
) {
    /**
     * Returns the primary stream source URL
     */
    fun getStreamUrl(): String? =
        listOfNotNull(url, externalUrl)
            .firstOrNull { !it.isMagnetLink() && !it.isTorrentUrl() }

    fun torrentMagnetUri(): String? =
        listOfNotNull(url, externalUrl)
            .firstOrNull { it.isMagnetLink() }

    /**
     * Returns true if this is a torrent-only stream (no HTTP URL available).
     * When both infoHash and url are present (e.g. addon-resolved debrid links),
     * the HTTP url is preferred and this returns false.
     */
    fun isTorrent(): Boolean =
        getStreamUrl().isNullOrBlank() &&
            (!infoHash.isNullOrBlank() || !torrentMagnetUri().isNullOrBlank() || hasTorrentUrl())

    fun getEffectiveInfoHash(): String? =
        infoHash?.takeIf { it.isNotBlank() }
            ?: clientResolve?.infoHash?.takeIf { it.isNotBlank() }
            ?: url?.let { extractInfoHashFromTorrentUrl(it) ?: extractInfoHashFromMagnetLink(it) }
            ?: externalUrl?.let { extractInfoHashFromTorrentUrl(it) ?: extractInfoHashFromMagnetLink(it) }

    fun getEffectiveFileIdx(): Int? =
        fileIdx ?: clientResolve?.fileIdx
            ?: url?.let { extractFileIdxFromTorrentUrl(it) } ?: externalUrl?.let { extractFileIdxFromTorrentUrl(it) }

    private fun String.isTorrentUrl(): Boolean =
        this.trimStart().startsWith("torrent:", ignoreCase = true)

    private fun hasTorrentUrl(): Boolean =
        url?.isTorrentUrl() == true || externalUrl?.isTorrentUrl() == true

    private fun extractInfoHashFromTorrentUrl(url: String): String? {
        if (!url.startsWith("torrent:", ignoreCase = true)) return null
        val clean = url.substringAfter("torrent://").substringAfter("torrent:")
            .substringBefore('?')
            .trimEnd('/')
        val hash = clean.substringBefore('/')
        return hash.takeIf { it.length == 40 || it.length == 32 }
    }

    private fun extractInfoHashFromMagnetLink(url: String): String? {
        if (!url.startsWith("magnet:", ignoreCase = true)) return null
        val btih = url.substringAfter("urn:btih:", "")
        if (btih.isBlank()) return null
        val hash = btih.substringBefore('&').substringBefore('?')
        return hash.takeIf { it.length == 40 || it.length == 32 }
    }

    private fun extractFileIdxFromTorrentUrl(url: String): Int? {
        if (!url.startsWith("torrent:", ignoreCase = true)) return null
        val clean = url.substringAfter("torrent://").substringAfter("torrent:")
            .substringBefore('?')
            .trimEnd('/')
        val idxStr = clean.substringAfter('/', "").substringBefore('/')
        return idxStr.toIntOrNull()
    }

    /**
     * Returns true if this is an external URL (opens in browser)
     */
    fun isExternal(): Boolean = externalUrl != null && url == null && !externalUrl.isMagnetLink()

    /**
     * Returns a display name for the stream, or null when no field is usable.
     * UI call sites should substitute a localized fallback (R.string.stream_unknown).
     */
    fun getDisplayNameOrNull(): String? = name ?: title ?: description

    /**
     * Returns a display name for the stream
     */
    fun getDisplayName(): String = getDisplayNameOrNull() ?: "Unknown Stream"

    /**
     * Returns a display description for the stream
     */
    fun getDisplayDescription(): String? = description ?: title

    /**
     * Badge-independent identity, the base of `limitKey` (core/stream/StreamListView.kt),
     * which adds the stream URL and is the key badge jobs, the prefetch gate, the hidden
     * set and the F-5 focus restore use (Spec 51 R5-7: alone this collides for one
     * torrent file cached on two debrid services).
     * Use the playable URL as primary key - but for streams without a playable URL
     * (e.g. statistic/informational entries that only have externalUrl), fall back
     * to name+title+description to avoid all such streams collapsing to one key.
     */
    fun badgeMergeKey(): String {
        infoHash?.lowercase()?.let { hash -> return "$addonName|$hash:${fileIdx ?: ""}" }
        val playableUrl = url ?: clientResolve?.let { resolve ->
            resolve.stream?.raw?.filename ?: resolve.infoHash
        }
        if (playableUrl != null) return "$addonName|$playableUrl"
        return "$addonName|${name}:${title}:${description?.hashCode() ?: 0}"
    }

    /**
     * Returns a stable key for use in LazyColumn/LazyRow.
     * Incorporates all content-identifying fields so the key doesn't change
     * when the list recomposes or items shift position. The [occurrence] parameter
     * disambiguates genuine duplicates (same addon+url+name+title).
     */
    fun stableKey(occurrence: Int = 0): String = buildString {
        append(addonName)
        append('\u0000')
        append(url ?: infoHash ?: clientResolve?.infoHash ?: ytId ?: externalUrl ?: "")
        append('\u0000')
        append(getEffectiveFileIdx() ?: "")
        append('\u0000')
        append(name ?: "")
        append('\u0000')
        append(title ?: "")
        append('\u0000')
        append(description ?: "")
        append('\u0000')
        append(quality ?: "")
        append('\u0000')
        append(sources.orEmpty().joinToString("|"))
        append('\u0000')
        append(occurrence)
    }
}

@Immutable
data class StreamBadge(
    val name: String,
    val imageURL: String = "",
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = ""
)

@Immutable
data class StreamBehaviorHints(
    val notWebReady: Boolean?,
    val bingeGroup: String?,
    val countryWhitelist: List<String>?,
    val proxyHeaders: ProxyHeaders?,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null
)

@Immutable
data class StreamClientResolve(
    val type: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val sources: List<String>?,
    val torrentName: String?,
    val filename: String?,
    val mediaType: String?,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val service: String?,
    val stream: StreamClientResolveStream? = null
)

@Immutable
data class StreamClientResolveStream(
    val raw: StreamClientResolveRaw?
)

@Immutable
data class StreamClientResolveRaw(
    val torrentName: String?,
    val filename: String?,
    val size: Long?,
    val network: String?,
    val parsed: StreamClientResolveParsed?
)

@Immutable
data class StreamClientResolveParsed(
    val rawTitle: String?,
    val parsedTitle: String?,
    val year: Int?,
    val resolution: String?,
    val seasons: List<Int>?,
    val episodes: List<Int>?,
    val quality: String?,
    val hdr: List<String>?,
    val codec: String?,
    val audio: List<String>?,
    val channels: List<String>?,
    val languages: List<String>?,
    val group: String?,
    val network: String?,
    val edition: String?,
    val duration: Long?,
    val bitDepth: String?,
    val extended: Boolean?,
    val theatrical: Boolean?,
    val remastered: Boolean?,
    val unrated: Boolean?
)

/**
 * AIOStreams' `streamData`: the file's size and name, debrid cache state and parsed release facts,
 * plus the upstream addon that found the stream ([upstream], one line), the stream kind ([type]:
 * "debrid", "http", "p2p", ...), the debrid service id ([serviceId], "premiumize") and the file's
 * own length ([durationMs], null when not sent or not positive).
 */
@Immutable
data class AioStreamData(
    val size: Long?,
    val filename: String?,
    val cached: Boolean?,
    val parsedFile: AioParsedFile?,
    val upstream: String? = null,
    val type: String? = null,
    val serviceId: String? = null,
    val durationMs: Long? = null
)

/** AIOStreams' labels as sent ("2160p", "BluRay REMUX", "DV", "DD+", "English", "IMAX"). */
@Immutable
data class AioParsedFile(
    val resolution: String?,
    val quality: String?,
    val encode: String?,
    val releaseGroup: String?,
    val visualTags: List<String>,
    val audioTags: List<String>,
    val audioChannels: List<String>,
    val languages: List<String>,
    val editions: List<String>
)

@Immutable
data class ProxyHeaders(
    val request: Map<String, String>?,
    val response: Map<String, String>?
)

/**
 * Represents streams grouped by addon source
 */
@Immutable
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>,
    /** Upstreams a gateway (AIOStreams) reported as failed or timed out in this answer (Spec 42 P6). */
    val missingUpstreams: List<String> = emptyList()
)

/** Every group's [AddonStreams.missingUpstreams], once each, in group order. */
fun List<AddonStreams>.missingUpstreams(): List<String> = flatMap { it.missingUpstreams }.distinct()

private fun String?.isMagnetLink(): Boolean =
    this?.trimStart()?.startsWith("magnet:", ignoreCase = true) == true
