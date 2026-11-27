package com.nuvio.tv.ui.screens.settings.trackers

import android.content.Context
import com.nuvio.tv.R
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.server.PasteKeyPageStrings
import com.nuvio.tv.core.server.PasteKeyPhonePage
import com.nuvio.tv.core.server.PasteKeyServer
import com.nuvio.tv.core.server.PasteKeyServerStarter
import com.nuvio.tv.core.server.StartedPasteKeyServer
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingAccountStatus
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingDeviceCode
import com.nuvio.tv.core.tracking.TrackingDevicePoll
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingSetupText
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.core.tracking.availableTrackingSources
import com.nuvio.tv.core.tracking.disconnect
import com.nuvio.tv.core.tracking.trackerCallFailure
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.simkl.RelatedTitlesSource
import com.nuvio.tv.data.simkl.SimklAnimeSeasons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Trackers"

/** E3: how much of the stored key the panel shows. */
private const val KEY_HINT_LENGTH = 4

/** A tracker row's state on the hub (mockup A1, A2, C3; Spec 106 K1: NEEDS_SETUP until its per-TV setup is done). */
internal enum class TrackerStatus { NEEDS_SETUP, NOT_CONNECTED, CONNECTED, REVOKED }

/**
 * [setup] is the tracker's own setup wording, null for a tracker with nothing to set up; [setupHint] the saved
 * value's last four characters (K4/K5). [accountStatus] is the active profile's allowance (E2/E5). [keyText] is set for
 * a tracker that connects with a pasted key (Spec 105 E1-E4), [keyHint] the stored key's last four characters (E3).
 */
internal data class TrackerRowUi(
    val id: TrackingProviderId,
    val name: String,
    val logoRes: Int?,
    val status: TrackerStatus,
    val accountName: String?,
    val lastSyncedAtEpochMs: Long?,
    val syncing: Boolean,
    val setup: TrackingSetupText? = null,
    val setupHint: String? = null,
    val accountStatus: TrackingAccountStatus? = null,
    val keyText: TrackerKeyText? = null,
    val keyHint: String? = null
)

private data class ProviderExtras(val setupHint: String?, val accountStatus: TrackingAccountStatus?)

/** The open setup panel (K2/K4/K5); [phoneUrl] null = no phone page runs (no network, no free port, expired). */
internal data class TrackerSetupUi(val providerId: TrackingProviderId, val phoneUrl: String?)

/** The open key panel (Spec 105 E2/E4); [lastCheck] is what the last send came to (Refused, Unreachable), null before one. */
internal data class TrackerKeyUi(val providerId: TrackingProviderId, val phoneUrl: String?, val lastCheck: PasteCheck?)

/** The key panel's session: a send's answer for an older one is not shown; [profileId] is the profile it saves on. */
private data class KeyPanel(
    val providerId: TrackingProviderId,
    val profileId: Int,
    val session: Int,
    val lastCheck: PasteCheck? = null
)

/** One source picker row (C1, C2): the saved choice, and whether reads fall back to Nuvio meanwhile (C3). */
internal data class TrackerSourceUi(
    val chosen: TrackingSource,
    val readsFromNuvio: Boolean
)

/** The open sign-in; [profileId] is the profile it was opened on: the account is saved there, whatever happens meanwhile. */
internal data class TrackerSignInUi(
    val providerId: TrackingProviderId,
    val name: String,
    val profileId: Int,
    val state: TrackerSignIn
)

