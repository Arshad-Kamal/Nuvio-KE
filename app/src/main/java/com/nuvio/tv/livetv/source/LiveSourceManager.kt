package com.nuvio.tv.livetv.source

import android.content.Context
import android.util.Log
import android.util.Xml
import com.nuvio.tv.livetv.data.ChannelEnricher
import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.EpgMatcher
import com.nuvio.tv.livetv.data.EpgSource
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.parse.M3uParser
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds, refreshes and removes Live TV sources (Spec 34 L5): fetches through [SourceFetcher],
 * checks the detected kind, parses, and saves the result: a playlist's channels replace the
 * source's block in [ChannelRepository]; a guide's look-ahead snapshot goes to [GuideRepository].
 * A failed refresh (network, HTTP status, cap, wrong content, empty or cut-short file) keeps the
 * previous data and records the reason on the source. The broadcaster preset (D3, Q7) is an M3U
 * source with the fixed id [PRESET_ID] whose playlist is the bundled asset [PRESET_ASSET]. The two
 * India guide presets (D5) are Sources' suggestion since Spec 49 D27 F5 ([addIndiaGuides]).
 *
 * A Stremio `tv` addon (L5d, O2) is a source whose manifest URL is fetched, checked and expanded
 * into its catalog channels by [StremioTvAdapter]; their streams resolve at play time
 * ([streamsFor]). One mutex serialises every operation on one source, so a removal never races a
 * refresh that would re-add the removed source's data. It is held for the whole fetch: one call
 * ([SourceFetcher.CALL_TIMEOUT_MINUTES] each) for a playlist or guide, up to
 * 1 + catalogs x [StremioTvAdapter.MAX_PAGES] calls for an addon. Suspend
 * functions are main-safe: the stores and the fetcher move their own work to IO, the preset read
 * runs on [io]. File-write failures are reported (refused add, `lastError`, false from [remove]),
 * never thrown.
 */
