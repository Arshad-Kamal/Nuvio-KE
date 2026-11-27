package com.nuvio.tv.ui.screens.settings.trackers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.SyncAge
import com.nuvio.tv.core.sync.syncAge
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.data.simkl.RelatedTitlesSource
import com.nuvio.tv.data.simkl.SimklAnimeSeasons
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowValue
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.components.marquee.MarqueeValueRow
import com.nuvio.tv.ui.components.marquee.formRowSurface
import com.nuvio.tv.ui.screens.settings.SettingsNote
import com.nuvio.tv.ui.screens.settings.SettingsPane
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsPickerPanel
import com.nuvio.tv.ui.screens.settings.LocalSettingsReturnFocus
import com.nuvio.tv.ui.screens.settings.SettingsSectionLabel
import com.nuvio.tv.ui.screens.settings.optionalFocusRequester
import com.nuvio.tv.ui.screens.settings.rememberSettingsReturnTarget
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** A tracker row's list key, also its Settings return target (Settings' MDBList entry, Spec 105 D28). */
internal fun trackerRowKey(id: TrackingProviderId): String = "tracker_${id.storageId}"

/** The "Synced 4 min ago" lines move on this often while the pane is open. */
private const val SYNC_AGE_TICK_MS = 30_000L

private enum class SourcePicker { PROGRESS, LIBRARY }

/**
 * Settings › Trackers (Spec 104 S3d; sandbox `spec-104-trackers` v2, stages A1-A5, C1-C3). One row per tracker this
 * build can sign in to: OK on a row whose per-TV setup is not done opens the setup panel (Spec 106 K1/K2,
 * [TrackerSetupPanel]); on a signed-out or revoked row the sign-in (B1-B5, [TrackerSignInDialog]), or for a tracker
 * that connects with a pasted key its key panel (Spec 105 E1-E4, [TrackerKeyPanel]); on a
 * connected one its account panel (A3: Sync now, Disconnect with its confirm, A4; Spec 106 E2/E5's allowance box and
 * K5's setup row). Then the two source pickers (C1, C2),
 * dimmed with their reason until a tracker is connected, Spec 106's More like this source (E1/E6; dimmed until Simkl is
 * connected), and Transfer library (D1-D4, [LibraryTransferPanel]). The sign-in polls only
 * while it is open: leaving Settings or the app closes it. [openMdbListAccount] (Settings' entry
 * [com.nuvio.tv.ui.screens.settings.SettingsEntry.MDBLIST_ACCOUNT], once MDBList's row has focus; Spec 105 D28) opens
 * MDBList's account panel when it is connected; [onMdbListAccountOpened] clears it. MDBList's panel carries Lists on
 * Home (Spec 105 S5b2, A2/E3, [ListsOnHomeViewModel]), which opens Home catalog order ([onOpenHomeCatalogOrder]); Back
 * returns focus to MDBList's row.
 */
