package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel

/**
 * Joins channels with iptv-org metadata by tvg-id, in memory (Spec 34 D3/D4, L5c): fills country
 * when the source gave none, adds the iptv-org name and `alt_names` for EPG name matching, and
 * sets categories, the feed's languages and the NSFW flag. A channel without metadata is flagged
 * NSFW only by the keyword guard on its name and groups (D4: unmatched user entries). Source
 * fields win; the cache never stores the result, so a metadata refresh needs no channel rewrite.
 */
object ChannelEnricher {

    fun enrich(channels: List<Channel>, meta: IptvMetadata?): List<Channel> = channels.map { enrich(it, meta) }

    fun enrich(ch: Channel, meta: IptvMetadata?): Channel {
        val m = ChannelIdentity.normalizeTvgId(ch.tvgId)?.let { meta?.channels?.get(it) }
            ?: return ch.copy(nsfw = looksAdult(ch))
        val feed = ch.tvgId?.substringAfter('@', "")?.trim()?.lowercase()
        val altNames = (ch.altNames + m.name + m.altNames).filter { it != ch.name }.distinct()
        return ch.copy(
            country = ch.country ?: m.country,
            altNames = altNames,
            categories = m.categories,
            languages = feed?.let { m.feedLanguages[it] } ?: m.languages,
            nsfw = m.nsfw
        )
    }

    /** What a viewer may see: not lost, not NSFW. Input must be enriched ([enrich]). */
    fun listable(enriched: List<Channel>): List<Channel> = enriched.filter { it.lostAtMs == null && !it.nsfw }

    /** D4 keyword guard for channels iptv-org does not know. */
    fun looksAdult(ch: Channel): Boolean = (ch.groups + ch.name).any { ADULT.containsMatchIn(it) }

    // "adult" alone is not enough ("Adult Swim"); group names use "Adult", "Adults only", "For adults".
    private val ADULT = Regex(
        """(?i)\b(xxx|porn\w*|erot\w*|sex\w*|playboy|hustler|adults?\s+only|for\s+adults)\b|18\+|^\s*adults?\s*$"""
    )
}
