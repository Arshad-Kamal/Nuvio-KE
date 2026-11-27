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
import com.nuvio.tv.livetv.data.GuideService
import com.nuvio.tv.livetv.data.GuideShift
import com.nuvio.tv.livetv.data.localDayStartMs
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.parse.M3uParser
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds, refreshes and removes Live TV sources (Spec 34 L5): fetches through [SourceFetcher],
 * checks the detected kind, parses, and saves the result: a playlist's channels replace the
 * source's block in [ChannelRepository]; a guide's snapshot of today and tomorrow ([dayWindow],
 * Spec 67 G1c; started earlier by the largest positive guide shift, [guideLookBackMs]) goes to [GuideRepository].
 * A failed refresh (network, HTTP status, cap, wrong content, empty or cut-short file) keeps the
 * previous data and records the reason on the source. The broadcaster preset (D3, Q7) is an M3U
 * source with the fixed id [PRESET_ID] whose playlist is the bundled asset [PRESET_ASSET]. The
 * guide presets ([GUIDE_PRESETS]: services and countries, Spec 55 S2) are Sources' suggestions
 * ([addPresetGuides]). The channel pack (Spec 73 S2b) is a [SourceKind.PACK] source with the fixed
 * id [ChannelPack.SOURCE_ID]: two files from the public list repo ([fetchPack]), kept whole in
 * [PackStore] and listed through [ChannelPack.listing], with the results of [PackScanner]'s scans
 * from this TV ([PackScanStore], [applyScan], Spec 73 S3a). A
 * playlist's own header guides are probed at each check and refresh and kept on its record
 * ([LiveSource.headerGuides], Spec 55 S1); Sources offers them ([addHeaderGuides]).
 *
 * A Stremio `tv` addon (L5d, O2) is a source whose manifest URL is fetched, checked and expanded
 * into its catalog channels by [StremioTvAdapter]; their streams resolve at play time
 * ([streamsFor]). An Xtream login (Spec 109 S1) is checked from its fields ([checkXtream]), its
 * login kept in [XtreamAccountStore] (never in the source record), and listed by [XtreamAdapter]
 * at each check and refresh; its channels carry their stream URLs as a playlist's do. One mutex
 * serialises every operation on one source, so a removal never races a
 * refresh that would re-add the removed source's data. It is held for the whole fetch: one call
 * ([SourceFetcher.CALL_TIMEOUT_MINUTES] each) for a playlist or guide (a playlist's header guides'
 * probes after it, [PROBE_PARALLEL] at a time, [SourceFetcher.PROBE_TIMEOUT_SECONDS] each), up to
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
    private val io: CoroutineDispatcher,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val pack: PackStore,
    private val scans: PackScanStore,
    private val packUrls: PackUrls = PackUrls(),
    private val accounts: XtreamAccountStore,
    private val xtream: XtreamAdapter = XtreamAdapter(fetcher)
) {
    /** Where the pack's files are fetched from (tests point them at a local server). */
    internal data class PackUrls(
        val state: String = ChannelPack.STATE_URL,
        val streams: String = ChannelPack.STREAMS_URL,
        val summary: String = ChannelPack.SUMMARY_URL
    )

    @Inject
    constructor(
        store: SourceStore,
        channels: ChannelRepository,
        guides: GuideRepository,
        metadata: IptvMetadataRepository,
        fetcher: SourceFetcher,
        stremio: StremioTvAdapter,
        pack: PackStore,
        scans: PackScanStore,
        accounts: XtreamAccountStore,
        xtream: XtreamAdapter,
        @ApplicationContext context: Context
    ) : this(
        store,
        channels,
        guides,
        metadata,
        fetcher,
        stremio,
        // The day-window cap is passed per parse ([dayWindowCap]).
        XmltvNowNextParser(Xml::newPullParser),
        { context.assets.open(PRESET_ASSET) },
        System::currentTimeMillis,
        { UUID.randomUUID().toString() },
        Dispatchers.IO,
        pack = pack,
        scans = scans,
        accounts = accounts,
        xtream = xtream
    )

    sealed interface AddResult {
        data class Added(val source: LiveSource) : AddResult
        data class Refused(val reason: String) : AddResult
    }

    /**
     * A URL that passed [check], ready for [add]: [source] is the record adding it writes, [listed]
     * the channels the playlist or addon lists or the guide file holds (for a guide,
     * [LiveSource.channelCount] is the channels it matched). Holds the fetched data, so adding
     * never fetches again. [login] is an Xtream source's login, stored apart from the record.
     */
    class Checked internal constructor(
        val source: LiveSource,
        val listed: Int,
        internal val loaded: Loaded,
        internal val login: XtreamLogin? = null
    )

    sealed interface CheckResult {
        data class Ready(val checked: Checked) : CheckResult
        data class Refused(val reason: String) : CheckResult
    }

    /** What one fetch produced, before it is saved. */
    internal sealed interface Loaded {
        /**
         * [addonName] is set for a Stremio addon's channels (its manifest name); [epgUrls] are a
         * playlist header's guides, [playlistAttrs] its other attributes ([LiveSource.playlistAttrs]);
         * [account] is set for an Xtream login's channels.
         */
        data class Channels(
            val list: List<Channel>,
            val addonName: String? = null,
            val epgUrls: List<String> = emptyList(),
            val playlistAttrs: Map<String, String> = emptyMap(),
            val account: XtreamAccountInfo? = null
        ) : Loaded
        data class Guide(val snapshot: EpgSnapshot) : Loaded
        /** The channel pack: every kept channel, listed or not ([ChannelPack.listing] picks). */
        data class Pack(val channels: List<PackChannel>) : Loaded
    }

    /** What the fetched body alone gives: final data, or an addon manifest still to expand. */
    private sealed interface Parsed {
        data class Ready(val loaded: Loaded) : Parsed
        data class Addon(val manifest: StremioTvManifest) : Parsed
    }

    private val mutex = Mutex()

    /**
     * Serialises the record-only adds ([addGuideRecords]) with [add], so a URL is never stored
     * twice, without waiting on [mutex], which a refresh holds for its whole download.
     */
    private val addMutex = Mutex()

    val sources: StateFlow<List<LiveSource>> = store.sources

    /** Running [refreshStale] calls; [unload] refuses while any runs. Changed and read under [unloadGate]. */
    private var refreshing = 0

    /** Orders [unload]'s check with a [refreshStale] start (short holds only, unlike [mutex]). */
    private val unloadGate = Mutex()

    private val _unloads = MutableStateFlow(0)

    /**
     * Counts the [unload]s that ran (each player init runs one, data held or not): Live TV's screen
     * state drops what it derived from the stores and reads them again when shown.
     */
    val unloads: StateFlow<Int> = _unloads.asStateFlow()

    private val _packSaves = MutableStateFlow(0)

    /**
     * Counts the pack's completed downloads (its file, kept scan results and listing all written):
     * readers of [PackStore] key on it, as an add writes the pack's record before its file
     * ([addLocked]), so the record alone does not mean the file is there (Spec 73 S3c).
     */
    val packSaves: StateFlow<Int> = _packSaves.asStateFlow()

    /** [loadChannels], then [loadGuides]. */
    suspend fun load() {
        loadChannels()
        loadGuides()
    }

    /**
     * Reads the source list, channel cache and iptv-org metadata from disk (once each) and removes any
     * retired preset guide ([retireGuides]): enough for Live TV's rows (Spec 115 I2, D5).
     */
    suspend fun loadChannels() {
        store.load()
        channels.load()
        metadata.load()
        retireGuides()
        if (!loginsSwept) sweepLogins()
    }

    /** [sweepLogins] ran this process (once is enough: what it sweeps arises at a restore or a failed rollback). */
    @Volatile private var loginsSwept = false

    /**
     * Drops stored Xtream logins whose record is gone (a restore replaced `sources.json`; an add
     * whose rollback could not drop its login), so a credential lives only as long as its source.
     * Under [addMutex]: an add writes its record before its login and holds it for both. Skipped
     * while `sources.json` is unreadable ([SourceStore.unreadable]): its list reads as empty, and
     * must not cost every login; an empty list that was read sweeps them all.
     */
    private suspend fun sweepLogins() = addMutex.withLock {
        if (loginsSwept || store.unreadable) return@withLock
        val sources = store.sources.value
        try {
            accounts.retainOnly(sources.filter { it.kind == SourceKind.XTREAM }.mapTo(HashSet()) { it.id })
            loginsSwept = true
        } catch (e: IOException) {
            logFailure("sweep logins", e) // retried at the next load
        }
    }

    /** Reads the cached guides of the saved guide sources and Xtream logins from disk (once each); after [loadChannels]. */
    suspend fun loadGuides() {
        guides.load(store.sources.value.filter { it.kind == SourceKind.XMLTV || it.kind == SourceKind.XTREAM }.map { it.id })
    }

    /**
     * Removes each installed [RETIRED_GUIDE_IDS] record ([remove]: its cache file and the guide
     * choices pointing at it go too). Idempotent; a failed write leaves the record for the next load.
     * Only a load that finds one takes [mutex] (so it may wait behind a refresh's download, once per
     * device); every other load passes the check without the lock.
     */
    private suspend fun retireGuides() {
        for (id in RETIRED_GUIDE_IDS) if (store.get(id) != null) remove(id)
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
     * changes it later). A playlist's header guides are probed here ([headerGuides]). Holds no
     * lock while it fetches: a check that runs for minutes never holds up a refresh ([load] takes
     * [mutex] only to retire a preset guide, [retireGuides]).
     */
    suspend fun check(url: String): CheckResult {
        val trimmed = StremioTvAdapter.normalizeUrl(url)
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return CheckResult.Refused(NOT_WEB_ADDRESS)
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
            country = if (loaded is Loaded.Guide) guessCountry(httpUrl) else null,
            headerGuides = headerGuides(loaded),
            playlistAttrs = playlistAttrs(loaded)
        )
        val source = draft.copy(lastError = incompleteNote(loaded), channelCount = count(draft, loaded))
        val listed = when (loaded) {
            is Loaded.Channels -> loaded.list.size
            is Loaded.Guide -> loaded.snapshot.channels.size
            is Loaded.Pack -> loaded.channels.size
        }
        return CheckResult.Ready(Checked(source, listed, loaded))
    }

    /**
     * Signs in to an Xtream server and lists its live channels without storing anything (Spec 109
     * S1, the Add source dialog's Xtream login check): [server] as typed ([XtreamAdapter.serverBase]),
     * [username] trimmed, [password] exactly as typed (a space can be part of it). Named after the server's host (Rename changes it
     * later). Refused when a field is blank, the server is no web address, the same server and
     * username is already added, or the server refuses ([XtreamAdapter]'s reasons). No reason quotes
     * a credential. Holds no lock while it fetches, as [check].
     */
    suspend fun checkXtream(server: String, username: String, password: String): CheckResult {
        val login = xtreamLogin(server, username, password) ?: return CheckResult.Refused(XTREAM_FILL_IN)
        if (login.server.isEmpty()) return CheckResult.Refused(NOT_WEB_ADDRESS)
        load()
        if (xtreamAdded(login)) return CheckResult.Refused(ALREADY_ADDED)
        val id = newId()
        val loaded = try {
            fetchXtream(id, login)
        } catch (e: IOException) {
            return CheckResult.Refused(describe(e))
        }
        emptyReason(loaded)?.let { return CheckResult.Refused(it) }
        val source = LiveSource(
            id = id,
            kind = SourceKind.XTREAM,
            name = login.server.toHttpUrl().host,
            url = login.server,
            addedAtMs = nowMs(),
            lastRefreshMs = nowMs(),
            channelCount = loaded.list.size,
            account = loaded.account
        )
        return CheckResult.Ready(Checked(source, loaded.list.size, loaded, login))
    }

    /**
     * The Link tab's check (Spec 109 S2.7, stage 11): a pasted `get.php` link carrying a login
     * ([XtreamAdapter.linkLogin]) signs in as Xtream first (D14), Ready as an XTREAM source the
     * panel offers as a login or a playlist; a server already added with that username is "Already
     * added" only (D15); any other refusal falls back to [check] (the Xtream reason is dropped).
     * Any other URL is [check], as is a link [check] reads as no web address (no scheme): no
     * sign-in is sent for a link the playlist check would refuse unread. A link already stored as
     * a playlist still gets the offer (Q5: the upgrade is offered on paste); the panel then offers
     * the login only.
     */
    suspend fun checkLink(url: String): CheckResult {
        if (StremioTvAdapter.normalizeUrl(url).toHttpUrlOrNull() == null) return check(url)
        val login = XtreamAdapter.linkLogin(url) ?: return check(url)
        val xtream = checkXtream(login.server, login.username, login.password)
        return if (xtream is CheckResult.Refused && xtream.reason != ALREADY_ADDED) check(url) else xtream
    }

    /**
     * The login as typed: [username] trimmed, [password] exactly as typed; null when a field is
     * blank, a login with an empty server when [server] is no web address.
     */
    private fun xtreamLogin(server: String, username: String, password: String): XtreamLogin? {
        val user = username.trim()
        if (server.isBlank() || user.isEmpty() || password.isBlank()) return null
        return XtreamLogin(XtreamAdapter.serverBase(server) ?: "", user, password)
    }

    /** This listing's account facts, keeping [previous]'s guide count (the guide is read after the channels). */
    private fun XtreamAccountInfo?.copyGuide(previous: XtreamAccountInfo?): XtreamAccountInfo? =
        this?.copy(guideChannels = previous?.guideChannels)

    /** Whether a stored Xtream source other than [except] holds [login]'s server and username. */
    private suspend fun xtreamAdded(login: XtreamLogin, except: String? = null): Boolean = store.sources.value.any { source ->
        source.kind == SourceKind.XTREAM && source.id != except && source.url == login.server &&
            accounts.get(source.id)?.username == login.username
    }

    /** The username stored for Xtream source [id] (Edit login prefills it); null when no login is on this TV. */
    suspend fun xtreamUsername(id: String): String? = accounts.get(id)?.username

    /**
     * Edit login's check (Spec 109 S2, stage 10): signs in with the edited login of Xtream source
     * [id] and lists its channels under the same id (so channel keys, and with them favourites,
     * stay), storing nothing. [password] null = keep the stored one (read here only: the screen
     * never holds it), sent only to the server it was stored for: another server needs it typed
     * ([XTREAM_RETYPE_PASSWORD]). Refused as [checkXtream] is, when the source is gone, or when
     * another login holds the same server and username.
     */
    suspend fun checkXtreamEdit(id: String, server: String, username: String, password: String?): CheckResult {
        load()
        val source = store.get(id)?.takeIf { it.kind == SourceKind.XTREAM } ?: return CheckResult.Refused(SOURCE_GONE)
        val pass = password ?: run {
            val stored = accounts.get(id) ?: return CheckResult.Refused(XTREAM_FILL_IN)
            // The whole guarantee here: no stored password with an unreadable or another server.
            val typed = XtreamAdapter.serverBase(server) ?: return CheckResult.Refused(
                if (server.isBlank()) XTREAM_FILL_IN else NOT_WEB_ADDRESS
            )
            if (typed != stored.server) return CheckResult.Refused(XTREAM_RETYPE_PASSWORD)
            stored.password
        }
        val login = xtreamLogin(server, username, pass) ?: return CheckResult.Refused(XTREAM_FILL_IN)
        if (login.server.isEmpty()) return CheckResult.Refused(NOT_WEB_ADDRESS)
        if (xtreamAdded(login, except = id)) return CheckResult.Refused(ALREADY_ADDED)
        val loaded = try {
            fetchXtream(id, login)
        } catch (e: IOException) {
            return CheckResult.Refused(describe(e))
        }
        emptyReason(loaded)?.let { return CheckResult.Refused(it) }
        val edited = source.copy(
            url = login.server,
            lastRefreshMs = nowMs(),
            lastError = null,
            channelCount = loaded.list.size,
            account = loaded.account
        )
        return CheckResult.Ready(Checked(edited, loaded.list.size, loaded, login))
    }

    /**
     * Saves an Edit login [checked] by [checkXtreamEdit] over the record as it is now (its name
     * kept: a rename meanwhile stands): the record first, then the login and the channels, then on
     * a server change the old server's guide is dropped. A failed record write changes nothing; a
     * later failure restores the old record and login (the channels and guide on disk still match
     * them). Refused when the source is gone or another login took the same server and username meanwhile.
     */
    suspend fun saveEdit(checked: Checked): AddResult = mutex.withLock {
        addMutex.withLock {
            val login = checked.login ?: return@withLock AddResult.Refused(SOURCE_GONE)
            val current = store.get(checked.source.id)?.takeIf { it.kind == SourceKind.XTREAM }
                ?: return@withLock AddResult.Refused(SOURCE_GONE)
            if (xtreamAdded(login, except = current.id)) return@withLock AddResult.Refused(ALREADY_ADDED)
            // Another server's guide is not this login's: it goes, with its count ([refreshGuide] reads the new one).
            val moved = login.server != current.url
            val listed = checked.source
            val edited = current.copy(
                url = login.server,
                lastRefreshMs = listed.lastRefreshMs,
                lastError = null,
                channelCount = listed.channelCount,
                account = listed.account?.copy(guideChannels = if (moved) null else current.account?.guideChannels)
            )
            withContext(NonCancellable) {
                val old = accounts.get(current.id)
                try {
                    store.upsert(edited)
                    try {
                        accounts.put(current.id, login)
                        save(edited, checked.loaded)
                        // Last: a failed edit keeps the old login's guide (GuideRepository.remove does not throw).
                        if (moved) guides.remove(current.id)
                    } catch (e: IOException) {
                        // Each on its own: one failed restore must not skip the other.
                        try {
                            if (old != null) accounts.put(current.id, old) else accounts.remove(current.id)
                        } catch (cleanup: IOException) {
                            logFailure("restore login", cleanup) // the new login stays under this id until Edit login
                        }
                        record(current)
                        throw e
                    }
                    AddResult.Added(edited)
                } catch (e: IOException) {
                    AddResult.Refused(describe(e, saving = true))
                }
            }
        }
    }

    /**
     * Reads Xtream source [id]'s guide alone (Spec 109 S2: after an add or an Edit login, whose
     * check listed the channels already), as a refresh would after its channels; the record
     * otherwise unchanged. Null when no such login; the source as it is when its login is not on this TV.
     */
    suspend fun refreshGuide(id: String): LiveSource? = mutex.withLock {
        val source = store.get(id)?.takeIf { it.kind == SourceKind.XTREAM } ?: return@withLock null
        val login = accounts.get(id) ?: return@withLock source
        guides.load(listOf(id))
        refreshXtreamGuide(source, login)
    }

    /** [XtreamAdapter.load] as a [Loaded.Channels]. */
    private suspend fun fetchXtream(sourceId: String, login: XtreamLogin): Loaded.Channels {
        val listing = xtream.load(sourceId, login, nowMs())
        return Loaded.Channels(listing.channels, account = listing.account)
    }

    /** Stores a [check]ed source and its data; refused when its URL was added meanwhile or a write failed. */
    suspend fun add(checked: Checked): AddResult = mutex.withLock { addMutex.withLock { addLocked(checked) } }

    private suspend fun addLocked(checked: Checked): AddResult {
        val source = checked.source
        val login = checked.login
        val taken = if (login != null) xtreamAdded(login) else store.sources.value.any { it.url == source.url }
        if (taken) return AddResult.Refused(ALREADY_ADDED)
        // Record first: if the data write then fails (or the process dies), the record stays
        // reachable on the Sources route, where Refresh or Remove repairs it; data written first
        // could be orphaned with no source to remove it by. Not cancellable: a cancel between the
        // writes (the route closing) would leave a record that looks healthy with no data.
        return withContext(NonCancellable) {
            try {
                store.upsert(source)
                try {
                    // The login before the data: a record with channels but no login could not refresh.
                    if (login != null) accounts.put(source.id, login)
                    save(source, checked.loaded)
                } catch (e: IOException) {
                    // The pack file is written before the channels: drop it with the record.
                    if (checked.loaded is Loaded.Pack) {
                        try {
                            pack.clear()
                        } catch (cleanup: IOException) {
                            logFailure("drop pack file", cleanup) // a stale file; the next add overwrites it
                        }
                    }
                    if (login != null) {
                        try {
                            accounts.remove(source.id)
                        } catch (cleanup: IOException) {
                            logFailure("drop login", cleanup) // an orphan login under an unused id
                        }
                    }
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
     * Downloads the channel pack and adds it (Spec 73 D3); refused, with nothing stored, when it
     * is already added or the download or a write fails. Downloads without the lock, as [check]
     * does; [addLocked] checks again under it.
     */
    suspend fun addPack(): AddResult {
        load()
        if (store.get(ChannelPack.SOURCE_ID) != null) return AddResult.Refused(ALREADY_ADDED)
        val loaded = try {
            fetchPack()
        } catch (e: IOException) {
            return AddResult.Refused(describe(e))
        }
        emptyReason(loaded)?.let { return AddResult.Refused(it) }
        val source = LiveSource(
            id = ChannelPack.SOURCE_ID,
            kind = SourceKind.PACK,
            name = ChannelPack.NAME,
            url = ChannelPack.BASE_URL,
            addedAtMs = nowMs(),
            lastRefreshMs = nowMs(),
            channelCount = loaded.channels.size
        )
        return add(Checked(source, loaded.channels.size, loaded))
    }

    /**
     * The pack's figures for the empty state and the Sources offer (Spec 73 D2, D9), read from the
     * list repo; null when they cannot be read (offline, missing, malformed, or slower than
     * [SUMMARY_TIMEOUT_MS]). Stores nothing.
     */
    suspend fun packSummary(): PackSummary? = withTimeoutOrNull(SUMMARY_TIMEOUT_MS) {
        try {
            fetcher.fetch(packUrls.summary) { _, body -> ChannelPack.readSummary(readCapped(body, SUMMARY_MAX_BYTES)) }
        } catch (e: IOException) {
            logFailure("pack summary", e)
            null
        }
    }

    /**
     * Adds the guide records of [presets] (Sources' suggestions, Spec 55 S2) that no source holds
     * by URL; returns the records holding every one of their guides, in order, or null when a write
     * failed ([addGuideRecords]). Fetching is the caller's [refresh] (or the next [refreshStale]).
     */
    suspend fun addPresetGuides(presets: List<GuidePreset>): List<LiveSource>? =
        addGuideRecords(presets.flatMap { it.guides }, "preset guide")

    /**
     * Adds a guide record for each of [named] (URL to row name: a playlist's header guides, Spec 55
     * S1) that no source holds by URL, its country read from the file name ([guessCountry]);
     * returns the records holding them, or null when a write failed ([addGuideRecords]). Fetching
     * is the caller's [refresh].
     */
    suspend fun addHeaderGuides(named: List<Pair<String, String>>): List<LiveSource>? = addGuideRecords(
        named.map { (url, name) ->
            LiveSource(
                id = newId(),
                kind = SourceKind.XMLTV,
                name = name,
                url = url,
                addedAtMs = 0,
                country = url.toHttpUrlOrNull()?.let(::guessCountry)
            )
        },
        "header guide"
    )

    /**
     * Stores each of [drafts] (guide records, stamped now) that no source holds by URL; returns
     * the records holding them, in order, or null when a write failed. Takes [addMutex], not
     * [mutex]: a new record touches no other source's data. Records written before a
     * failed one stay: each is an ordinary guide row (fetched by the next [refreshStale]) and the
     * rest can be added again.
     */
    private suspend fun addGuideRecords(drafts: List<LiveSource>, what: String): List<LiveSource>? = addMutex.withLock {
        val held = mutableListOf<LiveSource>()
        for (draft in drafts) {
            val existing = store.sources.value.firstOrNull { it.url == draft.url }
            if (existing != null) {
                held += existing
                continue
            }
            val guide = draft.copy(addedAtMs = nowMs())
            try {
                store.upsert(guide)
            } catch (e: IOException) {
                logFailure("save $what", e)
                return@withLock null
            }
            held += guide
        }
        held
    }

    /** Refreshes source [id]; null when no such source. */
    suspend fun refresh(id: String): LiveSource? = mutex.withLock {
        store.get(id)?.let { refreshLocked(it) }
    }

    /**
     * Refreshes every source [isStale] calls stale, one at a time (each takes the lock on its
     * own, so a removal can slip in between), after the iptv-org metadata when there are channel
     * sources (L5c, every 3 days) so guide match counts see its names. Called on Live TV open,
     * off the UI path (D14). The channels it saves publish once, when it ends (Spec 115 D4: one
     * lineup re-derivation per refresh); guides publish as each is saved, so each old snapshot is
     * freed at once. Any other channel write meanwhile publishes at once.
     */
    suspend fun refreshStale() {
        unloadGate.withLock { refreshing++ }
        try {
            refreshStaleCounted()
        } finally {
            withContext(NonCancellable) { unloadGate.withLock { refreshing-- } }
        }
    }

    /**
     * Spec 146 (F19): lets the channels, iptv-org metadata and guides held in memory go, so a 4K start
     * does not carry them; the next load reads them from disk again. Refused (false) while a source operation holds [mutex] or a
     * [refreshStale] runs: those read the held data mid-way (cached guides, unpublished channels).
     */
    suspend fun unload(): Boolean = unloadGate.withLock {
        if (refreshing > 0 || !mutex.tryLock()) return@withLock false
        try {
            withContext(NonCancellable) {
                channels.unload()
                metadata.unload()
                guides.unload()
                _unloads.update { it + 1 }
            }
            true
        } finally {
            mutex.unlock()
        }
    }

    /** [refreshStale]'s work, run while it is counted in [refreshing] (so [unload] keeps out). */
    private suspend fun refreshStaleCounted() {
        load()
        // Only playlists, Xtream logins and the pack carry tvg-ids to enrich; Stremio channels have provider ids.
        if (store.sources.value.any { it.kind == SourceKind.M3U || it.kind == SourceKind.PACK || it.kind == SourceKind.XTREAM }) {
            metadata.refreshIfStale()
        }
        try {
            for (id in store.sources.value.map { it.id }) {
                // Re-checked under the lock: an overlapping call may have refreshed it meanwhile.
                mutex.withLock {
                    val source = store.get(id) ?: return@withLock
                    if (isStale(source, guides.guides.value[id], nowMs(), zone())) refreshLocked(source, publish = false)
                }
            }
        } finally {
            // Also when cancelled: what was saved must reach the screens.
            withContext(NonCancellable) { channels.publishLatest() }
        }
    }

    /**
     * Removes source [id] with its channels (and their guide overrides) or its guide; false when
     * a file could not be written. Data goes first, so a failure leaves the record to retry from.
     */
    suspend fun remove(id: String): Boolean = withContext(NonCancellable) {
        // Not cancellable, the wait for the lock included (a refresh holds it for a whole download): Back
        // right after Remove closes the Sources route, and a cancel would drop the remove unseen, or
        // between the writes leave the record with its channels gone and its login kept.
        mutex.withLock {
            val source = store.get(id) ?: return@withLock true
            removeLocked(id, source)
        }
    }

    private suspend fun removeLocked(id: String, source: LiveSource): Boolean {
        return try {
            when (source.kind) {
                // A user's "this guide" choice must not outlive the guide: the channel falls back to matching.
                SourceKind.XMLTV -> {
                    guides.remove(id)
                    channels.clearOverridesTo(id)
                }
                SourceKind.M3U, SourceKind.STREMIO -> channels.removeSource(id)
                // Channels, then the login: a failure keeps the record, and Remove again finishes it.
                SourceKind.XTREAM -> {
                    channels.removeSource(id)
                    guides.remove(id)
                    channels.clearOverridesTo(id)
                    accounts.remove(id)
                }
                // Channels first, as a playlist's: a failed file delete then keeps the record to retry from.
                SourceKind.PACK -> {
                    channels.removeSource(id)
                    pack.clear()
                    scans.clear()
                }
            }
            store.remove(id)
            true
        } catch (e: IOException) {
            logFailure("remove", e)
            false
        }
    }

    /** [publish] false: saved channels reach disk and [ChannelRepository.latest], not the screens ([refreshStale]). */
    private suspend fun refreshLocked(source: LiveSource, publish: Boolean = true): LiveSource {
        if (source.kind == SourceKind.XMLTV || source.kind == SourceKind.XTREAM) guides.load(listOf(source.id))
        val login = if (source.kind == SourceKind.XTREAM) accounts.get(source.id) else null
        val loaded = try {
            when {
                source.id == PRESET_ID -> withContext(io) { openPreset().use { parseM3u(source.id, it) } }
                source.kind == SourceKind.PACK -> fetchPack()
                source.kind == SourceKind.XTREAM ->
                    fetchXtream(source.id, login ?: throw SourceFetchException(XTREAM_LOGIN_MISSING))
                else -> fetchLoaded(source.url, source.id, expect = source.kind).second
            }
        } catch (e: XtreamAdapter.Expired) {
            // The date the server gave, for the row (Spec 109 stage 9); the channels stay.
            val account = (source.account ?: XtreamAccountInfo()).let { it.copy(expiresAtMs = e.expiresAtMs ?: it.expiresAtMs) }
            return record(source.copy(lastError = describe(e), account = account))
        } catch (e: IOException) {
            return record(source.copy(lastError = describe(e)))
        }
        emptyReason(loaded)?.let { return record(source.copy(lastError = it)) }
        val saving = if (loaded is Loaded.Guide) {
            guideToSave(source.id, loaded) ?: return record(source.copy(lastError = GUIDE_INCOMPLETE))
        } else {
            loaded
        }
        return try {
            save(source, saving, publish)
            val saved = record(
                source.copy(
                    lastRefreshMs = nowMs(),
                    lastError = incompleteNote(saving),
                    channelCount = count(source, saving),
                    playlistAttrs = playlistAttrs(loaded),
                    account = (loaded as? Loaded.Channels)?.account?.copyGuide(source.account) ?: source.account
                )
            )
            // Probed once the refresh is recorded: a cancel meanwhile only keeps the old header guides.
            val header = headerGuides(loaded)
            val withHeader = if (header == saved.headerGuides) saved else record(saved.copy(headerGuides = header))
            if (login != null) refreshXtreamGuide(withHeader, login) else withHeader
        } catch (e: IOException) {
            record(source.copy(lastError = describe(e, saving = true)))
        }
    }

    /**
     * [loaded] as it is saved over source [id]'s cached guide; null when it is cut short (bad XML,
     * or a network drop or timeout mid-body: the parser keeps what it read) and loses to a complete
     * snapshot that still covers today and tomorrow. Otherwise (after midnight; kept losing, every
     * open would refetch) it wins, filled from the cached snapshot ([fillFrom]: an early cut must
     * not blank today, nor an hourly retry what the last fill kept).
     */
    private fun guideToSave(id: String, loaded: Loaded.Guide): Loaded.Guide? {
        val partial = loaded.snapshot.takeIf { !it.complete } ?: return loaded
        val cached = guides.guides.value[id] ?: return loaded
        if (cached.complete && coversDays(cached, nowMs(), zone())) return null
        return Loaded.Guide(fillFrom(partial, cached))
    }

    /**
     * Fetches the Xtream login's own guide (`xmltv.php`, Spec 109 S2 / D4 Q2) after its channels,
     * caches it under the source id and records how many of the login's channels it covers. Any
     * failure (none served, not a guide, too large, a cut-short one that loses) keeps the cached
     * guide and leaves the login's status alone: the guide is extra, the channels are the source.
     * The URL carries the password: it is built here, fetched, and dropped (never logged or recorded).
     */
    private suspend fun refreshXtreamGuide(source: LiveSource, login: XtreamLogin): LiveSource {
        val url = XtreamAdapter.xmltvUrl(login) ?: return source
        val guide = try {
            fetchLoaded(url, source.id, expect = SourceKind.XMLTV).second as? Loaded.Guide
        } catch (e: IOException) {
            logFailure("xtream guide", e)
            null
        }
        val saving = guide?.takeIf { emptyReason(it) == null }?.let { guideToSave(source.id, it) } ?: return source
        return try {
            guides.put(source.id, saving.snapshot)
            val covered = guideMatches(source.id, null, saving.snapshot, channels.latest().filter { it.sourceId == source.id })
            record(source.copy(account = (source.account ?: XtreamAccountInfo()).copy(guideChannels = covered)))
        } catch (e: IOException) {
            logFailure("save xtream guide", e)
            source
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
        // Source caps: this read streams a playlist or guide into its bounded parser (Spec 137).
        val (kind, parsed) = fetcher.fetch(url, sourceCaps = true) { detected, body ->
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

    /**
     * The channel pack from the list repo: the monitor's states first, then the pool, joined by
     * URL ([ChannelPack]). Each file is one [SourceFetcher.fetch] (its call timeout and its text cap,
     * whatever the head): a TSV is detected as no kind, so a body detected as any kind is refused
     * before it is read (the format changed).
     * Throws IOException / [SourceFetchException] (a missing column, a cut-short body).
     */
    private suspend fun fetchPack(): Loaded.Pack {
        val state = fetcher.fetch(packUrls.state) { kind, body -> ChannelPack.readState(tsv(kind, body)) }
        val channels = fetcher.fetch(packUrls.streams) { kind, body -> ChannelPack.readStreams(tsv(kind, body), state) }
        return Loaded.Pack(channels)
    }

    private fun tsv(kind: SourceKind?, body: InputStream): java.io.BufferedReader =
        if (kind != null) throw SourceFetchException(ChannelPack.FORMAT_CHANGED) else body.bufferedReader(Charsets.UTF_8)

    private fun parse(kind: SourceKind, sourceId: String, body: InputStream): Parsed = when (kind) {
        SourceKind.M3U -> Parsed.Ready(parseM3u(sourceId, body))
        SourceKind.XMLTV -> {
            val startNs = System.nanoTime()
            val lookBackMs = guideLookBackMs(channels.channels.value, store.sources.value)
            val (dayStart, windowEnd) = dayWindow(nowMs(), zone())
            val windowStart = guideWindowStart(dayStart, lookBackMs)
            val snapshot = xmltv.parse(body, windowStart, windowEnd - windowStart, maxPerChannel = dayWindowCap(lookBackMs))
                .copy(dayStartMs = dayStart)
            // Spec 12/S57: Shield guide time (D14). The body streams from the network, so this is
            // download + parse together.
            Log.i(TAG, "Guide $sourceId fetched + parsed in ${(System.nanoTime() - startNs) / 1_000_000} ms: " +
                "${snapshot.channels.size} channels, complete=${snapshot.complete}")
            Parsed.Ready(Loaded.Guide(snapshot))
        }
        SourceKind.STREMIO -> Parsed.Addon(stremio.readManifest(body))
        // Never detected from a body (SourceFetcher.detectKind).
        SourceKind.PACK, SourceKind.XTREAM -> throw SourceFetchException(UNRECOGNISED)
    }

    /**
     * Streams to try for [channel], in order: its own for playlist channels; for a Stremio
     * channel, the addon's answer now (D6, never cached: addon stream URLs expire), within
     * [STREAM_LOOKUP_MS] so a dead addon cannot eat the per-channel budget (D7, ~30 s). Throws
     * IOException / [SourceFetchException] when the addon cannot answer; the player (L6) treats
     * that like a failed stream. Spec 147 F27: [channel] may be a screen's stale copy, so its source
     * and its current streams are read here: a removed source's channel throws [SOURCE_GONE] whatever
     * its kind (its stored Xtream URL carries the login), and an edited login's channel plays its
     * new URL, not the copy's.
     */
    suspend fun streamsFor(channel: Channel): List<LiveStream> {
        val source = store.get(channel.sourceId) ?: throw SourceFetchException(SOURCE_GONE)
        val providerId = channel.providerId ?: return channels.latest()
            .firstOrNull { it.key == channel.key && it.sourceId == channel.sourceId }?.streams
            ?: throw SourceFetchException(SOURCE_GONE)
        return withTimeoutOrNull(STREAM_LOOKUP_MS) { stremio.streams(source.url, providerId) }
            ?: throw SourceFetchException("Addon did not answer")
    }

    private suspend fun save(source: LiveSource, loaded: Loaded, publish: Boolean = true) = when (loaded) {
        is Loaded.Channels -> channels.replaceSource(source.id, loaded.list, publish)
        is Loaded.Guide -> guides.put(source.id, loaded.snapshot)
        // The whole pack first (the scan's input), then its scan results kept by URL, then the listing.
        is Loaded.Pack -> {
            pack.save(loaded.channels)
            val stored = scans.read()
            val results = withContext(io) { forPack(stored.results, loaded.channels) }
            if (results.size != stored.results.size) scans.save(stored.copy(results = results))
            channels.replaceSource(source.id, withContext(io) { ChannelPack.listing(loaded.channels, results) }, publish)
            _packSaves.update { it + 1 }
        }
    }

    /**
     * Stores a running or ended scan's [results] (this scan's so far, over the stored ones; the
     * stored ones of [cleared] dropped: back to the monitor's state) and [record] (the last scan),
     * then lists the pack anew from them (Spec 73 S3a, [PackScanner]). False when the pack is no
     * longer added (the scan stops); a failed write is logged and the next call retries it.
     */
    suspend fun applyScan(
        results: Map<String, PackScanResult>,
        cleared: Set<String>,
        record: PackScanRecord
    ): Boolean = mutex.withLock {
        if (store.get(ChannelPack.SOURCE_ID) == null) return@withLock false
        try {
            val packChannels = pack.read()
            val merged = withContext(io) { forPack(scans.read().results - cleared + results, packChannels) }
            scans.save(PackScans(results = merged, last = record))
            channels.replaceSource(ChannelPack.SOURCE_ID, withContext(io) { ChannelPack.listing(packChannels, merged) })
        } catch (e: IOException) {
            logFailure("save scan", e)
        }
        true
    }

    /** Channels for a playlist; for a guide, the visible channels it gives a guide to today. */
    private suspend fun count(source: LiveSource, loaded: Loaded): Int = when (loaded) {
        is Loaded.Channels -> loaded.list.size
        // Every pack channel, listed or waiting for a scan (the PACK row's count, D6).
        is Loaded.Pack -> loaded.channels.size
        is Loaded.Guide -> guideMatches(source.id, source.country, loaded.snapshot, channels.latest())
    }

    /** How many of the visible channels among [among] guide [snapshot] (source [id], [country]) gives a guide to. */
    private suspend fun guideMatches(id: String, country: String?, snapshot: EpgSnapshot, among: List<Channel>): Int {
        metadata.load()
        return withContext(io) {
            EpgMatcher.match(
                ChannelEnricher.listable(ChannelEnricher.enrich(among, metadata.metadata.value)),
                listOf(EpgSource(id, country, snapshot)),
                channels.overrides.value
            ).values.count { it.epgSourceId == id }
        }
    }

    private fun emptyReason(loaded: Loaded): String? = when (loaded) {
        is Loaded.Channels if loaded.list.isEmpty() -> when {
            loaded.addonName != null -> "No channels in this addon"
            loaded.account != null -> "No live channels on this account"
            else -> "No channels in this playlist"
        }
        is Loaded.Guide if loaded.snapshot.channels.isEmpty() -> "No channels in this guide"
        is Loaded.Pack if loaded.channels.isEmpty() -> "No channels in the channel pack"
        else -> null
    }

    private fun incompleteNote(loaded: Loaded): String? =
        if (loaded is Loaded.Guide && !loaded.snapshot.complete) GUIDE_INCOMPLETE else null

    private fun noLongerReason(kind: SourceKind): String = when (kind) {
        SourceKind.M3U -> "No longer an M3U playlist"
        SourceKind.XMLTV -> "No longer an XMLTV guide"
        SourceKind.STREMIO -> "No longer a Stremio addon"
        SourceKind.PACK -> ChannelPack.FORMAT_CHANGED
        // Never fetched by URL ([refreshLocked] lists it through the adapter).
        SourceKind.XTREAM -> XtreamAdapter.NOT_XTREAM
    }

    private fun parseM3u(sourceId: String, body: InputStream): Loaded.Channels {
        val playlist = M3uParser.parse(body.reader(Charsets.UTF_8))
        return Loaded.Channels(
            ChannelIdentity.channelsFromM3u(sourceId, playlist),
            epgUrls = playlist.epgUrls,
            playlistAttrs = playlist.attrs
        )
    }

    private fun playlistAttrs(loaded: Loaded): Map<String, String>? =
        (loaded as? Loaded.Channels)?.playlistAttrs?.ifEmpty { null }

    /**
     * The header guides of a fetched playlist Sources may offer ([headerCandidates]), each country's
     * files tried in header order until one passes [offerable]; probes run [PROBE_PARALLEL] at a
     * time. Null for anything else, or when none passes.
     */
    private suspend fun headerGuides(loaded: Loaded): HeaderGuides? {
        if (loaded !is Loaded.Channels) return null
        val (groups, byCountry) = headerCandidates(loaded.epgUrls, loaded.list) ?: return null
        val gate = Semaphore(PROBE_PARALLEL)
        val urls = coroutineScope {
            groups.map { files -> async { gate.withPermit { files.firstOrNull { offerable(it) } } } }.awaitAll()
        }.filterNotNull()
        return urls.takeIf { it.isNotEmpty() }?.let { HeaderGuides(it, byCountry) }
    }

    /**
     * False when a HEAD finds [url] gone (404/410) or larger than [MAX_GUIDE_DOWNLOAD_BYTES]; a
     * probe that fails on the network keeps it (its fetch reports the problem on the guide's row).
     */
    private suspend fun offerable(url: String): Boolean {
        val probe = try {
            fetcher.probe(url)
        } catch (e: IOException) {
            logFailure("probe", e)
            return true
        }
        return probe.code != 404 && probe.code != 410 && (probe.length ?: 0) <= MAX_GUIDE_DOWNLOAD_BYTES
    }

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

        /**
         * D5 guide presets: India now/next for the broadcaster and user channels (Spec 49 D27 F5:
         * suggested). One guide since Spec 55 S4 (D16): epg.pw India was dropped, its times 8 h late.
         */
        val INDIA_GUIDES = listOf(
            guideRecord("preset-free-epg-in", "free-epg.de India", "https://www.free-epg.de/api/epg/in.xml.gz", "in")
        )

        /**
         * Preset guide records [load] removes from installed lists: epg.pw India (Spec 55 S4, D16)
         * writes UTC+8 times as UTC (386 of 387 channels 8 h late, 2026-11-11).
         */
        internal val RETIRED_GUIDE_IDS = listOf("preset-epg-pw-in")

        /**
         * Spec 38 Q2: every Pluto channel's guide, matched by the id in its `jmp2.uk/plu-` stream
         * URL (EpgMatcher), never by name (country null). Only suggested on Sources when a playlist
         * holds Pluto channels; never added by [addBroadcasters].
         */
        val PLUTO_GUIDE = guideRecord("preset-pluto", "Pluto TV", "https://i.mjh.nz/PlutoTV/all.xml.gz", null)

        /**
         * Sources' guide suggestions (Spec 55 S2, D6-D10; URLs curl-checked 2026-11-10). A service
         * preset fits channels whose stream URL carries its id ([ChannelIdentity.services]) and is
         * matched by that id (country null); a country preset fits channels of its country and is
         * matched by tvg-id or name. One combined guide per service (D10): Plex and Samsung TV Plus
         * "all", as Pluto's. Australia takes two guides (D7: 9 of 68 channels matched with one, 19
         * with both).
         */
        val GUIDE_PRESETS: List<GuidePreset> = listOf(
            GuidePreset("pluto", listOf(PLUTO_GUIDE), service = GuideService.PLUTO),
            GuidePreset(
                "plex",
                listOf(guideRecord("preset-plex", "Plex TV", "https://i.mjh.nz/Plex/all.xml.gz", null)),
                service = GuideService.PLEX
            ),
            GuidePreset(
                "samsung",
                listOf(guideRecord("preset-samsung", "Samsung TV Plus", "https://i.mjh.nz/SamsungTVPlus/all.xml.gz", null)),
                service = GuideService.SAMSUNG
            ),
            GuidePreset("in", INDIA_GUIDES, country = "in"),
            GuidePreset(
                "uk",
                listOf(openEpg("uk", "unitedkingdom", "United Kingdom")),
                country = "uk"
            ),
            GuidePreset("ca", listOf(openEpg("ca", "canada", "Canada")), country = "ca"),
            GuidePreset(
                "au",
                listOf(
                    openEpg("au", "australia", "Australia"),
                    guideRecord("preset-mjh-au", "i.mjh.nz Australia", "https://i.mjh.nz/au/all/epg.xml.gz", "au")
                ),
                country = "au"
            ),
            GuidePreset("nz", listOf(openEpg("nz", "newzealand", "New Zealand")), country = "nz")
        )

        private fun openEpg(country: String, file: String, countryName: String) = guideRecord(
            "preset-open-epg-$country",
            "open-epg $countryName",
            "https://www.open-epg.com/files/$file.xml.gz",
            country
        )

        private fun guideRecord(id: String, name: String, url: String, country: String?) =
            LiveSource(id = id, kind = SourceKind.XMLTV, name = name, url = url, addedAtMs = 0, country = country)

        /** [results] of streams [pack] still holds: a refresh keeps results by URL and drops the rest. */
        private fun forPack(results: Map<String, PackScanResult>, pack: List<PackChannel>): Map<String, PackScanResult> {
            if (results.isEmpty()) return results
            val keys = pack.flatMapTo(HashSet()) { ch -> ch.streams.map { ChannelPack.scanKey(it.url) } }
            return results.filterKeys { it in keys }
        }

        /** [packSummary]'s bound: it runs as the empty state opens, so a slow answer just shows no figures. */
        const val SUMMARY_TIMEOUT_MS = 10_000L

        /** `monitor/pack.json` is four numbers; anything near this is not it. */
        private const val SUMMARY_MAX_BYTES = 4 * 1024

        /** [body] as UTF-8 text; throws [SourceFetchException] past [max] bytes. */
        private fun readCapped(body: InputStream, max: Int): String {
            // readNBytes needs API 33 on Android (minSdk 24).
            val bytes = ByteArray(max + 1)
            var n = 0
            while (n < bytes.size) {
                val r = body.read(bytes, n, bytes.size - n)
                if (r < 0) break
                n += r
            }
            if (n > max) throw SourceFetchException("Larger than expected")
            return String(bytes, 0, n, Charsets.UTF_8)
        }

        /** Play-time stream lookup bound for a Stremio channel. */
        const val STREAM_LOOKUP_MS = 10_000L

        /** Any source: refetch after a day. */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** Guides: a cut-short snapshot is retried this long after its refresh. */
        const val GUIDE_MARGIN_MS = 60L * 60 * 1000

        private const val UNRECOGNISED = "Not a playlist, guide or Stremio addon"
        private const val ALREADY_ADDED = "Already added"
        const val XTREAM_FILL_IN = "Fill in server, username and password"
        /** A restored or damaged list holds the record but not its login (logins stay off backups, Spec 109 Q3). */
        const val XTREAM_LOGIN_MISSING = "Login not on this TV; use Edit login"
        /** Edit login with the password left unchanged and a new server: the stored one goes only to its own server. */
        private const val NOT_WEB_ADDRESS = "Not a web address"
        const val XTREAM_RETYPE_PASSWORD = "Type the password again for the new server"
        /** An edit of a source removed meanwhile. */
        const val SOURCE_GONE = "Source removed"
        /** A guide refresh that kept part of a cut-short file (Sources shows it as partial, not failed). */
        internal const val GUIDE_INCOMPLETE = "Guide file incomplete; kept what was read"
        private const val TAG = "LiveSourceManager"
        private val COUNTRY_STEM = Regex("""(?:^|[_.-])([A-Za-z]{2})\d?$""")

        /** Spec 55 D4: a header naming this many guides or fewer offers them all, no country check. */
        const val FEW_HEADER_GUIDES = 3

        /**
         * Header guides at or under this size are offered: what a slow 2 Mbit/s line fetches within
         * [SourceFetcher.CALL_TIMEOUT_MINUTES]; a gz guide inflates ~7x (epgshare01 PL1: 9 -> 63 MB,
         * 2026-11-08), so this also keeps under [XmltvNowNextParser.MAX_INPUT_BYTES].
         */
        const val MAX_GUIDE_DOWNLOAD_BYTES = 50L * 1024 * 1024

        private const val PROBE_PARALLEL = 8

        /** Header files that hold every country or placeholder channels (epgshare01's ALL_SOURCES1, DUMMY_CHANNELS). */
        private val CATCH_ALL = Regex("all_sources|dummy", RegexOption.IGNORE_CASE)

        /**
         * Which of a playlist header's [epgUrls] Sources may offer (Spec 55 D4/D5), before the live
         * probe: web addresses only, catch-alls dropped. 3 or fewer left: each alone, byCountry
         * false. More: one group per country of [channels] that a file name gives (`_UK1` -> uk),
         * its files in header order, the country with most channels first (header order on ties),
         * byCountry true. Null when nothing is left.
         */
        internal fun headerCandidates(epgUrls: List<String>, channels: List<Channel>): Pair<List<List<String>>, Boolean>? {
            val usable = epgUrls.distinct().filter { url ->
                val http = url.toHttpUrlOrNull() ?: return@filter false
                !CATCH_ALL.containsMatchIn(http.pathSegments.lastOrNull().orEmpty())
            }
            if (usable.isEmpty()) return null
            if (usable.size <= FEW_HEADER_GUIDES) return usable.map { listOf(it) } to false
            val perCountry = channels.mapNotNull { ChannelIdentity.normalizeCountry(it.country) }.groupingBy { it }.eachCount()
            val groups = usable.groupBy { guessCountry(it.toHttpUrl()) }
                .filterKeys { it != null && it in perCountry }
                .entries
                .sortedByDescending { perCountry.getValue(it.key!!) }
                .map { it.value }
            return groups.takeIf { it.isNotEmpty() }?.let { it to true }
        }

        /**
         * Per-channel programme cap for the day window (Spec 67 G1c): G1a measured at most 201 in
         * 48 h (free-epg.de), so 300 is a 10-minute average slot.
         */
        const val DAY_WINDOW_MAX_PROGRAMMES = 300

        /** The cap's average slot (300 over 48 h), which [dayWindowCap] keeps for the look-back hours too. */
        private const val CAP_SLOT_MS = 10 * 60_000L

        /**
         * Spec 92 W2: how far before local midnight a guide parse starts, the largest positive guide shift
         * among [channels] (`tvg-shift`, [GuideShift.of] with each source's header shift), 0 for none. A
         * channel shifted +N h shows the guide's programme from N h ago, so after local midnight its
         * current programme is yesterday's in guide time; without these hours it showed nothing. At most
         * [GuideShift]'s 24 h. Read at parse time: a shift added later counts from the next guide refresh.
         */
        internal fun guideLookBackMs(channels: List<Channel>, sources: List<LiveSource>): Long {
            val headers = GuideShift.headerShifts(sources.associate { it.id to it.playlistAttrs })
            return channels.maxOfOrNull { GuideShift.of(it, headers[it.sourceId] ?: 0L) }?.coerceAtLeast(0L) ?: 0L
        }

        /** Where a guide parse's window starts: [dayStartMs] ([dayWindow]'s start) less [lookBackMs]. */
        internal fun guideWindowStart(dayStartMs: Long, lookBackMs: Long): Long = dayStartMs - lookBackMs

        /** The per-channel cap for a day window started [lookBackMs] early: same average slot over the extra hours. */
        internal fun dayWindowCap(lookBackMs: Long): Int =
            DAY_WINDOW_MAX_PROGRAMMES + ((lookBackMs + CAP_SLOT_MS - 1) / CAP_SLOT_MS).toInt()

        /**
         * Today and tomorrow (Spec 67 G1c): [local midnight today, local midnight of the day after tomorrow) in
         * [zone], as calendar days (a DST day is not cut or stretched by an hour). A guide parse keeps this window
         * started [guideLookBackMs] earlier (Spec 92 W2).
         */
        internal fun dayWindow(nowMs: Long, zone: ZoneId): Pair<Long, Long> =
            localDayStartMs(nowMs, zone) to localDayStartMs(nowMs, zone, 2)

        /**
         * [partial] (a cut-short parse) filled from [old], the cached snapshot: [old]'s programmes
         * that have not ended by [partial]'s window start, for channels [partial] holds none for and
         * after the end of a channel's cut-off list; [old]'s channel entries only for channels the
         * result holds programmes for. [partial]'s own programmes and entries win. Programmes ended
         * before the window start never carry over, so across days of cut-short files a list holds at
         * most the look-back hours of the past (the matcher's live pass skips those, [EpgSnapshot.isLive]),
         * and a channel the guide dropped goes at the first refresh whose window starts after its last
         * programme ends.
         */
        internal fun fillFrom(partial: EpgSnapshot, old: EpgSnapshot): EpgSnapshot {
            val current = old.programmes
                .mapValues { (_, list) -> list.filter { it.stopMs > partial.windowStartMs } }
                .filterValues { it.isNotEmpty() }
            // The parser drops empty lists, so maxOf has an element.
            val extended = partial.programmes.mapValues { (id, list) ->
                val end = list.maxOf { it.stopMs }
                list + current[id].orEmpty().filter { it.startMs >= end }
            }
            val programmes = current + extended
            return partial.copy(channels = old.channels.filterKeys { it in programmes } + partial.channels, programmes = programmes)
        }

        /**
         * Whether [snapshot] reaches the end of tomorrow at [nowMs] in [zone]. False after local
         * midnight for yesterday's window, for a pre-G1c 6 h snapshot, and after a zone change west.
         * A refresh writes exactly [dayWindow]'s end, so a fresh guide never fails it.
         */
        private fun coversDays(snapshot: EpgSnapshot, nowMs: Long, zone: ZoneId): Boolean =
            snapshot.windowEndMs >= dayWindow(nowMs, zone).second

        /**
         * Stale = never refreshed, or older than [MAX_AGE_MS]; a guide also when its snapshot is
         * missing, no longer reaches the end of tomorrow ([coversDays]: the first Live TV open after
         * local midnight refetches), or was cut short and is [GUIDE_MARGIN_MS] old (retried hourly,
         * not on every open).
         */
        internal fun isStale(source: LiveSource, snapshot: EpgSnapshot?, nowMs: Long, zone: ZoneId): Boolean {
            val last = source.lastRefreshMs ?: return true
            return when (source.kind) {
                SourceKind.M3U, SourceKind.STREMIO, SourceKind.PACK -> nowMs - last >= MAX_AGE_MS
                // Its guide, once one is cached, must cover today and tomorrow, retried hourly (a failing
                // xmltv.php keeps the old guide, and each retry relists the channels); none cached (the
                // server serves none) is no reason to refetch.
                SourceKind.XTREAM -> nowMs - last >= MAX_AGE_MS ||
                    (snapshot != null && !coversDays(snapshot, nowMs, zone) && nowMs - last >= GUIDE_MARGIN_MS)
                SourceKind.XMLTV -> snapshot == null || nowMs - last >= MAX_AGE_MS ||
                    !coversDays(snapshot, nowMs, zone) ||
                    (!snapshot.complete && nowMs - last >= GUIDE_MARGIN_MS)
            }
        }

        /**
         * Country of a guide from its file name (`epg_IN.xml.gz`, `in.xml`, epgshare01's
         * `epg_ripper_UK1.xml.gz`); null when none reads as one.
         */
        internal fun guessCountry(url: HttpUrl): String? {
            val stem = url.pathSegments.lastOrNull().orEmpty()
                .removeSuffix(".gz").removeSuffix(".xml")
            return COUNTRY_STEM.find(stem)?.groupValues?.get(1)?.let(ChannelIdentity::normalizeCountry)
        }
    }
}

/**
 * One guide suggestion Sources offers (Spec 55 S2): [key] is stable, [guides] the records adding
 * it stores; it fits channels of [service] (by stream URL id) or of [country] (exactly one is set).
 */
data class GuidePreset(
    val key: String,
    val guides: List<LiveSource>,
    val service: GuideService? = null,
    val country: String? = null
) {
    init {
        require((service == null) != (country == null)) { "A preset fits a service or a country" }
    }
}