@Singleton
class LiveSourceManager internal constructor(
    private val store: SourceStore,
    private val channels: ChannelRepository,
    private val guides: GuideRepository,
    private val metadata: IptvMetadataRepository,
    private val fetcher: SourceFetcher,
    private val stremio: StremioTvAdapter,
    private val xmltv: XmltvNowNextParser,
    private val openPreset: () -> InputStream,
    private val nowMs: () -> Long,
    private val newId: () -> String,
    private val io: CoroutineDispatcher
) {
    @Inject
    constructor(
        store: SourceStore,
        channels: ChannelRepository,
        guides: GuideRepository,
        metadata: IptvMetadataRepository,
        fetcher: SourceFetcher,
        stremio: StremioTvAdapter,
        @ApplicationContext context: Context
    ) : this(
        store,
        channels,
        guides,
        metadata,
        fetcher,
        stremio,
        XmltvNowNextParser(Xml::newPullParser),
        { context.assets.open(PRESET_ASSET) },
        System::currentTimeMillis,
        { UUID.randomUUID().toString() },
        Dispatchers.IO
    )

    sealed interface AddResult {
        data class Added(val source: LiveSource) : AddResult
        data class Refused(val reason: String) : AddResult
    }

    /**
     * A URL that passed [check], ready for [add]: [source] is the record adding it writes, [listed]
     * the channels the playlist or addon lists or the guide file holds (for a guide,
     * [LiveSource.channelCount] is the channels it matched). Holds the fetched data, so adding
     * never fetches again.
     */
    class Checked internal constructor(val source: LiveSource, val listed: Int, internal val loaded: Loaded)

    sealed interface CheckResult {
        data class Ready(val checked: Checked) : CheckResult
        data class Refused(val reason: String) : CheckResult
    }

    /** What one fetch produced, before it is saved. */
    internal sealed interface Loaded {
        /** [addonName] is set for a Stremio addon's channels (its manifest name). */
        data class Channels(val list: List<Channel>, val addonName: String? = null) : Loaded
        data class Guide(val snapshot: EpgSnapshot) : Loaded
    }

    /** What the fetched body alone gives: final data, or an addon manifest still to expand. */
    private sealed interface Parsed {
        data class Ready(val loaded: Loaded) : Parsed
        data class Addon(val manifest: StremioTvManifest) : Parsed
    }

    private val mutex = Mutex()

    val sources: StateFlow<List<LiveSource>> = store.sources

    /** Reads the source list, channel cache, iptv-org metadata and cached guides from disk (once each). */
    suspend fun load() {
        store.load()
        channels.load()
        metadata.load()
        guides.load(store.sources.value.filter { it.kind == SourceKind.XMLTV }.map { it.id })
    }

    /** [check], then [add]: adds the source [url] serves once its content checks out. */
    suspend fun addUrl(url: String): AddResult = when (val result = check(url)) {
        is CheckResult.Refused -> AddResult.Refused(result.reason)
        is CheckResult.Ready -> add(result.checked)
    }

    /**
     * Fetches [url] and checks its content without storing anything (the Add source dialog's
     * check, D16): an M3U playlist with at least one channel, or an XMLTV guide with at least one
     * channel (its country, for name matching, is read from the file name: `epg_IN.xml.gz` ->
     * `in`), or a Stremio manifest with a usable `tv` catalog and at least one channel
     * (`stremio://` links are read as https). Named after the addon, else the URL's host (Rename
     * changes it later). Takes no lock: a check that runs for minutes never holds up a refresh.
     */
    suspend fun check(url: String): CheckResult {
        val trimmed = StremioTvAdapter.normalizeUrl(url)
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return CheckResult.Refused("Not a web address")
        load()
        if (store.sources.value.any { it.url == trimmed }) return CheckResult.Refused(ALREADY_ADDED)
        val id = newId()
        val (kind, loaded) = try {
            fetchLoaded(trimmed, id, expect = null)
        } catch (e: IOException) {
            return CheckResult.Refused(describe(e))
        }
        emptyReason(loaded)?.let { return CheckResult.Refused(it) }
        val draft = LiveSource(
            id = id,
            kind = kind,
            name = (loaded as? Loaded.Channels)?.addonName ?: httpUrl.host,
            url = trimmed,
            addedAtMs = nowMs(),
            lastRefreshMs = nowMs(),
            country = if (loaded is Loaded.Guide) guessCountry(httpUrl) else null
        )
        val source = draft.copy(lastError = incompleteNote(loaded), channelCount = count(draft, loaded))
        val listed = when (loaded) {
            is Loaded.Channels -> loaded.list.size
            is Loaded.Guide -> loaded.snapshot.channels.size
        }
        return CheckResult.Ready(Checked(source, listed, loaded))
    }

    /** Stores a [check]ed source and its data; refused when its URL was added meanwhile or a write failed. */
    suspend fun add(checked: Checked): AddResult = mutex.withLock {
        val source = checked.source
        if (store.sources.value.any { it.url == source.url }) return@withLock AddResult.Refused(ALREADY_ADDED)
        // Record first: if the data write then fails (or the process dies), the record stays
        // reachable on the Sources route, where Refresh or Remove repairs it; data written first
        // could be orphaned with no source to remove it by. Not cancellable: a cancel between the
        // writes (the route closing) would leave a record that looks healthy with no data.
        withContext(NonCancellable) {
            try {
                store.upsert(source)
                try {
                    save(source, checked.loaded)
                } catch (e: IOException) {
                    store.remove(source.id)
                    throw e
                }
                AddResult.Added(source)
            } catch (e: IOException) {
                AddResult.Refused(describe(e, saving = true))
            }
        }
    }

    /** Renames source [id] (Sources > Rename); false when it is gone, [name] is blank, or the file write failed. */
    suspend fun rename(id: String, name: String): Boolean = mutex.withLock {
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return@withLock false
        val source = store.get(id) ?: return@withLock false
        if (source.name == trimmed) return@withLock true
        try {
            store.upsert(source.copy(name = trimmed))
            true
        } catch (e: IOException) {
            logFailure("rename", e)
            false
        }
    }

    /** Adds the broadcaster preset (or refreshes it when present) and returns its record. */
    suspend fun addBroadcasters(): LiveSource = mutex.withLock {
        val preset = store.get(PRESET_ID) ?: LiveSource(
            id = PRESET_ID,
            kind = SourceKind.M3U,
            name = PRESET_NAME,
            url = PRESET_URL,
            addedAtMs = nowMs()
        )
        refreshLocked(preset)
    }

    /**
     * Adds each India guide record ([INDIA_GUIDES], Spec 49 D27 F5: Sources' suggestion) that no
     * source holds by URL; returns the records holding the two guides, or null when a write failed.
     * A failed second write keeps the first record: it is an ordinary guide row (fetched by the next
     * [refreshStale]) and the other guide can be added by URL. Fetching is the caller's [refresh].
     */
    suspend fun addIndiaGuides(): List<LiveSource>? = mutex.withLock {
        val held = mutableListOf<LiveSource>()
        for (preset in INDIA_GUIDES) {
            val existing = store.sources.value.firstOrNull { it.url == preset.url }
            if (existing != null) {
                held += existing
                continue
            }
            val guide = preset.copy(addedAtMs = nowMs())
            try {
                store.upsert(guide)
            } catch (e: IOException) {
                logFailure("save India guide", e)
                return@withLock null
            }
            held += guide
        }
        held
    }

    /**
     * Adds the Pluto guide record ([PLUTO_GUIDE], Spec 38 S2: Sources' suggestion) unless a source
     * already has its URL; returns the record that holds it, or null when the write failed. Fetching
     * is the caller's [refresh] (or the next [refreshStale]).
     */
    suspend fun addPlutoGuide(): LiveSource? = mutex.withLock {
        store.sources.value.firstOrNull { it.url == PLUTO_GUIDE.url }?.let { return@withLock it }
        val guide = PLUTO_GUIDE.copy(addedAtMs = nowMs())
        try {
            store.upsert(guide)
            guide
        } catch (e: IOException) {
            logFailure("save Pluto guide", e)
            null
        }
    }

    /** Refreshes source [id]; null when no such source. */
    suspend fun refresh(id: String): LiveSource? = mutex.withLock {
        store.get(id)?.let { refreshLocked(it) }
    }

    /**
     * Refreshes every source [isStale] calls stale, one at a time (each takes the lock on its
     * own, so a removal can slip in between), after the iptv-org metadata when there are channel
     * sources (L5c, every 3 days) so guide match counts see its names. Called on Live TV open,
     * off the UI path (D14).
     */
    suspend fun refreshStale() {
        load()
        // Only playlists carry tvg-ids to enrich; Stremio channels have provider ids.
        if (store.sources.value.any { it.kind == SourceKind.M3U }) metadata.refreshIfStale()
        for (id in store.sources.value.map { it.id }) {
            // Re-checked under the lock: an overlapping call may have refreshed it meanwhile.
            mutex.withLock {
                val source = store.get(id) ?: return@withLock
                if (isStale(source, guides.guides.value[id], nowMs())) refreshLocked(source)
            }
        }
    }

    /**
     * Removes source [id] with its channels (and their guide overrides) or its guide; false when
     * a file could not be written. Data goes first, so a failure leaves the record to retry from.
     */
    suspend fun remove(id: String): Boolean = mutex.withLock {
        val source = store.get(id) ?: return@withLock true
        try {
            when (source.kind) {
                // A user's "this guide" choice must not outlive the guide: the channel falls back to matching.
                SourceKind.XMLTV -> {
                    guides.remove(id)
                    channels.clearOverridesTo(id)
                }
                SourceKind.M3U, SourceKind.STREMIO -> channels.removeSource(id)
            }
            store.remove(id)
            true
        } catch (e: IOException) {
            logFailure("remove", e)
            false
        }
    }

    private suspend fun refreshLocked(source: LiveSource): LiveSource {
        if (source.kind == SourceKind.XMLTV) guides.load(listOf(source.id))
        val loaded = try {
            if (source.id == PRESET_ID) {
                withContext(io) { openPreset().use { Loaded.Channels(parseM3u(source.id, it)) } }
            } else {
                fetchLoaded(source.url, source.id, expect = source.kind).second
            }
        } catch (e: IOException) {
            return record(source.copy(lastError = describe(e)))
        }
        emptyReason(loaded)?.let { return record(source.copy(lastError = it)) }
        // A cut-short guide (bad XML, or a network drop or timeout mid-body: the parser keeps what
        // it read) loses only to a complete snapshot that still covers now.
        val old = guides.guides.value[source.id]
        if (loaded is Loaded.Guide && !loaded.snapshot.complete && old != null && old.complete && nowMs() < old.windowEndMs) {
            return record(source.copy(lastError = GUIDE_INCOMPLETE))
        }
        return try {
            save(source, loaded)
            record(
                source.copy(
                    lastRefreshMs = nowMs(),
                    lastError = incompleteNote(loaded),
                    channelCount = count(source, loaded)
                )
            )
        } catch (e: IOException) {
            record(source.copy(lastError = describe(e, saving = true)))
        }
    }

    /** Stores [updated]; a failed write only loses the status line until the next refresh. */
    private suspend fun record(updated: LiveSource): LiveSource {
        try {
            store.upsert(updated)
        } catch (e: IOException) {
            logFailure("save source", e)
        }
        return updated
    }

    /**
     * Fetches [url] and parses it by its detected kind ([expect], when set, must match); a Stremio
     * manifest is expanded into its catalog channels. Throws IOException / [SourceFetchException].
     */
    private suspend fun fetchLoaded(url: String, sourceId: String, expect: SourceKind?): Pair<SourceKind, Loaded> {
        val (kind, parsed) = fetcher.fetch(url) { detected, body ->
            if (expect != null && detected != expect) throw SourceFetchException(noLongerReason(expect))
            val found = detected ?: throw SourceFetchException(UNRECOGNISED)
            found to parse(found, sourceId, body)
        }
        val loaded = when (parsed) {
            is Parsed.Ready -> parsed.loaded
            is Parsed.Addon -> Loaded.Channels(stremio.channels(sourceId, url, parsed.manifest), parsed.manifest.name)
        }
        return kind to loaded
    }

    private fun parse(kind: SourceKind, sourceId: String, body: InputStream): Parsed = when (kind) {
        SourceKind.M3U -> Parsed.Ready(Loaded.Channels(parseM3u(sourceId, body)))
        SourceKind.XMLTV -> {
            val startNs = System.nanoTime()
            val snapshot = xmltv.parse(body, nowMs())
            // Spec 12/S57: Shield guide time (D14). The body streams from the network, so this is
            // download + parse together.
            Log.i(TAG, "Guide $sourceId fetched + parsed in ${(System.nanoTime() - startNs) / 1_000_000} ms: " +
                "${snapshot.channels.size} channels, complete=${snapshot.complete}")
            Parsed.Ready(Loaded.Guide(snapshot))
        }
        SourceKind.STREMIO -> Parsed.Addon(stremio.readManifest(body))
    }

    /**
     * Streams to try for [channel], in order: its own for playlist channels; for a Stremio
     * channel, the addon's answer now (D6, never cached: addon stream URLs expire), within
     * [STREAM_LOOKUP_MS] so a dead addon cannot eat the per-channel budget (D7, ~30 s). Throws
     * IOException / [SourceFetchException] when the addon cannot answer; the player (L6) treats
     * that like a failed stream.
     */
    suspend fun streamsFor(channel: Channel): List<LiveStream> {
        val providerId = channel.providerId ?: return channel.streams
        val source = store.get(channel.sourceId) ?: throw SourceFetchException("Source removed")
        return withTimeoutOrNull(STREAM_LOOKUP_MS) { stremio.streams(source.url, providerId) }
            ?: throw SourceFetchException("Addon did not answer")
    }

    private suspend fun save(source: LiveSource, loaded: Loaded) = when (loaded) {
        is Loaded.Channels -> channels.replaceSource(source.id, loaded.list)
        is Loaded.Guide -> guides.put(source.id, loaded.snapshot)
    }

    /** Channels for a playlist; for a guide, the visible channels it gives a guide to today. */
    private suspend fun count(source: LiveSource, loaded: Loaded): Int = when (loaded) {
        is Loaded.Channels -> loaded.list.size
        is Loaded.Guide -> {
            channels.load()
            metadata.load()
            withContext(io) {
                EpgMatcher.match(
                    ChannelEnricher.listable(ChannelEnricher.enrich(channels.channels.value, metadata.metadata.value)),
                    listOf(EpgSource(source.id, source.country, loaded.snapshot)),
                    channels.overrides.value
                ).values.count { it.epgSourceId == source.id }
            }
        }
    }

    private fun emptyReason(loaded: Loaded): String? = when (loaded) {
        is Loaded.Channels if loaded.list.isEmpty() ->
            if (loaded.addonName != null) "No channels in this addon" else "No channels in this playlist"
        is Loaded.Guide if loaded.snapshot.channels.isEmpty() -> "No channels in this guide"
        else -> null
    }

    private fun incompleteNote(loaded: Loaded): String? =
        if (loaded is Loaded.Guide && !loaded.snapshot.complete) GUIDE_INCOMPLETE else null

    private fun noLongerReason(kind: SourceKind): String = when (kind) {
        SourceKind.M3U -> "No longer an M3U playlist"
        SourceKind.XMLTV -> "No longer an XMLTV guide"
        SourceKind.STREMIO -> "No longer a Stremio addon"
    }

    private fun parseM3u(sourceId: String, body: InputStream): List<Channel> =
        ChannelIdentity.channelsFromM3u(sourceId, M3uParser.parse(body.reader(Charsets.UTF_8)))

    private fun describe(e: Throwable, saving: Boolean = false): String = when {
        e is SourceFetchException -> e.message ?: UNRECOGNISED
        saving -> {
            logFailure("save", e)
            "Could not save (${e.javaClass.simpleName})"
        }
        else -> {
            logFailure("fetch", e)
            "Network error (${e.javaClass.simpleName})"
        }
    }

    // Class name only: messages of network and file failures can carry the URL or path (KT-SEC-02).
    private fun logFailure(what: String, e: Throwable) = Log.w(TAG, "Source $what failed: ${e.javaClass.simpleName}")

    companion object {
        const val PRESET_ID = "preset"
        const val PRESET_ASSET = "livetv/broadcasters.m3u"
        const val PRESET_URL = "asset:///$PRESET_ASSET"
        const val PRESET_NAME = "Broadcasters"

        /** D5 guide presets: India now/next for the broadcaster and user channels (Spec 49 D27 F5: suggested). */
        val INDIA_GUIDES = listOf(
            LiveSource(
                id = "preset-epg-pw-in",
                kind = SourceKind.XMLTV,
                name = "epg.pw India",
                url = "https://epg.pw/xmltv/epg_IN.xml.gz",
                addedAtMs = 0,
                country = "in"
            ),
            LiveSource(
                id = "preset-free-epg-in",
                kind = SourceKind.XMLTV,
                name = "free-epg.de India",
                url = "https://www.free-epg.de/api/epg/in.xml.gz",
                addedAtMs = 0,
                country = "in"
            )
        )

        /**
         * Spec 38 Q2: every Pluto channel's guide, matched by the id in its `jmp2.uk/plu-` stream
         * URL (EpgMatcher), never by name (country null). Only suggested on Sources when a playlist
         * holds Pluto channels; never added by [addBroadcasters].
         */
        val PLUTO_GUIDE = LiveSource(
            id = "preset-pluto",
            kind = SourceKind.XMLTV,
            name = "Pluto TV",
            url = "https://i.mjh.nz/PlutoTV/all.xml.gz",
            addedAtMs = 0,
            country = null
        )

        /** Play-time stream lookup bound for a Stremio channel. */
        const val STREAM_LOOKUP_MS = 10_000L

        /** Any source: refetch after a day. */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** Guides: refetch this long before the snapshot's look-ahead window ends (D14). */
        const val GUIDE_MARGIN_MS = 60L * 60 * 1000

        private const val UNRECOGNISED = "Not a playlist, guide or Stremio addon"
        private const val ALREADY_ADDED = "Already added"
        /** A guide refresh that kept part of a cut-short file (Sources shows it as partial, not failed). */
        internal const val GUIDE_INCOMPLETE = "Guide file incomplete; kept what was read"
        private const val TAG = "LiveSourceManager"
        private val COUNTRY_STEM = Regex("""(?:^|[_.-])([A-Za-z]{2})$""")

        /**
         * Stale = never refreshed, or older than [MAX_AGE_MS]; a guide also when its snapshot is
         * missing, its look-ahead window ends within [GUIDE_MARGIN_MS] (with the default 6 h
         * window that is about every 5 h, on Live TV open only), or it was cut short and is
         * [GUIDE_MARGIN_MS] old (retried hourly, not on every open).
         */
        internal fun isStale(source: LiveSource, snapshot: EpgSnapshot?, nowMs: Long): Boolean {
            val last = source.lastRefreshMs ?: return true
            return when (source.kind) {
                SourceKind.M3U, SourceKind.STREMIO -> nowMs - last >= MAX_AGE_MS
                SourceKind.XMLTV -> snapshot == null || nowMs - last >= MAX_AGE_MS ||
                    nowMs >= snapshot.windowEndMs - GUIDE_MARGIN_MS ||
                    (!snapshot.complete && nowMs - last >= GUIDE_MARGIN_MS)
            }
        }

        /** Country of a guide from its file name (`epg_IN.xml.gz`, `in.xml`); null when none reads as one. */
        internal fun guessCountry(url: HttpUrl): String? {
            val stem = url.pathSegments.lastOrNull().orEmpty()
                .removeSuffix(".gz").removeSuffix(".xml")
            return COUNTRY_STEM.find(stem)?.groupValues?.get(1)?.let(ChannelIdentity::normalizeCountry)
        }
    }
}
