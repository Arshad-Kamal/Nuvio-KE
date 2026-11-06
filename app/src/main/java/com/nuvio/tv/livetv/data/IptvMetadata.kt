package com.nuvio.tv.livetv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the app keeps of one iptv-org channel (Spec 34 D3/D4): enough to enrich a playlist entry by
 * its tvg-id. [languages] are the main feed's (else every feed's); [feedLanguages] only lists feeds
 * whose languages differ from those (`DW.de@Deutsch` vs DW's English main feed).
 */
@Serializable
data class ChannelMeta(
    val name: String,
    val altNames: List<String> = emptyList(),
    /** Lower-case country code. */
    val country: String? = null,
    val categories: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    /** Lower-case feed id -> its languages. */
    val feedLanguages: Map<String, List<String>> = emptyMap(),
    /** `is_nsfw`, an `xxx` category, or a blocklist entry with reason `nsfw`. */
    val nsfw: Boolean = false
)

/** iptv-org metadata keyed by normalised channel id (`ChannelIdentity.normalizeTvgId`). */
@Serializable
data class IptvMetadata(val fetchedAtMs: Long, val channels: Map<String, ChannelMeta>)

// Wire DTOs of https://iptv-org.github.io/api/ (only the fields read; the rest is ignored).

@Serializable
internal data class IptvChannelDto(
    val id: String,
    val name: String? = null,
    @SerialName("alt_names") val altNames: List<String>? = null,
    val country: String? = null,
    val categories: List<String>? = null,
    @SerialName("is_nsfw") val isNsfw: Boolean? = null
)

@Serializable
internal data class IptvFeedDto(
    val channel: String,
    val id: String,
    @SerialName("is_main") val isMain: Boolean? = null,
    val languages: List<String>? = null
)

@Serializable
internal data class IptvBlockDto(val channel: String, val reason: String? = null)

internal object IptvMetadataBuilder {

    /** Joins the three iptv-org lists into [IptvMetadata]; blocklisted nsfw ids without a channel get a stub. */
    fun build(
        channels: Sequence<IptvChannelDto>,
        feeds: Sequence<IptvFeedDto>,
        blocklist: Sequence<IptvBlockDto>,
        fetchedAtMs: Long
    ): IptvMetadata {
        val feedsByChannel = HashMap<String, MutableList<IptvFeedDto>>()
        for (f in feeds) {
            val key = ChannelIdentity.normalizeTvgId(f.channel) ?: continue
            feedsByChannel.getOrPut(key) { ArrayList(2) } += f
        }
        val nsfwBlocked = HashSet<String>()
        for (b in blocklist) {
            if (b.reason == "nsfw") ChannelIdentity.normalizeTvgId(b.channel)?.let { nsfwBlocked += it }
        }
        val out = HashMap<String, ChannelMeta>()
        for (c in channels) {
            val key = ChannelIdentity.normalizeTvgId(c.id) ?: continue
            val categories = c.categories.orEmpty()
            val channelFeeds = feedsByChannel[key].orEmpty()
            val main = channelFeeds.firstOrNull { it.isMain == true }
            val languages = main?.languages ?: channelFeeds.flatMap { it.languages.orEmpty() }.distinct()
            val feedLanguages = channelFeeds
                .filter { it.languages.orEmpty() != languages }
                .associate { it.id.lowercase() to it.languages.orEmpty() }
            out[key] = ChannelMeta(
                name = c.name ?: c.id,
                altNames = c.altNames.orEmpty(),
                country = ChannelIdentity.normalizeCountry(c.country),
                categories = categories,
                languages = languages,
                feedLanguages = feedLanguages,
                nsfw = c.isNsfw == true || "xxx" in categories || key in nsfwBlocked
            )
        }
        for (key in nsfwBlocked) if (key !in out) out[key] = ChannelMeta(name = key, nsfw = true)
        return IptvMetadata(fetchedAtMs, out)
    }
}
