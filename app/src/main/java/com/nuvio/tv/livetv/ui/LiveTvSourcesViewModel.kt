package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.PackScanRecord
import com.nuvio.tv.livetv.source.PackScanStore
import com.nuvio.tv.livetv.source.PackScanner
import com.nuvio.tv.livetv.source.PackStore
import com.nuvio.tv.livetv.source.SourceKind
import com.nuvio.tv.livetv.source.StremioTvAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Sources route draws (D16 stage 14). */
data class LiveTvSourcesUiState(
    val loading: Boolean = true,
    val rows: List<SourceRow> = emptyList(),
    val channelSources: Int = 0,
    val guides: Int = 0,
    /** When the iptv-org channel data was fetched; null = never (no DATA row). */
    val metadataFetchedAtMs: Long? = null,
    val hidden: List<HiddenRow> = emptyList(),
    /**
     * Spec 55 S2: the guide presets offered above the sources, most channels first (Pluto's and
     * India's rows among them); more than [LiveTvSourcesModel.FOLD_PRESETS] show as one row (D11).
     */
    val presets: List<PresetSuggestion> = emptyList(),
    /** Spec 55 S1: playlists' header guides not yet added (SUGGESTED rows and Choose guides). */
    val headers: List<HeaderSuggestion> = emptyList()
)

/** The Add source panel's tabs (Spec 109 D4 Q1): a URL, or an Xtream login. */
enum class AddTab { LINK, XTREAM }

/** The Xtream login tab's fields as typed (in memory only, never saved state: the password is one). */
data class XtreamFields(val server: String = "", val username: String = "", val password: String = "") {
    override fun toString(): String = "XtreamFields(server=$server)"
}

/**
 * Edit login's form (Spec 109 S2.6, stage 10, D13) for Xtream source [sourceId]: [initial] as it
 * opened (server, stored username, empty password), [fields] as typed. [stored] = a login is on
 * this TV, so an empty password keeps it ("unchanged"); none (a restored list) opens with the
 * username and password empty and needs both typed. In memory only, as [XtreamFields].
 */
data class EditLogin(val sourceId: String, val initial: XtreamFields, val fields: XtreamFields, val stored: Boolean) {
    /** Save with nothing changed only closes (D13 c). */
    val changed: Boolean get() = fields != initial
}

/** The Add source dialog's check (stage 15), shared by Edit login: Add / Save is enabled only in [Ready]. */
sealed interface AddCheck {
    data object Idle : AddCheck
    data object Checking : AddCheck
    data class Ready(val checked: LiveSourceManager.Checked) : AddCheck
    data class Refused(val reason: String) : AddCheck
    data object Adding : AddCheck
}

/** One-shot outcomes the route shows as a toast; [Added] also moves focus to the new row. */
sealed interface SourcesEvent {
    data class Added(val id: String, val name: String) : SourcesEvent
    /** Edit login saved [id]'s login: the panel closes, focus back on its row. */
    data class Edited(val id: String) : SourcesEvent
    data object RenameFailed : SourcesEvent
    data object RemoveFailed : SourcesEvent
    data object UnhideFailed : SourcesEvent
    /** A preset guide record could not be saved. */
    data object PresetAddFailed : SourcesEvent
    /** A header guide record could not be saved. */
    data object HeaderAddFailed : SourcesEvent
    /** Add pack failed (Spec 73 D6); [reason] is the manager's user-facing refusal. */
    data class PackAddFailed(val reason: String) : SourcesEvent
    /**
     * A pack scan ended (done or cancelled) while this route is open: its result panel (Spec 73
     * stages 7, 8; D12 Q3: only over Sources, nothing elsewhere).
     */
    data class ScanEnded(val record: PackScanRecord) : SourcesEvent
}

/**
 * The Sources route (Spec 34 L7e): the source list with what each gave and its health, Refresh
 * (one or all), Rename, Remove, unhide, and the Add source dialog's check-then-add. Refreshes run
 * one at a time per source in this ViewModel's scope (leaving the route cancels them; the next
 * open's stale refresh picks them up). The Live TV screen sees every change through the shared
 * stores.
 */
