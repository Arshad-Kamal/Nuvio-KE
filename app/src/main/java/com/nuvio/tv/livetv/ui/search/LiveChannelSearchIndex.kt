package com.nuvio.tv.livetv.ui.search

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.ui.Lineup
import com.nuvio.tv.livetv.ui.shownName

/**
 * Spec 111 D8: what live search looks through, every listable channel of [lineup] whatever filter
 * shows ([Lineup.channels]: hidden, NSFW and lost channels are already left out), in number order.
 * Names are folded here once per lineup; build it off the main thread (tens of thousands of names).
 * Matched by the name rows show ([shownName]: playlist tags such as "(720p)" are not searched).
 */
class LiveChannelSearchIndex(val lineup: Lineup) {

    private class Entry(val channel: Channel, val name: LiveChannelSearch.PreparedName, val number: String?)

    private val entries: List<Entry> = lineup.channels.map { ch ->
        Entry(ch, LiveChannelSearch.prepare(ch.shownName), lineup.numbers[ch.key])
    }

    fun search(query: String): List<LiveChannelSearch.Hit<Channel>> =
        LiveChannelSearch.search(entries, query, name = { it.name }, number = { it.number })
            .map { LiveChannelSearch.Hit(it.item.channel, it.tier, it.foldedStart, it.foldedLength) }

    companion object {
        /**
         * What [hit] marks: chars of the channel's number for a number tier, else of its [shownName]
         * (the name an index folds, so the offsets always fit).
         */
        fun highlight(hit: LiveChannelSearch.Hit<Channel>): IntRange =
            if (hit.tier.onNumber) 0..<hit.foldedLength else LiveChannelSearch.highlight(hit.item.shownName, hit)
    }
}
