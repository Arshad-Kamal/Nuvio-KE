package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** Stage 6 (Spec 38 S2): the Pluto guide row above the sources; null = none. */
    val pluto: GuideSuggestion? = null,
    /** Spec 49 D27 F5: the India guides row above the sources; null = none. */
    val india: GuideSuggestion? = null
)

/** The Add source dialog's check (stage 15): Add is enabled only in [Ready]. */
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
    data object RenameFailed : SourcesEvent
    data object RemoveFailed : SourcesEvent
    data object UnhideFailed : SourcesEvent
    /** The Pluto guide record could not be saved. */
    data object PlutoAddFailed : SourcesEvent
    /** An India guide record could not be saved. */
    data object IndiaAddFailed : SourcesEvent
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
    private val hiddenStore: HiddenChannelStore
) : ViewModel() {

    private val loaded = MutableStateFlow(false)
    private val refreshing = MutableStateFlow<Set<String>>(emptySet())
    private var refreshAllJob: Job? = null
    private var plutoJob: Job? = null
    private var indiaJob: Job? = null

    private val _addUrl = MutableStateFlow("")
    val addUrl: StateFlow<String> = _addUrl.asStateFlow()
    private val _addCheck = MutableStateFlow<AddCheck>(AddCheck.Idle)
    val addCheck: StateFlow<AddCheck> = _addCheck.asStateFlow()
    private var addJob: Job? = null

    private val events = Channel<SourcesEvent>(Channel.BUFFERED)
    val event: Flow<SourcesEvent> = events.receiveAsFlow()

    private val rows = combine(
        sourceManager.sources,
        channelRepository.channels,
        guideRepository.guides,
        refreshing
    ) { sources, channels, guides, busy -> LiveTvSourcesModel.rows(sources, channels, guides, busy) }

    private val hidden = combine(hiddenStore.keys, channelRepository.channels, sourceManager.sources) { keys, channels, sources ->
        LiveTvSourcesModel.hidden(keys, channels, sources)
    }

    // Their own flow: a refresh starting or ending must not rescan every stream URL.
    private val suggestions = combine(sourceManager.sources, channelRepository.channels) { sources, channels ->
        LiveTvSourcesModel.plutoSuggestion(sources, channels) to LiveTvSourcesModel.indiaSuggestion(sources, channels)
    }

    val uiState: StateFlow<LiveTvSourcesUiState> = combine(
        loaded,
        rows,
        hidden,
        metadataRepository.metadata,
        suggestions
    ) { loaded, rows, hidden, metadata, (pluto, india) ->
        LiveTvSourcesUiState(
            loading = !loaded,
            rows = rows,
            channelSources = rows.count { it.source.kind != SourceKind.XMLTV },
            guides = rows.count { it.source.kind == SourceKind.XMLTV },
            metadataFetchedAtMs = metadata?.fetchedAtMs,
            hidden = hidden,
            pluto = pluto,
            india = india
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LiveTvSourcesUiState())

    init {
        viewModelScope.launch {
            hiddenStore.load()
            sourceManager.load()
            loaded.value = true
        }
    }

    fun refresh(id: String) {
        if (id in refreshing.value) return
        viewModelScope.launch { refreshNow(id) }
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

    private suspend fun refreshNow(id: String) {
        refreshing.update { it + id }
        try {
            sourceManager.refresh(id)
        } finally {
            refreshing.update { it - id }
        }
    }

    /** Stage 6 "Add guide": stores the Pluto guide record, then fetches it here (its row shows Refreshing). */
    fun addPlutoGuide() {
        // A second press before the row leaves would toast Added twice.
        if (plutoJob?.isActive == true) return
        plutoJob = viewModelScope.launch {
            val guide = sourceManager.addPlutoGuide()
            if (guide == null) {
                events.send(SourcesEvent.PlutoAddFailed)
                return@launch
            }
            events.send(SourcesEvent.Added(guide.id, guide.name))
            if (guide.id !in refreshing.value) refreshNow(guide.id)
        }
    }

    /** Spec 49 D27 F5 "Add guides": stores both India guide records, then fetches them here. */
    fun addIndiaGuides() {
        if (indiaJob?.isActive == true) return
        indiaJob = viewModelScope.launch {
            val guides = sourceManager.addIndiaGuides()
            if (guides == null) {
                events.send(SourcesEvent.IndiaAddFailed)
                return@launch
            }
            events.send(SourcesEvent.Added(guides.first().id, guides.first().name))
            for (guide in guides) {
                if (guide.id !in refreshing.value) refreshNow(guide.id)
            }
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

    /** The dialog opened: an empty field, nothing checked (an add still saving finishes first). */
    fun openAdd() {
        if (resetCheck()) _addUrl.value = ""
    }

    /** The URL changed: any check of the old text no longer applies (ignored while an add saves). */
    fun onAddUrlChange(url: String) {
        if (url == _addUrl.value || _addCheck.value == AddCheck.Adding) return
        _addUrl.value = url
        resetCheck()
    }

    /** The dialog closed: a check still running is dropped with its data; an add still saving finishes. */
    fun closeAdd() {
        resetCheck()
    }

    /** Drops any check (running or passed) back to Idle; false, doing nothing, while an add saves. */
    private fun resetCheck(): Boolean {
        if (_addCheck.value == AddCheck.Adding) return false
        addJob?.cancel()
        _addCheck.value = AddCheck.Idle
        return true
    }

    /** Checks the typed URL (IME Done, D16): Add is enabled once it passes. */
    fun checkAdd() {
        val url = _addUrl.value.trim()
        val state = _addCheck.value
        if (url.isEmpty() || (state != AddCheck.Idle && state !is AddCheck.Refused)) return
        _addCheck.value = AddCheck.Checking
        addJob = viewModelScope.launch {
            _addCheck.value = when (val result = sourceManager.check(url)) {
                is LiveSourceManager.CheckResult.Refused -> AddCheck.Refused(result.reason)
                is LiveSourceManager.CheckResult.Ready -> AddCheck.Ready(result.checked)
            }
        }
    }

    /** Add (enabled only after a passed check): stores it; the route closes the dialog on [SourcesEvent.Added]. */
    fun confirmAdd() {
        val pending = (_addCheck.value as? AddCheck.Ready)?.checked ?: return
        _addCheck.value = AddCheck.Adding
        addJob = viewModelScope.launch {
            _addCheck.value = when (val result = sourceManager.add(pending)) {
                is LiveSourceManager.AddResult.Added -> {
                    _addUrl.value = ""
                    events.send(SourcesEvent.Added(result.source.id, result.source.name))
                    AddCheck.Idle
                }
                is LiveSourceManager.AddResult.Refused -> AddCheck.Refused(result.reason)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