@Composable
internal fun TrackersSettingsContent(
    initialFocusRequester: FocusRequester?,
    openMdbListAccount: Boolean = false,
    onMdbListAccountOpened: () -> Unit = {},
    onOpenHomeCatalogOrder: () -> Unit = {},
    viewModel: TrackersViewModel = hiltViewModel(),
    listsOnHome: ListsOnHomeViewModel = hiltViewModel()
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val toast = LocalMarqueeToast.current
    val context = LocalContext.current
    var accountPanelFor by remember { mutableStateOf<TrackingProviderId?>(null) }
    var disconnectFor by remember { mutableStateOf<TrackingProviderId?>(null) }
    var picker by remember { mutableStateOf<SourcePicker?>(null) }
    var transferOpen by remember { mutableStateOf(false) }
    var animePickerOpen by remember { mutableStateOf(false) }
    var relatedPickerOpen by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(SYNC_AGE_TICK_MS)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.closeSignIn()
        viewModel.closeSetup()
        viewModel.closeKeyPanel()
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.closeSignIn()
            viewModel.closeSetup()
            viewModel.closeKeyPanel()
        }
    }

    LaunchedEffect(openMdbListAccount) {
        if (openMdbListAccount) {
            // Not connected (a disconnect meanwhile): the panel clears itself below and focus stays on the row.
            accountPanelFor = TrackingProviderId.MDBLIST
            onMdbListAccountOpened()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TrackersEvent.Synced -> {
                    // A3: the panel's head line said Syncing...; the toast takes over.
                    if (event.id == accountPanelFor) accountPanelFor = null
                    toast?.show(context.getString(R.string.trackers_synced_toast, event.name), success = true)
                }
                is TrackersEvent.SyncFailed -> {
                    if (event.id == accountPanelFor) accountPanelFor = null
                    toast?.show(context.getString(R.string.trackers_sync_failed_toast, event.name))
                }
                is TrackersEvent.Connected -> toast?.show(event.toastText(context), success = true)
            }
        }
    }

    val names = state.rows.map { it.name }
    SettingsPane(
        title = stringResource(R.string.settings_trackers),
        subtitle = when (names.size) {
            0 -> null
            1 -> stringResource(R.string.trackers_subtitle_one, names[0])
            else -> stringResource(R.string.trackers_subtitle_two, names[0], names[1])
        }
    ) {
        item(key = "trackers_accounts") { SettingsSectionLabel(text = stringResource(R.string.trackers_section_accounts)) }
        state.rows.forEachIndexed { index, row ->
            item(key = trackerRowKey(row.id)) {
                val returnRow = rememberSettingsReturnTarget(trackerRowKey(row.id))
                TrackerAccountRow(
                    row = row,
                    nowEpochMs = now,
                    onClick = {
                        when (row.status) {
                            TrackerStatus.NEEDS_SETUP -> viewModel.openSetup(row.id)
                            TrackerStatus.CONNECTED -> accountPanelFor = row.id
                            TrackerStatus.NOT_CONNECTED, TrackerStatus.REVOKED ->
                                if (row.keyText != null) viewModel.openKeyPanel(row.id) else viewModel.openSignIn(row.id)
                        }
                    },
                    modifier = Modifier
                        .optionalFocusRequester(returnRow.requester)
                        .then(if (index == 0) Modifier.optionalFocusRequester(initialFocusRequester) else Modifier)
                )
            }
        }
        item(key = "trackers_sources") { SettingsSectionLabel(text = stringResource(R.string.trackers_section_sources)) }
        item(key = "trackers_progress_source") {
            SourceRow(
                title = stringResource(R.string.trackers_progress_source_title),
                subtitle = stringResource(R.string.trackers_progress_source_subtitle),
                source = state.progressSource,
                state = state,
                enabled = state.sourcesEnabled,
                onClick = { picker = SourcePicker.PROGRESS }
            )
        }
        item(key = "trackers_library_source") {
            SourceRow(
                title = stringResource(R.string.trackers_library_source_title),
                subtitle = stringResource(R.string.trackers_library_source_subtitle),
                source = state.librarySource,
                state = state,
                enabled = state.librarySourceEnabled,
                onClick = { picker = SourcePicker.LIBRARY }
            )
        }
        if (state.hasRelatedSource) {
            item(key = "trackers_related_source") {
                MarqueeValueRow(
                    label = stringResource(R.string.trackers_related_source_title),
                    value = relatedSourceName(state.relatedSource, state),
                    onClick = { relatedPickerOpen = true },
                    helper = stringResource(
                        if (state.relatedSourceEnabled) R.string.trackers_related_source_subtitle else R.string.trackers_needs_tracker
                    ),
                    enabled = state.relatedSourceEnabled,
                    minHeight = NuvioTheme.marquee.settings.rowMinHeight
                )
            }
        }
        item(key = "trackers_transfer") {
            MarqueeValueRow(
                label = stringResource(R.string.trackers_transfer_title),
                value = null,
                helper = stringResource(
                    if (state.librarySourceEnabled) R.string.trackers_transfer_subtitle else R.string.trackers_needs_tracker
                ),
                enabled = state.librarySourceEnabled,
                onClick = { transferOpen = true },
                minHeight = NuvioTheme.marquee.settings.rowMinHeight
            )
        }
        item(key = "trackers_note") { SettingsNote(text = stringResource(R.string.trackers_note)) }
    }

    val returnFocus = LocalSettingsReturnFocus.current
    val listsOnHomeState = listsOnHome.uiState.collectAsStateWithLifecycle().value
    val panelRow = accountPanelFor?.let { id -> state.rows.firstOrNull { it.id == id } }
    if (panelRow != null && panelRow.status == TrackerStatus.CONNECTED) {
        TrackerAccountPanel(
            row = panelRow,
            nowEpochMs = now,
            animeSeasons = state.simklAnimeSeasons.takeIf { panelRow.id == TrackingProviderId.SIMKL },
            onAnimeSeasons = {
                accountPanelFor = null
                animePickerOpen = true
            },
            onSyncNow = { viewModel.syncNow(panelRow.id) },
            listsOnHome = listsOnHomeState?.takeIf { panelRow.id == TrackingProviderId.MDBLIST },
            onListsOnHome = {
                accountPanelFor = null
                returnFocus.markLaunch(trackerRowKey(panelRow.id))
                onOpenHomeCatalogOrder()
            },
            onSetup = {
                accountPanelFor = null
                viewModel.openSetup(panelRow.id)
            },
            onDisconnect = {
                accountPanelFor = null
                disconnectFor = panelRow.id
            },
            onDismiss = { accountPanelFor = null }
        )
    } else if (accountPanelFor != null) {
        // Signed out meanwhile (a revoke, a profile switch): the panel has nothing left to act on.
        LaunchedEffect(Unit) { accountPanelFor = null }
    }

    disconnectFor?.let { id -> state.rows.firstOrNull { it.id == id } }?.let { row ->
        DisconnectConfirm(
            row = row,
            resetsProgress = state.progressSource.chosen.providerId == row.id,
            resetsLibrary = state.librarySource.chosen.providerId == row.id,
            onConfirm = {
                viewModel.disconnect(row.id)
                disconnectFor = null
            },
            onDismiss = { disconnectFor = null }
        )
    }

    picker?.let { which ->
        val progress = which == SourcePicker.PROGRESS
        SettingsPickerPanel(
            title = stringResource(
                if (progress) R.string.trackers_progress_source_title else R.string.trackers_library_source_title
            ),
            options = (if (progress) state.sourceChoices else state.libraryChoices).map { source ->
                val name = sourceName(source, state)
                SettingsPickerOption(
                    value = source,
                    title = name,
                    description = when {
                        source == TrackingSource.NUVIO && progress -> stringResource(R.string.trackers_progress_option_nuvio)
                        source == TrackingSource.NUVIO -> stringResource(R.string.trackers_library_option_nuvio)
                        progress -> stringResource(R.string.trackers_progress_option_tracker, name)
                        else -> stringResource(R.string.trackers_library_option_tracker, name)
                    }
                )
            },
            selectedValue = if (progress) state.progressSource.chosen else state.librarySource.chosen,
            onSelect = { source ->
                if (progress) viewModel.selectProgressSource(source) else viewModel.selectLibrarySource(source)
                picker = null
            },
            onDismiss = { picker = null }
        )
    }

    if (animePickerOpen) {
        SettingsPickerPanel(
            title = stringResource(R.string.simkl_anime_seasons_title),
            options = SimklAnimeSeasons.entries.map { mode ->
                SettingsPickerOption(value = mode, title = animeSeasonsName(mode), description = stringResource(
                    when (mode) {
                        SimklAnimeSeasons.PER_SHOW -> R.string.simkl_anime_seasons_show_description
                        SimklAnimeSeasons.PER_SEASON -> R.string.simkl_anime_seasons_season_description
                    }
                ))
            },
            selectedValue = state.simklAnimeSeasons,
            onSelect = viewModel::selectSimklAnimeSeasons,
            onDismiss = { animePickerOpen = false }
        )
    }

    if (relatedPickerOpen) {
        SettingsPickerPanel(
            title = stringResource(R.string.trackers_related_source_title),
            options = state.relatedChoices.map { source ->
                SettingsPickerOption(
                    value = source,
                    title = relatedSourceName(source, state),
                    description = stringResource(
                        when (source) {
                            RelatedTitlesSource.TMDB -> R.string.trackers_related_option_tmdb
                            RelatedTitlesSource.SIMKL -> R.string.trackers_related_option_simkl
                        }
                    )
                )
            },
            selectedValue = state.relatedSource,
            onSelect = { source ->
                viewModel.selectRelatedSource(source)
                relatedPickerOpen = false
            },
            onDismiss = { relatedPickerOpen = false }
        )
    }

    if (transferOpen) {
        LibraryTransferPanel(
            viewModel = hiltViewModel(),
            sourceName = { source -> sourceName(source, state) },
            onDismiss = { transferOpen = false }
        )
    }

    state.setup?.let { setup ->
        val row = state.rows.firstOrNull { it.id == setup.providerId }
        if (row?.setup != null) {
            TrackerSetupPanel(
                name = row.name,
                text = row.setup,
                savedHint = row.setupHint,
                connected = row.status == TrackerStatus.CONNECTED,
                phoneUrl = setup.phoneUrl,
                onConnect = { viewModel.connectFromSetup(row.id) },
                onRemove = { viewModel.removeSetup(row.id) },
                onDismiss = viewModel::closeSetup
            )
        }
    }

    TrackerKeyPanelHost(state = state, onDismiss = viewModel::closeKeyPanel)

    state.signIn?.let { signIn ->
        TrackerSignInDialog(
            signIn = signIn,
            logoRes = state.rows.firstOrNull { it.id == signIn.providerId }?.logoRes,
            onRestart = viewModel::restartSignIn,
            onClose = viewModel::closeSignIn
        )
    }
}

