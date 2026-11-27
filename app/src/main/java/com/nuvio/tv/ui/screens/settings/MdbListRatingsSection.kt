package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.data.mdblist.MdbListRatingSource
import com.nuvio.tv.data.mdblist.MdbListRatingsPrefs
import com.nuvio.tv.data.mdblist.MdbListRatingsSettings
import com.nuvio.tv.data.mdblist.mdbListConnected
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.components.marquee.marqueeFormRowValueStyle
import com.nuvio.tv.ui.components.marquee.MarqueeSwitch
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The section's state: [connected] = the profile's MDBList key is stored and working (D7: the tracker's key). */
data class MdbListRatingsSettingsUi(val connected: Boolean, val settings: MdbListRatingsSettings) {
    /** The switch as shown: off with no key (D3), else as set (on until switched off). */
    val switchOn: Boolean get() = settings.onWith(connected)
}

/** Spec 105 S4 B1/B2: the active profile's ratings switch and sources; writes go to the profile active at the click. */
@HiltViewModel
class MdbListRatingsViewModel @Inject constructor(
    private val prefs: MdbListRatingsPrefs,
    accounts: TrackingAccountStore,
    private val profileManager: ProfileManager
) : ViewModel() {
    /** Null until both reads land (rows wait, as the TMDB rows do). */
    val state: StateFlow<MdbListRatingsSettingsUi?> = combine(
        accounts.mdbListConnected(),
        prefs.active
    ) { connected, settings -> MdbListRatingsSettingsUi(connected, settings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setEnabled(enabled: Boolean) = write { prefs.setEnabled(it, enabled) }

    fun toggleSource(source: MdbListRatingSource) = write { prefs.toggleSource(it, source) }

    fun move(source: MdbListRatingSource, delta: Int) = write { prefs.move(it, source, delta) }

    private fun write(block: suspend (profileId: Int) -> Unit) {
        val profileId = profileManager.activeProfileId.value
        viewModelScope.launch { block(profileId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * B1 (sandbox `spec-105-mdblist` v5): the MDBList ratings section, after the TMDB rows (D25). With no key the switch and
 * Sources dim (focusable, OK does nothing) and the switch reads off (D26). "Ratings from" (D24): connected, the account
 * name ([accountName]) after a green dot, and OK opens Trackers with MDBList's account panel ([onOpenAccount], D28);
 * not connected, "Add your MDBList key", and OK opens Trackers' MDBList key panel ([onAddKey]): one key connects
 * tracking and ratings (D7). [accountName] null with [state] connected shows the
 * dot alone (a key whose answer named no user).
 */
internal fun LazyListScope.mdbListRatingsSection(
    state: MdbListRatingsSettingsUi,
    accountName: String?,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSources: () -> Unit,
    onAddKey: () -> Unit,
    onOpenAccount: () -> Unit
) {
    item(key = "mdblist_ratings_section") {
        SettingsSectionLabel(text = stringResource(R.string.mdblist_ratings_section))
    }
    item(key = "mdblist_ratings_enabled") {
        SettingsSwitchRow(
            title = stringResource(R.string.mdblist_ratings_switch_title),
            subtitle = stringResource(R.string.mdblist_ratings_switch_subtitle),
            checked = state.switchOn,
            enabled = state.connected,
            onCheckedChange = onEnabledChange
        )
    }
    item(key = "mdblist_ratings_sources") {
        SettingsValueRow(
            title = stringResource(R.string.mdblist_ratings_sources_title),
            subtitle = stringResource(R.string.mdblist_ratings_sources_subtitle),
            value = stringResource(
                R.string.mdblist_ratings_sources_value,
                state.settings.shown.size,
                MdbListRatingSource.entries.size
            ),
            enabled = state.connected,
            onClick = onOpenSources
        )
    }
    item(key = "mdblist_ratings_from") {
        RatingsFromRow(
            connected = state.connected,
            accountName = accountName,
            onClick = if (state.connected) onOpenAccount else onAddKey
        )
    }
}

@Composable
private fun RatingsFromRow(connected: Boolean, accountName: String?, onClick: () -> Unit) {
    val marquee = NuvioTheme.marquee
    MarqueeFormRow(
        label = stringResource(R.string.mdblist_ratings_from_title),
        helper = stringResource(
            if (connected) R.string.mdblist_ratings_from_connected else R.string.mdblist_ratings_from_add
        ),
        onClick = onClick,
        minHeight = marquee.settings.rowMinHeight
    ) {
        if (connected) {
            Box(modifier = Modifier.size(marquee.settings.signInStatusDot).background(marquee.readyText, CircleShape))
            if (!accountName.isNullOrBlank()) {
                Text(text = accountName, style = marqueeFormRowValueStyle, color = marquee.readyText, maxLines = 1)
            }
        }
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}

/**
 * B2: the eight sources in the line's order, numbered; OK flips one; Move up / Move down (Home catalog order's pills,
 * dimmed at the ends) sit under the focused row. Rows are keyed by source, so a moved row keeps the focused pill.
 */
@Composable
internal fun MdbListRatingSourcesPanel(
    settings: MdbListRatingsSettings,
    onToggle: (MdbListRatingSource) -> Unit,
    onMove: (MdbListRatingSource, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val requesters = remember { MdbListRatingSource.entries.associateWith { FocusRequester() } }
    val first = remember { requesters.getValue(settings.order.first()) }
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = first
    ) {
        PanelEyebrow(text = stringResource(R.string.mdblist_ratings_sources_eyebrow))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            settings.order.forEachIndexed { index, source ->
                key(source) {
                    SourceRow(
                        position = index + 1,
                        source = source,
                        on = source !in settings.off,
                        canMoveUp = index > 0,
                        canMoveDown = index < settings.order.lastIndex,
                        focusRequester = requesters.getValue(source),
                        onToggle = { onToggle(source) },
                        onMove = { delta -> onMove(source, delta) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    position: Int,
    source: MdbListRatingSource,
    on: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    focusRequester: FocusRequester,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit
) {
    var focusWithin by remember { mutableStateOf(false) }
    Column(modifier = Modifier.onFocusChanged { focusWithin = it.hasFocus }.focusGroup()) {
        PlayerPanelRow(
            title = source.panelName,
            selected = false,
            onClick = onToggle,
            focusRequester = focusRequester,
            leading = {
                Text(
                    text = position.toString(),
                    style = NuvioTheme.textStyles.caption,
                    color = NuvioTheme.marquee.onImage3,
                    modifier = Modifier.width(POSITION_WIDTH)
                )
            },
            trailing = { focused -> MarqueeSwitch(checked = on, onLight = focused) }
        )
        if (focusWithin) {
            Row(
                modifier = Modifier.padding(
                    start = ACTIONS_INSET,
                    end = ACTIONS_INSET,
                    top = ACTIONS_TOP,
                    bottom = ACTIONS_INSET
                ),
                horizontalArrangement = Arrangement.spacedBy(ACTIONS_GAP),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CollectionRowAction(label = stringResource(R.string.cd_move_up), onClick = { onMove(-1) }, dimmed = !canMoveUp)
                CollectionRowAction(label = stringResource(R.string.cd_move_down), onClick = { onMove(1) }, dimmed = !canMoveDown)
            }
        }
    }
}

// `.s105n` 18 px number column; `.s105acts` padding 2/6/6/6 px, pills 8 px apart.
private val POSITION_WIDTH = 18.dp
private val ACTIONS_INSET = 6.dp
private val ACTIONS_TOP = 2.dp
private val ACTIONS_GAP = 8.dp
