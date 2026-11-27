package com.nuvio.tv.ui.screens.settings.trackers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.repository.LibraryTransferMode
import com.nuvio.tv.data.repository.LibraryTransferPreview
import com.nuvio.tv.data.repository.LibraryTransferReadException
import com.nuvio.tv.data.repository.LibraryTransferService
import com.nuvio.tv.data.repository.LibraryTransferState
import com.nuvio.tv.data.repository.transferDestinations
import com.nuvio.tv.data.repository.transferSources
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Stage D1's plan: being read, read, or a read that failed (on [failedSource]). */
internal sealed interface TransferPlanUi {
    data object Loading : TransferPlanUi
    data class Ready(val preview: LibraryTransferPreview) : TransferPlanUi
    data class Failed(val failedSource: TrackingSource) : TransferPlanUi
}

internal data class TransferPanelUi(
    val from: TrackingSource = TrackingSource.NUVIO,
    val to: TrackingSource = TrackingSource.NUVIO,
    val sources: List<TrackingSource> = emptyList(),
    val destinations: List<TrackingSource> = emptyList(),
    val plan: TransferPlanUi = TransferPlanUi.Loading,
    val run: LibraryTransferState = LibraryTransferState.Idle,
    /** The Move confirm's every-device line shows only with a Nuvio account (round 2 3f6c2106). */
    val nuvioAccount: Boolean = false
)

/**
 * Settings › Trackers › Transfer library (Spec 104 S4d, sandbox `spec-104-trackers` v4 stages D1-D4). Picks From and To,
 * reads the plan for the active profile, and hands Copy or Move to [LibraryTransferService], which keeps running when
 * Settings closes; reopening shows its progress or result.
 */
@HiltViewModel
class LibraryTransferViewModel @Inject constructor(
    private val service: LibraryTransferService,
    private val registry: TrackingProviderRegistry,
    private val libraryProviders: TrackingLibraryProviderRegistry,
    private val sourceSettings: ContinueWatchingSettingsDataStore,
    private val profileManager: ProfileManager,
    private val authManager: AuthManager
) : ViewModel() {

    private val selection = MutableStateFlow(TransferPanelUi())
    private var planJob: Job? = null

    /** Connected trackers that keep a library (Spec 105 D12: MDBList has none until its S3), as transfer sides. */
    private val connected = registry.connectedProviderIdsFlow.map { ids ->
        TrackingSource.entries.filterTo(mutableSetOf()) { source ->
            source.providerId?.let { it in ids && libraryProviders.provider(it) != null } == true
        }
    }

    internal val ui: StateFlow<TransferPanelUi> = combine(selection, service.state) { panel, run ->
        panel.copy(run = run)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TransferPanelUi())

    /**
     * Opening the panel: From = the other side of the Library source (D1 caption), To = the Library source if allowed.
     * The last plan is dropped at once (no stale counts while the new one loads); while a transfer runs or shows its
     * result the panel shows that, so nothing is read.
     */
    internal fun open() {
        planJob?.cancel()
        selection.value = selection.value.copy(plan = TransferPlanUi.Loading)
        if (service.state.value !is LibraryTransferState.Idle) return
        viewModelScope.launch {
            val trackers = connected.first()
            val library = sourceSettings.librarySource.first()
            val sources = transferSources(trackers)
            if (sources.isEmpty()) return@launch
            val preferredFrom = if (library == TrackingSource.NUVIO) trackers.minByOrNull { it.ordinal } else TrackingSource.NUVIO
            val from = preferredFrom?.takeIf { it in sources } ?: sources.first()
            select(from, preferredTo = library, sources = sources, trackers = trackers)
        }
    }

    internal fun selectFrom(from: TrackingSource) {
        viewModelScope.launch {
            val trackers = connected.first()
            select(from, preferredTo = selection.value.to, sources = transferSources(trackers), trackers = trackers)
        }
    }

    internal fun selectTo(to: TrackingSource) {
        val current = selection.value
        if (to !in current.destinations) return
        selection.value = current.copy(to = to)
        loadPlan()
    }

    internal fun retryPlan() = loadPlan()

    private fun select(from: TrackingSource, preferredTo: TrackingSource, sources: List<TrackingSource>, trackers: Set<TrackingSource>) {
        val destinations = transferDestinations(from, trackers)
        val to = preferredTo.takeIf { it in destinations } ?: destinations.firstOrNull() ?: return
        selection.value = selection.value.copy(
            from = from,
            to = to,
            sources = sources,
            destinations = destinations,
            nuvioAccount = authManager.isAuthenticated
        )
        loadPlan()
    }

    private fun loadPlan() {
        val panel = selection.value
        val profileId = profileManager.activeProfileId.value
        planJob?.cancel()
        selection.value = panel.copy(plan = TransferPlanUi.Loading)
        planJob = viewModelScope.launch {
            val plan = try {
                TransferPlanUi.Ready(service.preview(profileId, panel.from, panel.to))
            } catch (error: LibraryTransferReadException) {
                TransferPlanUi.Failed(error.source)
            }
            selection.value = selection.value.copy(plan = plan)
        }
    }

    internal fun start(mode: LibraryTransferMode) {
        val preview = (selection.value.plan as? TransferPlanUi.Ready)?.preview ?: return
        service.start(preview, mode)
    }

    internal fun stop() = service.stop()

    /** Close on a result (back to the hub); the next open plans afresh. */
    internal fun closeResult() = service.dismiss()
}