@Composable
private fun animeSeasonsName(mode: SimklAnimeSeasons): String = stringResource(
    when (mode) {
        SimklAnimeSeasons.PER_SHOW -> R.string.simkl_anime_seasons_show
        SimklAnimeSeasons.PER_SEASON -> R.string.simkl_anime_seasons_season
    }
)

@Composable
private fun sourceName(source: TrackingSource, state: TrackersUiState): String {
    val id = source.providerId ?: return stringResource(R.string.trackers_source_nuvio)
    return state.rows.firstOrNull { it.id == id }?.name ?: source.storageId
}

@Composable
private fun relatedSourceName(source: RelatedTitlesSource, state: TrackersUiState): String = when (source) {
    RelatedTitlesSource.TMDB -> stringResource(R.string.settings_tmdb_name)
    RelatedTitlesSource.SIMKL -> sourceName(TrackingSource.SIMKL, state)
}

/** The sync line of a connected tracker: Syncing..., or how long ago it last synced. */
@Composable
private fun syncLine(row: TrackerRowUi, nowEpochMs: Long): String = if (row.syncing) {
    stringResource(R.string.account_sync_line_running)
} else {
    when (val age = syncAge(nowEpochMs, row.lastSyncedAtEpochMs)) {
        SyncAge.Never -> stringResource(R.string.account_sync_line_never)
        SyncAge.JustNow -> stringResource(R.string.account_sync_line_just_now)
        is SyncAge.Minutes -> pluralStringResource(R.plurals.account_sync_line_minutes, age.count, age.count)
        is SyncAge.Hours -> pluralStringResource(R.plurals.account_sync_line_hours, age.count, age.count)
        is SyncAge.Days -> pluralStringResource(R.plurals.account_sync_line_days, age.count, age.count)
    }
}

