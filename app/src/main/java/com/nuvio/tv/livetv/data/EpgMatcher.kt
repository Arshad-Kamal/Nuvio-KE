package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgSnapshot

/** One parsed EPG source: [country] is the file's country (epg.pw `epg_IN` -> `in`), null if unknown. */
data class EpgSource(val id: String, val country: String?, val snapshot: EpgSnapshot)

/**
 * The Spec 34 D9 match chain, per channel:
 * 1. a persisted user override wins outright;
 * 2. a Pluto TV id in a stream URL ([ChannelIdentity.plutoIds]) equals an XMLTV channel id (any
 *    country; i.mjh.nz's Pluto guide keys channels by that id, Spec 38 S1);
 * 3. normalised tvg-id equals a normalised XMLTV channel id (any country);
 * 4. normalised channel name (title, then [Channel.altNames]) equals a normalised XMLTV
 *    display-name or id stem, accepted only when the channel's country equals the EPG source's
 *    country (without the gate NTV.bd got India's NTV and US Discovery got Indian schedules).
 * Steps 2-4 run twice: first accepting only XMLTV ids with programmes in the retained window
 * (free-epg.de is half stale, so its id hit must not shadow a live epg.pw name hit), then
 * accepting any hit. Several XMLTV ids can carry one name (free-epg.de lists a channel 3-5 times):
 * within a pass the first in file order wins.
 */
object EpgMatcher {

    fun match(
        channels: List<Channel>,
        sources: List<EpgSource>,
        overrides: Map<String, EpgOverride>
    ): Map<String, EpgRef> {
        val indexes = sources.map { Index(it) }
        val out = HashMap<String, EpgRef>()
        for (ch in channels) {
            val override = overrides[ch.key]
            val ref = if (override != null) override.ref
            else matchOne(ch, indexes, live = true) ?: matchOne(ch, indexes, live = false)
            if (ref != null) out[ch.key] = ref
        }
        return out
    }

    private fun matchOne(ch: Channel, indexes: List<Index>, live: Boolean): EpgRef? {
        val ids = ChannelIdentity.plutoIds(ch) + listOfNotNull(ChannelIdentity.normalizeTvgId(ch.tvgId))
        for (id in ids) {
            for (ix in indexes) ix.pick(ix.byId[id], live)?.let { return EpgRef(ix.source.id, it) }
        }
        val country = ChannelIdentity.normalizeCountry(ch.country) ?: return null
        val names = (listOf(ch.name) + ch.altNames).map(ChannelIdentity::normalizeName).filter { it.isNotEmpty() }
        for (ix in indexes) {
            if (ix.country != country) continue
            for (n in names) ix.pick(ix.byName[n], live)?.let { return EpgRef(ix.source.id, it) }
        }
        return null
    }

    private class Index(val source: EpgSource) {
        val country = ChannelIdentity.normalizeCountry(source.country)
        val byId = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()

        init {
            for (ch in source.snapshot.channels.values) {
                ChannelIdentity.normalizeTvgId(ch.id)?.let { byId.getOrPut(it) { ArrayList() } += ch.id }
                // free-epg.de ids double as names ("Star Jalsha HD.in"): the country suffix goes.
                for (name in ch.displayNames + ChannelIdentity.stripCountrySuffix(ch.id)) {
                    val n = ChannelIdentity.normalizeName(ChannelIdentity.stripCountrySuffix(name))
                    if (n.isEmpty()) continue
                    val ids = byName.getOrPut(n) { ArrayList() }
                    if (ch.id !in ids) ids += ch.id
                }
            }
        }

        fun pick(ids: List<String>?, live: Boolean): String? = when {
            ids.isNullOrEmpty() -> null
            live -> ids.firstOrNull { source.snapshot.programmes[it].orEmpty().isNotEmpty() }
            else -> ids.first()
        }
    }
}