@HiltViewModel
class LiveTvSourcesViewModel @Inject constructor(
    private val sourceManager: LiveSourceManager,
    channelRepository: ChannelRepository,
    guideRepository: GuideRepository,
    metadataRepository: IptvMetadataRepository,
    private val hiddenStore: HiddenChannelStore,
    private val scanner: PackScanner,
    private val packStore: PackStore,
    private val scanStore: PackScanStore
) : ViewModel() {

    private val loaded = MutableStateFlow(false)
    private val refreshing = MutableStateFlow<Set<String>>(emptySet())
    private var refreshAllJob: Job? = null
    /** Presets whose records are being written (a second press on the same row would toast Added twice). */
    private val presetsAdding = MutableStateFlow<Set<String>>(emptySet())
    private var headerJob: Job? = null

    private val _addUrl = MutableStateFlow("")
    val addUrl: StateFlow<String> = _addUrl.asStateFlow()

    /**
     * The typed link is already a stored source (as [LiveSourceManager.check] compares it): a pasted
     * link that passed as an Xtream login then offers the login only, no Add as playlist (D25).
     */
    val linkStored: StateFlow<Boolean> = combine(_addUrl, sourceManager.sources) { url, sources ->
        val link = StremioTvAdapter.normalizeUrl(url)
        link.isNotEmpty() && sources.any { it.url == link }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)
    private val _addTab = MutableStateFlow(AddTab.LINK)
    val addTab: StateFlow<AddTab> = _addTab.asStateFlow()
    private val _xtream = MutableStateFlow(XtreamFields())
    val xtreamFields: StateFlow<XtreamFields> = _xtream.asStateFlow()
    /** One dialog's check-then-store state and the job driving it: Add source's and Edit login's are apart. */
    private class CheckSlot {
        val state = MutableStateFlow<AddCheck>(AddCheck.Idle)
        var job: Job? = null
    }

    private val add = CheckSlot()
    val addCheck: StateFlow<AddCheck> = add.state.asStateFlow()
    private val editing = CheckSlot()
    val editCheck: StateFlow<AddCheck> = editing.state.asStateFlow()
    /** A Save press's run (its check, then the save): a second press while it runs does nothing. */
    private var saveJob: Job? = null
    private val _edit = MutableStateFlow<EditLogin?>(null)
    val editLogin: StateFlow<EditLogin?> = _edit.asStateFlow()

    private val events = Channel<SourcesEvent>(Channel.BUFFERED)
    val event: Flow<SourcesEvent> = events.receiveAsFlow()

    private val rows = combine(
        sourceManager.sources,
        channelRepository.channels,
        guideRepository.guides,
        refreshing
    ) { sources, channels, guides, busy -> LiveTvSourcesModel.rows(sources, channels, guides, busy) }

    // Hidden and suggestions read no refresh status: a refresh's record writes must not re-derive them
    // over every channel (Spec 115 S3).
    private val sourcesSansStatus = sourceManager.sources.map(LiveTvSourcesModel::withoutStatus).distinctUntilChanged()

    private val hidden = combine(hiddenStore.keys, channelRepository.channels, sourcesSansStatus) { keys, channels, sources ->
        LiveTvSourcesModel.hidden(keys, channels, sources)
    }

    // Their own flow: a refresh starting or ending must not rescan every stream URL.
    private val suggestions = combine(sourcesSansStatus, channelRepository.channels) { sources, channels ->
        Suggestions(
            LiveTvSourcesModel.presetSuggestions(sources, channels),
            LiveTvSourcesModel.headerSuggestions(sources, channels)
        )
    }

    val uiState: StateFlow<LiveTvSourcesUiState> = combine(
        loaded,
        rows,
        hidden,
        metadataRepository.metadata,
        suggestions
    ) { loaded, rows, hidden, metadata, suggested ->
        LiveTvSourcesUiState(
            loading = !loaded,
            rows = rows,
            channelSources = rows.count { it.source.kind != SourceKind.XMLTV },
            guides = rows.count { it.source.kind == SourceKind.XMLTV },
            metadataFetchedAtMs = metadata?.fetchedAtMs,
            hidden = hidden,
            presets = suggested.presets,
            headers = suggested.headers
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LiveTvSourcesUiState())

    private val packState = MutableStateFlow(PackOffer())

    /**
     * The SUGGESTED channel pack row (Spec 73 D6): null while the pack is added (or the list is
     * loading); its figures are read whenever it is offered and not read yet (also after a Remove), none when they cannot be read.
     */
    val packOffer: StateFlow<PackOffer?> = combine(loaded, sourceManager.sources, packState) { loaded, sources, offer ->
        offer.takeIf { loaded && sources.none { it.kind == SourceKind.PACK } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The pack's scan, running or not (Spec 73 S3b: the row's SCANNING n% / PAUSED, the progress panel). */
    val scan: StateFlow<PackScanner.State> = scanner.state

    /**
     * The stored pack and scans as the scan UI reads them (country list, the row's last scan and
     * waiting count); null while no pack is added, before the first read, and while the pack's
     * file is not written yet (an add stores the record first: the country list waits). Read
     * again when a pack download completes and when a scan starts or ends (a running scan's counts
     * come from [scan]).
     */
    val scanFacts: StateFlow<PackScanFacts?> = packScanFacts(
        sourceManager.sources,
        sourceManager.packSaves,
        scanner.state.map { it is PackScanner.State.Running }
    ) {
        val scans = scanStore.read()
        PackScanModel.facts(packStore.read(), scans.results, scans.last, thisTvRegion())
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch { scanner.ended.collect { events.send(SourcesEvent.ScanEnded(it)) } }
        viewModelScope.launch {
            hiddenStore.load()
            sourceManager.load()
            loaded.value = true
            // Whenever the pack is not added (also after a Remove here) and its figures are not read yet.
            sourceManager.sources
                .map { list -> list.none { it.kind == SourceKind.PACK } }
                .distinctUntilChanged()
                .collect { offered ->
                    if (offered && packState.value.summary == null) {
                        val summary = sourceManager.packSummary()
                        packState.update { it.copy(summary = summary) }
                    }
                }
        }
    }

    /**
     * Sources' "Add pack" (Spec 73 D6): downloads and adds the pack; Added moves focus to its new
     * row, a failure toasts its reason. A second press while it runs does nothing.
     */
    fun addPack() {
        if (packState.value.adding) return
        packState.update { it.copy(adding = true) }
        viewModelScope.launch {
            try {
                when (val result = sourceManager.addPack()) {
                    is LiveSourceManager.AddResult.Added -> events.send(SourcesEvent.Added(result.source.id, result.source.name))
                    is LiveSourceManager.AddResult.Refused -> events.send(SourcesEvent.PackAddFailed(result.reason))
                }
            } finally {
                packState.update { it.copy(adding = false) }
            }
        }
    }

    /** Scans the pack's channels of [countries] (Spec 73 D4); false when a scan runs already. */
    fun startScan(countries: Set<String?>): Boolean = scanner.start(countries)

    /** Cancel scan (D5): stops the scan, keeping what it checked. */
    fun cancelScan() = scanner.cancel()

    fun refresh(id: String) {
        if (id in refreshing.value) return
        viewModelScope.launch { refreshNow(id) }
    }

    private fun refreshGuide(id: String) {
        if (id in refreshing.value) return
        viewModelScope.launch { refreshNow(id, guideOnly = true) }
    }

    /** Refresh all: every source, one after another (each takes the manager's lock on its own). */
    fun refreshAll() {
        if (refreshAllJob?.isActive == true) return
        refreshAllJob = viewModelScope.launch {
            for (id in sourceManager.sources.value.map { it.id }) {
                if (id !in refreshing.value) refreshNow(id)
            }
        }
    }

    /** [guideOnly]: an Xtream login's guide alone ([LiveSourceManager.refreshGuide]), shown as Refreshing too. */
    private suspend fun refreshNow(id: String, guideOnly: Boolean = false) {
        refreshing.update { it + id }
        try {
            if (guideOnly) sourceManager.refreshGuide(id) else sourceManager.refresh(id)
        } finally {
            refreshing.update { it - id }
        }
    }

    /**
     * Spec 55 S2 "Add guide(s)" on a preset row, or Choose guides' "Add N guides": stores the guide
     * records of the offered presets among [keys], then fetches each ([refresh], so one guide's
     * download never holds up the next round). A preset whose write is still running is skipped,
     * so a second press on its row cannot toast Added twice; another row's press still adds.
     */
    fun addPresets(keys: Collection<String>) {
        val busy = presetsAdding.value
        val presets = uiState.value.presets.map { it.preset }.filter { it.key in keys && it.key !in busy }
        if (presets.isEmpty()) return
        val adding = presets.mapTo(HashSet()) { it.key }
        presetsAdding.update { it + adding }
        viewModelScope.launch {
            try {
                val guides = sourceManager.addPresetGuides(presets)
                if (guides == null) {
                    events.send(SourcesEvent.PresetAddFailed)
                    return@launch
                }
                events.send(SourcesEvent.Added(guides.first().id, guides.first().name))
                guides.forEach { refresh(it.id) }
            } finally {
                presetsAdding.update { it - adding }
            }
        }
    }

    /**
     * Spec 55 S1 "Add guides" / Choose guides' "Add N guides": stores a record for each of [urls]
     * among playlist [sourceId]'s offered header guides, named as offered, then fetches them here.
     * The guard covers the record write only: each fetch runs on its own ([refresh]), so a second
     * Choose guides round is not held up by the first round's downloads.
     */
    fun addHeaderGuides(sourceId: String, urls: Collection<String>) {
        if (headerJob?.isActive == true) return
        val offered = uiState.value.headers.firstOrNull { it.sourceId == sourceId }?.guides.orEmpty()
        val named = offered.filter { it.url in urls }.map { it.url to it.name }
        if (named.isEmpty()) return
        headerJob = viewModelScope.launch {
            val guides = sourceManager.addHeaderGuides(named)
            if (guides == null) {
                events.send(SourcesEvent.HeaderAddFailed)
                return@launch
            }
            events.send(SourcesEvent.Added(guides.first().id, guides.first().name))
            guides.forEach { refresh(it.id) }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            if (!sourceManager.rename(id, name)) events.send(SourcesEvent.RenameFailed)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            if (!sourceManager.remove(id)) events.send(SourcesEvent.RemoveFailed)
        }
    }

    fun unhide(key: String) {
        viewModelScope.launch {
            if (!hiddenStore.setHidden(key, false)) events.send(SourcesEvent.UnhideFailed)
        }
    }

    /** The dialog opened: on Link, empty fields, nothing checked (an add still saving finishes first). */
    fun openAdd() {
        if (resetCheck()) {
            _addUrl.value = ""
            _xtream.value = XtreamFields()
            _addTab.value = AddTab.LINK
        }
    }

    /** Another tab: its own fields stay; any check no longer applies (ignored while an add saves). */
    fun selectAddTab(tab: AddTab) {
        if (tab == _addTab.value || add.state.value == AddCheck.Adding) return
        _addTab.value = tab
        resetCheck()
    }

    /** An Xtream field changed: any check of the old login no longer applies (ignored while an add saves). */
    fun onXtreamChange(fields: XtreamFields) {
        if (fields == _xtream.value || add.state.value == AddCheck.Adding) return
        _xtream.value = fields
        resetCheck()
    }

    /** Signs in with the typed login (Password's IME Done, Spec 109 S1): Add is enabled once it passes. */
    fun checkXtream() {
        val fields = _xtream.value
        runCheck { sourceManager.checkXtream(fields.server, fields.username, fields.password) }
    }

    /** The URL changed: any check of the old text no longer applies (ignored while an add saves). */
    fun onAddUrlChange(url: String) {
        if (url == _addUrl.value || add.state.value == AddCheck.Adding) return
        _addUrl.value = url
        resetCheck()
    }

    /**
     * The dialog closed: a check still running is dropped with its data; an add still saving
     * finishes (it holds its own copy of the login). The typed login goes either way: the password
     * is not kept past the panel.
     */
    fun closeAdd() {
        _xtream.value = XtreamFields()
        resetCheck()
    }

    /** Drops [slot]'s check (running or passed) back to Idle; false, doing nothing, while it saves. */
    private fun resetCheck(slot: CheckSlot = add): Boolean {
        if (slot.state.value == AddCheck.Adding) return false
        slot.job?.cancel()
        slot.state.value = AddCheck.Idle
        return true
    }

    /**
     * Checks the typed URL (IME Done, D16): Add is enabled once it passes. A `get.php` link with a
     * login signs in as Xtream first ([LiveSourceManager.checkLink], Spec 109 D14).
     */
    fun checkAdd() {
        val url = _addUrl.value.trim()
        if (url.isEmpty()) return
        runCheck { sourceManager.checkLink(url) }
    }

    /**
     * Add as playlist (stage 11), offered while a pasted link passed as an Xtream login: checks the
     * URL as a playlist, then adds it, in one press. The check can be dropped as any check (closing
     * or editing); its refusal stays on the check line.
     */
    fun addAsPlaylist() {
        val ready = add.state.value as? AddCheck.Ready ?: return
        if (_addTab.value != AddTab.LINK || ready.checked.source.kind != SourceKind.XTREAM) return
        val url = _addUrl.value.trim()
        add.state.value = AddCheck.Checking
        add.job = viewModelScope.launch {
            when (val result = sourceManager.check(url)) {
                is LiveSourceManager.CheckResult.Refused -> add.state.value = AddCheck.Refused(result.reason)
                is LiveSourceManager.CheckResult.Ready -> {
                    add.state.value = AddCheck.Ready(result.checked)
                    confirmAdd()
                }
            }
        }
    }

    /** Runs [check] in [slot] unless one runs or passed already (Idle or Refused only); Add is enabled once it passes. */
    private fun runCheck(slot: CheckSlot = add, check: suspend () -> LiveSourceManager.CheckResult) {
        val state = slot.state.value
        if (state != AddCheck.Idle && state !is AddCheck.Refused) return
        slot.state.value = AddCheck.Checking
        slot.job = viewModelScope.launch {
            slot.state.value = when (val result = check()) {
                is LiveSourceManager.CheckResult.Refused -> AddCheck.Refused(result.reason)
                is LiveSourceManager.CheckResult.Ready -> AddCheck.Ready(result.checked)
            }
        }
    }

    /** Add (enabled only after a passed check): stores it; the route closes the dialog on [SourcesEvent.Added]. */
    fun confirmAdd() {
        val pending = (add.state.value as? AddCheck.Ready)?.checked ?: return
        add.state.value = AddCheck.Adding
        add.job = viewModelScope.launch {
            add.state.value = when (val result = sourceManager.add(pending)) {
                is LiveSourceManager.AddResult.Added -> {
                    _addUrl.value = ""
                    _xtream.value = XtreamFields()
                    events.send(SourcesEvent.Added(result.source.id, result.source.name))
                    // The login's guide is read now (the check stays quick and listed the channels; Spec 109 S2).
                    if (result.source.kind == SourceKind.XTREAM) refreshGuide(result.source.id)
                    AddCheck.Idle
                }
                is LiveSourceManager.AddResult.Refused -> AddCheck.Refused(result.reason)
            }
        }
    }

    /**
     * Opens Edit login on Xtream source [id] (D13 a): its server, and its stored username with the
     * password marked unchanged; with no login on this TV, both empty. Ignored while an earlier
     * edit's save runs; [closeEdit] drops a load still running (another panel opened first).
     */
    fun openEdit(id: String) {
        val source = sourceManager.sources.value.firstOrNull { it.id == id && it.kind == SourceKind.XTREAM } ?: return
        if (!resetEdit()) return
        editing.job = viewModelScope.launch {
            val username = sourceManager.xtreamUsername(id)
            val fields = XtreamFields(server = source.url, username = username.orEmpty())
            _edit.value = EditLogin(id, fields, fields, stored = username != null)
        }
    }

    /** An Edit login field changed: any check (and a Save waiting on it) no longer applies; ignored while a save runs. */
    fun onEditChange(fields: XtreamFields) {
        val edit = _edit.value ?: return
        if (fields == edit.fields || editing.state.value == AddCheck.Adding) return
        _edit.value = edit.copy(fields = fields)
        resetEdit()
    }

    /** Done on a changed field (stage 10): signs in with the edited login, storing nothing. */
    fun checkEdit() {
        val edit = _edit.value ?: return
        runCheck(editing) { checkEdit(edit) }
    }

    /** An empty password keeps the stored one (the manager reads it; this screen never holds it). */
    private suspend fun checkEdit(edit: EditLogin) = edit.fields.let {
        sourceManager.checkXtreamEdit(edit.sourceId, it.server, it.username, it.password.ifEmpty { null })
    }

    /**
     * Save (D13): nothing changed closes; a passed check saves; otherwise one press checks, then
     * saves (a check Done started is waited for, not repeated). Presses while a Save runs do
     * nothing. A refusal stays on the check line. Saved: the panel closes, focus goes back to its
     * row ([SourcesEvent.Edited]) and the login's guide is read ([LiveSourceManager.refreshGuide]).
     * A save that outlives its panel ([closeEdit]) still finishes, quietly.
     */
    fun saveEdit() {
        val edit = _edit.value ?: return
        if (saveJob?.isActive == true || editing.state.value == AddCheck.Adding) return
        if (!edit.changed) {
            closeEdit()
            return
        }
        if (editing.state.value != AddCheck.Checking) runCheck(editing) { checkEdit(edit) }
        val check = editing.job
        saveJob = viewModelScope.launch {
            check?.join()
            val checked = (editing.state.value as? AddCheck.Ready)?.checked ?: return@launch
            editing.state.value = AddCheck.Adding
            val result = sourceManager.saveEdit(checked)
            val open = _edit.value?.sourceId == edit.sourceId
            editing.state.value = when (result) {
                is LiveSourceManager.AddResult.Added -> {
                    if (open) {
                        _edit.value = null
                        events.send(SourcesEvent.Edited(edit.sourceId))
                    }
                    refreshGuide(edit.sourceId)
                    AddCheck.Idle
                }
                is LiveSourceManager.AddResult.Refused -> if (open) AddCheck.Refused(result.reason) else AddCheck.Idle
            }
        }
    }

    /** Edit login closed (or another panel opened): the typed login goes; a save already storing finishes. */
    fun closeEdit() {
        _edit.value = null
        resetEdit()
    }

    /** [resetCheck] for Edit login, with a Save waiting on the dropped check; false while a save stores. */
    private fun resetEdit(): Boolean {
        if (editing.state.value == AddCheck.Adding) return false
        saveJob?.cancel()
        return resetCheck(editing)
    }

    private data class Suggestions(
        val presets: List<PresetSuggestion>,
        val headers: List<HeaderSuggestion>
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** This TV's region from its time zone (D4: THIS TV); null for a zone with none (UTC: "001"). */
        fun thisTvRegion(): String? = try {
            android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().id)
                .takeIf { it.length == 2 && it.all(Char::isLetter) }
        } catch (_: RuntimeException) {
            null // an unknown zone id; on the JVM (unit tests) the android.icu stub
        }
    }
}

/**
 * [LiveTvSourcesViewModel.scanFacts]' triggers (Spec 73 S3c): [read] runs when the pack's record
 * comes or goes, when a pack download completes ([packSaves], counted once its file is written:
 * an add writes the record first), and when a scan starts or ends; null while no record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun packScanFacts(
    sources: Flow<List<LiveSource>>,
    packSaves: Flow<Int>,
    running: Flow<Boolean>,
    read: suspend () -> PackScanFacts?
): Flow<PackScanFacts?> = combine(
    sources.map { list -> list.any { it.kind == SourceKind.PACK } }.distinctUntilChanged(),
    packSaves,
    running.distinctUntilChanged()
) { added, saves, scanning -> if (added) saves to scanning else null }
    .mapLatest { key -> key?.let { read() } }