/**
 * A1/A2/C3 account row (`.l8r` with the tracker's glyph): name and its line (what connecting gives, the sync line, or
 * amber "signed this TV out"), then Connect, the green account name, or amber Signed out.
 */
@Composable
private fun TrackerAccountRow(row: TrackerRowUi, nowEpochMs: Long, onClick: () -> Unit, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.formRow
    var lit by remember { mutableStateOf(false) }
    val pausedUntil = row.accountStatus?.takeIf { row.status == TrackerStatus.CONNECTED }?.pausedUntil(nowEpochMs)
    val subtitle = when {
        row.status == TrackerStatus.NEEDS_SETUP -> row.setup?.let { stringResource(it.rowLine) }.orEmpty()
        row.status == TrackerStatus.NOT_CONNECTED -> stringResource(row.keyText?.rowLine ?: R.string.trackers_row_summary)
        row.status == TrackerStatus.REVOKED -> stringResource(R.string.trackers_row_signed_out_subtitle, row.name)
        pausedUntil != null -> stringResource(R.string.trackers_paused_row_line, row.name, timeLeftLabel(pausedUntil - nowEpochMs))
        else -> syncLine(row, nowEpochMs)
    }
    val amber = row.status == TrackerStatus.REVOKED || pausedUntil != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = marquee.settings.rowMinHeight)
            .onFocusChanged { lit = it.hasFocus }
            .formRowSurface(lit)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = tokens.paddingHorizontal, vertical = tokens.paddingVertical),
        horizontalArrangement = Arrangement.spacedBy(tokens.innerGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackerLogo(logoRes = row.logoRes, name = row.name, size = TrackerLogoSize.ROW)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = NuvioTheme.textStyles.button,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = NuvioTheme.textStyles.panelHelper,
                color = if (amber) NuvioPrimitives.warning else marquee.onImage3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when (row.status) {
            TrackerStatus.NEEDS_SETUP -> MarqueeFormRowValue(
                text = stringResource(R.string.trackers_row_set_up),
                dimmed = false,
                lit = lit
            )
            TrackerStatus.NOT_CONNECTED -> MarqueeFormRowValue(
                text = stringResource(if (row.keyText != null) R.string.trackers_row_add_key else R.string.trackers_row_connect),
                dimmed = false,
                lit = lit
            )
            TrackerStatus.CONNECTED -> if (pausedUntil != null) {
                Text(
                    text = stringResource(R.string.trackers_paused),
                    style = NuvioTheme.textStyles.meta,
                    color = NuvioPrimitives.warning,
                    maxLines = 1
                )
            } else Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(6.dp).background(NuvioPrimitives.successOnImage, CircleShape))
                Text(
                    text = row.accountName ?: stringResource(R.string.trackers_panel_connected),
                    style = NuvioTheme.textStyles.meta,
                    color = NuvioPrimitives.successOnImage,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TrackerStatus.REVOKED -> Text(
                text = stringResource(R.string.trackers_row_signed_out),
                style = NuvioTheme.textStyles.meta,
                color = NuvioPrimitives.warning,
                maxLines = 1
            )
        }
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}