internal data class TrackersUiState(
    val rows: List<TrackerRowUi> = emptyList(),
    val progressSource: TrackerSourceUi = TrackerSourceUi(TrackingSource.NUVIO, readsFromNuvio = false),
    val librarySource: TrackerSourceUi = TrackerSourceUi(TrackingSource.NUVIO, readsFromNuvio = false),
    /** The pickers' options: Nuvio, then each connected tracker. */
    val sourceChoices: List<TrackingSource> = listOf(TrackingSource.NUVIO),
    /** [sourceChoices] minus trackers with no library yet (MDBList until Spec 105 S3). */
    val libraryChoices: List<TrackingSource> = listOf(TrackingSource.NUVIO),
    val signIn: TrackerSignInUi? = null,
    val setup: TrackerSetupUi? = null,
    val keyPanel: TrackerKeyUi? = null,
    /** The active profile's Simkl anime mode (Spec 106 E2/E3; shown in Simkl's panel only). */
    val simklAnimeSeasons: SimklAnimeSeasons = SimklAnimeSeasons.DEFAULT,
    /** The active profile's chosen More like this source (Spec 106 E1/E6). */
    val relatedSource: RelatedTitlesSource = RelatedTitlesSource.DEFAULT,
    /** E6's options: TMDB, then Simkl while it is connected. */
    val relatedChoices: List<RelatedTitlesSource> = listOf(RelatedTitlesSource.TMDB)
) {
    /** The source rows are dimmed until a tracker is connected (A1). */
    val sourcesEnabled: Boolean get() = sourceChoices.size > 1

    /** The Library source row is dimmed until a tracker with a library is connected. */
    val librarySourceEnabled: Boolean get() = libraryChoices.size > 1

    /** The More like this row is listed when this build has Simkl (E1). */
    val hasRelatedSource: Boolean get() = rows.any { it.id == TrackingProviderId.SIMKL }

    /** The More like this row is dimmed until Simkl is connected (E1). */
    val relatedSourceEnabled: Boolean get() = relatedChoices.size > 1
}

/** The profile's saved choices the hub shows. */
private data class SourceSettings(
    val progress: TrackingSource,
    val library: TrackingSource,
    val animeSeasons: SimklAnimeSeasons,
    val related: RelatedTitlesSource
)

/** One-shot toasts (A3 "MDBList synced", B5 "Connected to MDBList as ..."). Dropped when no pane is listening. */
internal sealed interface TrackersEvent {
    val id: TrackingProviderId
    val name: String

    data class Synced(override val id: TrackingProviderId, override val name: String) : TrackersEvent
    data class SyncFailed(override val id: TrackingProviderId, override val name: String) : TrackersEvent
    data class Connected(override val id: TrackingProviderId, override val name: String, val accountName: String?) : TrackersEvent
}

/** The Connected toast's text (Trackers and Integrations show the same). */
internal fun TrackersEvent.Connected.toastText(context: Context): String =
    if (accountName.isNullOrBlank()) {
        context.getString(R.string.trackers_connected_toast, name)
    } else {
        context.getString(R.string.trackers_connected_toast_account, name, accountName)
    }

/**
 * Settings › Trackers (Spec 104 S3c). Lists the trackers this build can sign in to with their account state, holds the
 * source pickers (Spec 106 E6 adds More like this), Sync now and Disconnect, and runs a device-code sign-in while its screen is open: polling stops
 * when the screen closes (or the ViewModel goes), never runs in the background. A tracker with a per-TV setup (Spec 106
 * K1-K5) gets its setup panel here, a tracker that connects with a pasted key (Spec 105 E1-E4) its key panel; each
 * has a phone page that runs only while its panel is open.
 */
