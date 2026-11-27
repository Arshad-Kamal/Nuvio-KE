package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot

/**
 * Spec 143 P1: the Live TV stores held in memory with equal values shared. A JSON decode (and a
 * parse) makes a new String for every repeated value (a channel's source id, country, group and
 * language codes, programme titles) and a new map or list for every empty one; at ~23k channels that
 * was about half the retained heap (Spec 143 Findings). Each function returns an equal value (same
 * JSON, same map order), sharing one instance per distinct string within the call and the empty
 * constants; an object or list whose elements were already shared is kept, not copied. The pool
 * lives for one call only; the results are never mutated (read-only types, published once).
 */
internal object LiveCompact {

    fun channels(list: List<Channel>): List<Channel> {
        if (list.isEmpty()) return list
        val pool = Pool()
        return pool.keep(list, pool::channel)
    }

    fun metadata(meta: IptvMetadata): IptvMetadata {
        val pool = Pool()
        // Linked: the cache is written in this order, so a rewrite gives the same file.
        val out = LinkedHashMap<String, ChannelMeta>(capacity(meta.channels.size))
        for ((key, m) in meta.channels) out[pool.str(key)] = pool.meta(m)
        return IptvMetadata(meta.fetchedAtMs, out)
    }

    fun guide(snapshot: EpgSnapshot): EpgSnapshot {
        val pool = Pool()
        val channels = LinkedHashMap<String, EpgChannel>(capacity(snapshot.channels.size))
        for ((id, ch) in snapshot.channels) channels[pool.str(id)] = pool.epgChannel(ch)
        val programmes = LinkedHashMap<String, List<EpgProgramme>>(capacity(snapshot.programmes.size))
        for ((id, list) in snapshot.programmes) programmes[pool.str(id)] = pool.keep(list, pool::programme)
        return snapshot.copy(channels = channels, programmes = programmes)
    }

    /** A HashMap capacity that holds [n] entries without a rehash. */
    private fun capacity(n: Int): Int = n * 4 / 3 + 1

    private class Pool {
        private val strings = HashMap<String, String>()

        fun str(s: String): String = strings.getOrPut(s) { s }

        fun strOrNull(s: String?): String? = s?.let(::str)

        /** [l] mapped by [f]; [l] itself when [f] kept every element. */
        fun <T> keep(l: List<T>, f: (T) -> T): List<T> {
            val mapped = l.map(f)
            return if (mapped.indices.all { mapped[it] === l[it] }) l else mapped
        }

        fun list(l: List<String>): List<String> = if (l.isEmpty()) emptyList() else keep(l, ::str)

        fun map(m: Map<String, String>): Map<String, String> {
            if (m.isEmpty()) return emptyMap()
            if (m.all { (k, v) -> str(k) === k && str(v) === v }) return m
            val out = LinkedHashMap<String, String>(capacity(m.size))
            for ((k, v) in m) out[str(k)] = str(v)
            return out
        }

        fun mapOrNull(m: Map<String, String>?): Map<String, String>? = m?.let(::map)

        fun channel(c: Channel): Channel {
            val sourceId = str(c.sourceId)
            val country = strOrNull(c.country)
            val groups = list(c.groups)
            val altNames = list(c.altNames)
            val attrs = mapOrNull(c.attrs)
            val streams = keep(c.streams, ::stream)
            val same = sourceId === c.sourceId && country === c.country && groups === c.groups &&
                altNames === c.altNames && attrs === c.attrs && streams === c.streams
            return if (same) c else c.copy(
                sourceId = sourceId, country = country, groups = groups, altNames = altNames, attrs = attrs, streams = streams
            )
        }

        private fun stream(s: LiveStream): LiveStream {
            val headers = map(s.headers)
            val kodiProps = mapOrNull(s.kodiProps)
            val tags = mapOrNull(s.tags)
            val same = headers === s.headers && kodiProps === s.kodiProps && tags === s.tags
            return if (same) s else s.copy(headers = headers, kodiProps = kodiProps, tags = tags)
        }

        fun meta(m: ChannelMeta): ChannelMeta {
            val altNames = list(m.altNames)
            val country = strOrNull(m.country)
            val categories = list(m.categories)
            val languages = list(m.languages)
            val feedLanguages = feedLanguages(m.feedLanguages)
            val same = altNames === m.altNames && country === m.country && categories === m.categories &&
                languages === m.languages && feedLanguages === m.feedLanguages
            return if (same) m else m.copy(
                altNames = altNames, country = country, categories = categories, languages = languages, feedLanguages = feedLanguages
            )
        }

        private fun feedLanguages(m: Map<String, List<String>>): Map<String, List<String>> {
            if (m.isEmpty()) return emptyMap()
            if (m.all { (k, v) -> str(k) === k && list(v) === v }) return m
            val out = LinkedHashMap<String, List<String>>(capacity(m.size))
            for ((k, v) in m) out[str(k)] = list(v)
            return out
        }

        fun epgChannel(c: EpgChannel): EpgChannel {
            val id = str(c.id)
            val names = list(c.displayNames)
            val icon = strOrNull(c.icon)
            return if (id === c.id && names === c.displayNames && icon === c.icon) c
            else c.copy(id = id, displayNames = names, icon = icon)
        }

        fun programme(p: EpgProgramme): EpgProgramme {
            val title = str(p.title)
            val category = strOrNull(p.category)
            return if (title === p.title && category === p.category) p else p.copy(title = title, category = category)
        }
    }
}