/**
 * C1/C2 row: the chosen source's name. Dimmed with its reason until a tracker is connected (A1); a choice whose
 * tracker was signed out by its service keeps its name and says, in amber, that Nuvio shows meanwhile (C3).
 */
@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    source: TrackerSourceUi,
    state: TrackersUiState,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val name = sourceName(source.chosen, state)
    val warning = if (source.readsFromNuvio) stringResource(R.string.trackers_source_signed_out, name) else null
    MarqueeValueRow(
        label = title,
        value = name,
        onClick = onClick,
        helper = if (enabled) subtitle else stringResource(R.string.trackers_needs_tracker),
        helperContent = warning?.let { text ->
            { _ ->
                Text(
                    text = text,
                    style = NuvioTheme.textStyles.panelHelper,
                    color = NuvioPrimitives.warning,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        enabled = enabled,
        minHeight = NuvioTheme.marquee.settings.rowMinHeight
    )
}

/**
 * A3: the connected tracker's side panel: the account head, Sync now (focused) and Disconnect. Spec 106: Simkl's Anime
 * seasons row (E2, [animeSeasons] non-null; opens E3's picker), K5's setup row
 * (its value's last four, opens [TrackerSetupPanel]) above Disconnect, then E2's allowance box; while the service has
 * paused calls (E5) the head says Paused and Sync now is dimmed with the reason (focusable, OK does nothing). Spec 105
 * E3, a key tracker: the head names the key's last four (Syncing... while a sync runs), the allowance box (with A2's
 * Plan line) sits above Sync now, Disconnect forgets the key, and the foot note says how to stop the key everywhere.
 * [listsOnHome] non-null: S5b2's Lists on Home row ("6 of 9 lists shown", chevron) after Sync now; OK runs
 * [onListsOnHome].
 */
@Composable
private fun TrackerAccountPanel(
    row: TrackerRowUi,
    nowEpochMs: Long,
    animeSeasons: SimklAnimeSeasons?,
    onAnimeSeasons: () -> Unit,
    onSyncNow: () -> Unit,
    listsOnHome: ListsOnHomeUi?,
    onListsOnHome: () -> Unit,
    onSetup: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val syncFocus = remember { FocusRequester() }
    val pausedUntil = row.accountStatus?.pausedUntil(nowEpochMs)
    val connected = stringResource(if (pausedUntil != null) R.string.trackers_paused else R.string.trackers_panel_connected)
    val headLine = buildAnnotatedString {
        withStyle(SpanStyle(color = if (pausedUntil != null) NuvioPrimitives.warning else NuvioPrimitives.successOnImage)) {
            append(connected)
        }
        append(" \u00b7 ")
        append(
            if (row.keyHint != null && !row.syncing) stringResource(R.string.trackers_key_line, row.keyHint)
            else syncLine(row, nowEpochMs)
        )
    }
    val keyTracker = row.keyText != null
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.settings.pickerWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = syncFocus
    ) {
        PanelEyebrow(text = row.name)
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackerLogo(logoRes = row.logoRes, name = row.name, size = TrackerLogoSize.PANEL)
            Column {
                Text(
                    text = row.accountName ?: row.name,
                    style = NuvioTheme.textStyles.profileName,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = headLine, style = NuvioTheme.textStyles.caption, color = marquee.onImage2, maxLines = 1)
            }
        }
        if (keyTracker) row.accountStatus?.let { status -> TrackerAccountStatusBox(row.name, status, nowEpochMs) }
        PlayerPanelRow(
            title = stringResource(R.string.account_sync_now_title),
            subtitle = if (pausedUntil == null) stringResource(R.string.trackers_panel_sync_now_subtitle) else null,
            subtitleMark = pausedUntil?.let {
                stringResource(R.string.trackers_paused_sync_now, row.name, timeLeftLabel(it - nowEpochMs))
            },
            markColor = { NuvioPrimitives.warning },
            selected = false,
            onClick = onSyncNow,
            enabled = pausedUntil == null,
            focusRequester = syncFocus,
            subtitleMaxLines = 2
        )
        if (listsOnHome != null) {
            PlayerPanelRow(
                title = stringResource(R.string.mdblist_lists_on_home),
                subtitle = pluralStringResource(
                    R.plurals.mdblist_lists_on_home_count, listsOnHome.total, listsOnHome.shown, listsOnHome.total
                ),
                selected = false,
                onClick = onListsOnHome,
                trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight
            )
        }
        if (animeSeasons != null) {
            PlayerPanelRow(
                title = stringResource(R.string.simkl_anime_seasons_title),
                subtitle = stringResource(R.string.simkl_anime_seasons_subtitle),
                titleEnd = animeSeasonsName(animeSeasons),
                selected = false,
                onClick = onAnimeSeasons,
                subtitleMaxLines = 2
            )
        }
        if (row.setup != null && row.setupHint != null) {
            PlayerPanelRow(
                title = stringResource(row.setup.valueName),
                subtitle = stringResource(R.string.trackers_setup_row_subtitle, row.setupHint),
                titleEnd = stringResource(R.string.trackers_setup_row_value),
                selected = false,
                onClick = onSetup,
                subtitleMaxLines = 2
            )
        }
        PlayerPanelRow(
            title = stringResource(R.string.trackers_panel_disconnect),
            subtitle = if (keyTracker) stringResource(R.string.trackers_key_disconnect_subtitle)
            else stringResource(R.string.trackers_panel_disconnect_subtitle, row.name),
            selected = false,
            onClick = onDisconnect,
            destructive = true,
            subtitleMaxLines = 2
        )
        if (!keyTracker) row.accountStatus?.let { status -> TrackerAccountStatusBox(row.name, status, nowEpochMs) }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = row.keyText?.let { stringResource(it.note) } ?: stringResource(R.string.trackers_panel_note, row.name),
            style = NuvioTheme.textStyles.settingsNote,
            color = marquee.onImage2,
            modifier = Modifier.padding(horizontal = marquee.settings.panelTextInset, vertical = 4.dp)
        )
    }
}

/** A4: Cancel focused; the second sentence names the sources that go back to Nuvio, when any pointed at the tracker. */
@Composable
private fun DisconnectConfirm(
    row: TrackerRowUi,
    resetsProgress: Boolean,
    resetsLibrary: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = if (row.accountName.isNullOrBlank()) {
        stringResource(R.string.trackers_disconnect_body, row.name)
    } else {
        stringResource(R.string.trackers_disconnect_body_account, row.name, row.accountName)
    }
    val second = when {
        resetsProgress && resetsLibrary -> stringResource(R.string.trackers_disconnect_resets_both)
        resetsProgress -> stringResource(R.string.trackers_disconnect_resets_progress)
        resetsLibrary -> stringResource(R.string.trackers_disconnect_resets_library)
        else -> null
    }
    MarqueeConfirmPanel(
        eyebrow = stringResource(R.string.trackers_disconnect_title, row.name),
        body = if (second != null) "$first\n\n$second" else first,
        confirmLabel = stringResource(R.string.trackers_panel_disconnect),
        cancelLabel = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true
    )
}