@HiltViewModel
class TrackersViewModel internal constructor(
    private val registry: TrackingProviderRegistry,
    private val accountStore: TrackingAccountStore,
    private val sourceController: TrackingSourceController,
    private val sourceSettings: ContinueWatchingSettingsDataStore,
    private val progressProviders: TrackingProgressProviderRegistry,
    private val libraryProviders: TrackingLibraryProviderRegistry,
    private val profileManager: ProfileManager,
    private val startPastePage: (PasteKeyPageStrings, Int, (PasteKeyServer, String) -> PasteCheck) -> StartedPasteKeyServer?,
    private val nowEpochMs: () -> Long
) : ViewModel() {

    @Inject
    constructor(
        registry: TrackingProviderRegistry,
        accountStore: TrackingAccountStore,
        sourceController: TrackingSourceController,
        sourceSettings: ContinueWatchingSettingsDataStore,
        progressProviders: TrackingProgressProviderRegistry,
        libraryProviders: TrackingLibraryProviderRegistry,
        profileManager: ProfileManager,
        pasteServers: PasteKeyServerStarter
    ) : this(
        registry, accountStore, sourceController, sourceSettings, progressProviders, libraryProviders, profileManager,
        pasteServers::start, System::currentTimeMillis
    )

    /** False with no tracker bound: Settings hides the Trackers rail item (A6). Every build binds Simkl (Spec 106 D9). */
    internal val hasTrackers: Boolean = registry.hasProviders

    private val syncing = MutableStateFlow<Set<TrackingProviderId>>(emptySet())
    private val signIn = MutableStateFlow<TrackerSignInUi?>(null)
    private val signInJobs = mutableListOf<Job>()
    private val setupFor = MutableStateFlow<TrackingProviderId?>(null)
    private val keyPanel = MutableStateFlow<KeyPanel?>(null)
    private var keySessions = 0

    /** One phone page for whichever panel is open: the setup (K2-K5) or the key panel (E2/E4), never both. */
    private val phonePage = PasteKeyPhonePage(viewModelScope)

    /** Attempts keep counting across opens, so a late result of an earlier sign-in can never match the current one. */
    private var lastAttempt = 0

    // No replay and no buffer for absent listeners: a toast for a pane that is not shown is dropped, not replayed later.
    private val _events = MutableSharedFlow<TrackersEvent>(extraBufferCapacity = 8)
    internal val events: Flow<TrackersEvent> = _events.asSharedFlow()

    private val providers: List<TrackingProvider> = registry.providers()

    private val accounts: Flow<Map<TrackingProviderId, TrackingAccount?>> = providers
        .map { provider -> accountStore.account(provider.descriptor.id) }
        .let { flows ->
            if (flows.isEmpty()) flowOf(emptyMap())
            else combine(flows) { values -> providers.map { it.descriptor.id }.zip(values.toList()).toMap() }
        }

    /** Each provider's setup hint and allowance, by id. */
    private val providerExtras: Flow<Map<TrackingProviderId, ProviderExtras>> = providers
        .map { provider ->
            combine(provider.setup?.savedHint ?: flowOf(null), provider.accountStatus) { hint, status ->
                provider.descriptor.id to ProviderExtras(hint, status)
            }
        }
        .let { flows -> if (flows.isEmpty()) flowOf(emptyMap()) else combine(flows) { it.toMap() } }

    private val panelUi: Flow<Pair<TrackerSetupUi?, TrackerKeyUi?>> = combine(setupFor, keyPanel, phonePage.url) { id, key, url ->
        id?.let { TrackerSetupUi(it, url) } to key?.let { TrackerKeyUi(it.providerId, url, it.lastCheck) }
    }

    internal val uiState: StateFlow<TrackersUiState> = combine(
        combine(accounts, providerExtras, ::Pair),
        combine(
            sourceSettings.progressSource,
            sourceSettings.librarySource,
            sourceSettings.activeSimklAnimeSeasons,
            sourceSettings.relatedTitlesSource,
            ::SourceSettings
        ),
        registry.connectedProviderIdsFlow,
        combine(syncing, signIn, ::Pair),
        panelUi
    ) { (accounts, extras), settings, connected, (syncingIds, signInUi), (setupPanel, keyPanelUi) ->
        val choices = availableTrackingSources(connected)
        TrackersUiState(
            rows = providers.map { provider ->
                val id = provider.descriptor.id
                rowOf(provider, accounts[id], id in syncingIds, extras[id]?.setupHint, extras[id]?.accountStatus)
            },
            progressSource = sourceUi(settings.progress, connected),
            librarySource = sourceUi(settings.library, connected),
            sourceChoices = choices,
            libraryChoices = choices.filter { source -> source.providerId.let { it == null || libraryProviders.provider(it) != null } },
            signIn = signInUi,
            setup = setupPanel,
            keyPanel = keyPanelUi,
            simklAnimeSeasons = settings.animeSeasons,
            relatedSource = settings.related,
            relatedChoices = RelatedTitlesSource.entries.filter { it.effective(connected) == it }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TrackersUiState())

    private fun sourceUi(source: TrackingSource, connected: Set<TrackingProviderId>) =
        TrackerSourceUi(source, readsFromNuvio = source.providerId?.let { it !in connected } == true)

    private fun rowOf(
        provider: TrackingProvider,
        account: TrackingAccount?,
        syncing: Boolean,
        setupHint: String?,
        accountStatus: TrackingAccountStatus?
    ): TrackerRowUi {
        val keyText = provider.keyAuth?.let { trackerKeyText(provider.descriptor.id) }
        return TrackerRowUi(
            id = provider.descriptor.id,
            name = provider.descriptor.displayName,
            logoRes = provider.descriptor.logoRes,
            status = when {
                // An account kept from before a removed setup cannot be used: the setup comes first (K1).
                provider.setup != null && setupHint == null -> TrackerStatus.NEEDS_SETUP
                account == null -> TrackerStatus.NOT_CONNECTED
                account.revoked -> TrackerStatus.REVOKED
                else -> TrackerStatus.CONNECTED
            },
            accountName = account?.accountName,
            lastSyncedAtEpochMs = account?.lastSyncedAtEpochMs,
            syncing = syncing,
            setup = provider.setup?.text,
            setupHint = setupHint,
            accountStatus = accountStatus,
            keyText = keyText,
            keyHint = keyText?.let { account?.accessToken?.takeLast(KEY_HINT_LENGTH) }
        )
    }

    internal fun selectProgressSource(source: TrackingSource) {
        viewModelScope.launch { sourceController.selectProgressSource(source) }
    }

    internal fun selectLibrarySource(source: TrackingSource) {
        viewModelScope.launch { sourceController.selectLibrarySource(source) }
    }

    /** E3: saved for the active profile; Simkl's providers re-project at once, with no call. */
    internal fun selectSimklAnimeSeasons(value: SimklAnimeSeasons) {
        val profileId = profileManager.activeProfileId.value
        viewModelScope.launch { sourceSettings.setSimklAnimeSeasons(value, profileId) }
    }

    /**
     * E6: saved for the active profile. Simkl is refused while it is not connected (the picker lists it only then; this
     * covers a sign-out landing while the picker is open).
     */
    internal fun selectRelatedSource(value: RelatedTitlesSource) {
        if (value !in uiState.value.relatedChoices) return
        val profileId = profileManager.activeProfileId.value
        viewModelScope.launch { sourceSettings.setRelatedTitlesSource(value, profileId) }
    }

    /** A3 Sync now: this tracker's progress and library, as a user asked (not throttled). */
    internal fun syncNow(id: TrackingProviderId) {
        val provider = registry.provider(id) ?: return
        if (id in syncing.value) return
        syncing.update { it + id }
        viewModelScope.launch {
            try {
                val failed = sync(id)
                _events.tryEmit(
                    if (failed) TrackersEvent.SyncFailed(id, provider.descriptor.displayName)
                    else TrackersEvent.Synced(id, provider.descriptor.displayName)
                )
            } finally {
                syncing.update { it - id }
            }
        }
    }

    /** Returns true when a refresh failed. */
    private suspend fun sync(id: TrackingProviderId): Boolean {
        val progress = progressProviders.provider(id)?.let { p -> trackerCallFailure { p.refresh(TrackingRefreshIntent.USER_INITIATED) } }
        val library = libraryProviders.provider(id)?.let { p -> trackerCallFailure { p.refresh(TrackingRefreshIntent.USER_INITIATED) } }
        listOfNotNull(progress, library).forEach { error ->
            Log.w(TAG, "${id.storageId} sync failed: ${error.javaClass.simpleName}")
        }
        return progress != null || library != null
    }

    /**
     * A4 Disconnect: forgets this profile's tokens, then every source that pointed at the tracker goes back to Nuvio.
     * The profile is read once, first, so a switch meanwhile cannot move the reset onto another profile. The pair is not
     * cancelled by leaving Settings (else the tokens could go while a source still points at the tracker). A failed
     * disconnect (the provider's local store) is logged and leaves both as they were.
     */
    internal fun disconnect(id: TrackingProviderId) {
        val provider = registry.provider(id) ?: return
        val profileId = profileManager.activeProfileId.value
        viewModelScope.launch {
            withContext(NonCancellable) {
                trackerCallFailure { provider.disconnect(profileId) }?.let { error ->
                    Log.w(TAG, "${id.storageId} disconnect failed: ${error.javaClass.simpleName}")
                    return@withContext
                }
                sourceController.onProviderDisconnected(id, profileId)
            }
        }
    }

    /** B1: opens the sign-in for [id] on the active profile and asks for a code. */
    internal fun openSignIn(id: TrackingProviderId) {
        val provider = registry.provider(id)?.takeIf { it.deviceAuth != null } ?: return
        closeSetup()
        closeKeyPanel()
        cancelSignInWork()
        signIn.value = TrackerSignInUi(
            id, provider.descriptor.displayName, profileManager.activeProfileId.value, initialTrackerSignIn(lastAttempt)
        )
        dispatch(provider, TrackerSignInEvent.Restart)
    }

    /** B3 New code, B4 Try again. */
    internal fun restartSignIn() {
        val current = signIn.value ?: return
        val provider = registry.provider(current.providerId) ?: return
        cancelSignInWork()
        dispatch(provider, TrackerSignInEvent.Restart)
    }

    /** Cancel or Back: polling stops; nothing was connected. */
    internal fun closeSignIn() {
        cancelSignInWork()
        signIn.value = null
    }

    private fun dispatch(provider: TrackingProvider, event: TrackerSignInEvent) {
        val current = signIn.value ?: return
        if (current.providerId != provider.descriptor.id) return
        val (next, effects) = reduceTrackerSignIn(current.state, event)
        lastAttempt = next.attempt
        signIn.value = current.copy(state = next)
        if (next is TrackerSignIn.Connected && current.state !is TrackerSignIn.Connected) {
            cancelSignInWork()
            signIn.value = null
            onConnected(provider, current.profileId)
            return
        }
        effects.forEach { effect -> launchEffect(provider, current.profileId, effect) }
    }

    private fun launchEffect(provider: TrackingProvider, profileId: Int, effect: TrackerSignInEffect) {
        val auth = provider.deviceAuth ?: return
        signInJobs += viewModelScope.launch {
            // trackerCallFailure: a provider's own cancellation (its timeout) is a failed call, so the sign-in moves on
            // (retry, then "Couldn't reach"); this job's own cancellation is rethrown.
            val event = when (effect) {
                is TrackerSignInEffect.Start -> {
                    var code: TrackingDeviceCode? = null
                    val error = trackerCallFailure { code = auth.start(profileId) }
                    code?.let { TrackerSignInEvent.Started(effect.attempt, it) } ?: run {
                        Log.w(TAG, "${provider.descriptor.id.storageId} sign-in start failed: ${error?.javaClass?.simpleName}")
                        TrackerSignInEvent.StartFailed(effect.attempt)
                    }
                }

                is TrackerSignInEffect.Poll -> {
                    delay(effect.delayMs)
                    var result: TrackingDevicePoll? = null
                    val error = trackerCallFailure { result = auth.poll(profileId, effect.code) }
                    result?.let { TrackerSignInEvent.Polled(effect.attempt, it, nowEpochMs()) } ?: run {
                        Log.w(TAG, "${provider.descriptor.id.storageId} sign-in poll failed: ${error?.javaClass?.simpleName}")
                        TrackerSignInEvent.PollFailed(effect.attempt, nowEpochMs())
                    }
                }
            }
            // A call that returned after this job was cancelled (closed, restarted) reports nothing.
            ensureActive()
            dispatch(provider, event)
        }
        signInJobs.removeAll { it.isCompleted }
    }

    /**
     * B5 (and a key accepted, Spec 105 E2): a toast; the first sync starts at once. Sources stay as they were (D9). The
     * account was saved on [profileId] (the sign-in's or key panel's); the first sync runs only while that profile is
     * still active (a refresh reads the active profile's account).
     */
    private fun onConnected(provider: TrackingProvider, profileId: Int) {
        val id = provider.descriptor.id
        val startSync = id !in syncing.value && profileManager.activeProfileId.value == profileId
        if (startSync) syncing.update { it + id }
        viewModelScope.launch {
            try {
                val accountName = accountStore.get(profileId, id)?.accountName
                _events.tryEmit(TrackersEvent.Connected(id, provider.descriptor.displayName, accountName))
                if (startSync) sync(id)
            } finally {
                if (startSync) syncing.update { it - id }
            }
        }
    }

    private fun cancelSignInWork() {
        signInJobs.forEach(Job::cancel)
        signInJobs.clear()
    }

    /** K2/K4/K5: opens [id]'s setup panel and its phone page (a new page, fresh token, each time). */
    internal fun openSetup(id: TrackingProviderId) {
        val setup = registry.provider(id)?.setup ?: return
        closeSignIn()
        keyPanel.value = null
        setupFor.value = id
        phonePage.open(
            start = { submit -> startPastePage(setup.text.phonePage, setup.phonePagePort, submit) },
            check = setup::submit
        )
    }

    /** Done, Back, leaving Settings: the phone page ends; a send being checked still gets its answer. */
    internal fun closeSetup() {
        phonePage.close()
        setupFor.value = null
    }

    /** K4 Connect: the setup panel gives way to the sign-in. */
    internal fun connectFromSetup(id: TrackingProviderId) {
        closeSetup()
        openSignIn(id)
    }

    /**
     * K5 Remove id, after its confirm: every profile on this TV is signed out, then the value goes. Not cancelled by
     * leaving Settings; a failure is logged and leaves the value saved.
     */
    internal fun removeSetup(id: TrackingProviderId) {
        val setup = registry.provider(id)?.setup ?: return
        closeSetup()
        viewModelScope.launch {
            withContext(NonCancellable) {
                trackerCallFailure { setup.remove() }?.let { error ->
                    Log.w(TAG, "${id.storageId} setup removal failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * E2: opens [id]'s key panel and its phone page for the active profile (a new page, fresh token, each time). A key
     * sent from the phone is checked and saved on that profile, whatever is active meanwhile; once accepted the panel
     * closes and the hub says Connected (B5's toast and first sync). A refused or unreachable check is shown in the panel
     * (E4) while it stays open.
     */
    internal fun openKeyPanel(id: TrackingProviderId) {
        val provider = registry.provider(id) ?: return
        val auth = provider.keyAuth ?: return
        val text = trackerKeyText(id) ?: return
        closeSignIn()
        closeSetup()
        val profileId = profileManager.activeProfileId.value
        val session = ++keySessions
        keyPanel.value = KeyPanel(id, profileId, session)
        phonePage.open(
            start = { submit -> startPastePage(text.phonePage, text.phonePagePort, submit) },
            check = { value, stillWanted ->
                auth.connect(profileId, value, stillWanted).also { answer ->
                    // On the page's handler thread: the panel state changes on the main thread.
                    viewModelScope.launch { onKeyChecked(provider, profileId, session, answer) }
                }
            }
        )
    }

    private fun onKeyChecked(provider: TrackingProvider, profileId: Int, session: Int, answer: PasteCheck) {
        val current = keyPanel.value?.takeIf { it.session == session }
        when (answer) {
            PasteCheck.Saved -> {
                // A newer panel for the same tracker and profile has nothing left to ask either (the key landed).
                val open = keyPanel.value
                if (open != null && open.providerId == provider.descriptor.id && open.profileId == profileId) closeKeyPanel()
                onConnected(provider, profileId)
            }
            PasteCheck.Refused, PasteCheck.Unreachable -> if (current != null) keyPanel.value = current.copy(lastCheck = answer)
            PasteCheck.Closed -> Unit
        }
    }

    /** Done, Back, leaving Settings: the phone page ends; a send being checked still gets its answer. */
    internal fun closeKeyPanel() {
        if (keyPanel.value == null) return
        phonePage.close()
        keyPanel.value = null
    }

    override fun onCleared() {
        phonePage.close()
    }
}
