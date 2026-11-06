package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.SyncAge
import com.nuvio.tv.core.sync.SyncStatus
import com.nuvio.tv.core.sync.syncAge
import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.screens.settings.SettingsPane
import com.nuvio.tv.ui.screens.settings.SettingsResultsPanel
import com.nuvio.tv.ui.screens.settings.SettingsSectionLabel
import com.nuvio.tv.ui.screens.settings.SettingsNote
import com.nuvio.tv.ui.screens.settings.SettingsValueRow
import com.nuvio.tv.ui.screens.settings.optionalFocusRequester
import com.nuvio.tv.ui.screens.settings.rememberSettingsReturnTarget
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val ROW_SIGN_IN = "account_sign_in"

/** How often the sync line's "N min ago" re-reads the clock. */
private const val SYNC_LINE_TICK_MS = 30_000L

/**
 * Spec 31, Account pane (sandbox u11-settings-marquee v6, stages `account-in`, `signout`,
 * `account-out`). Signed in: the account block with the sync line, Sync now, Server, the
 * read-only Synced data table, Sign out (confirm panel). Signed out: what sync does, Sign in,
 * Server, a note on how other devices catch up. Rows wait for the auth state and, signed in, the
 * stored sync time (the line must never read "not synced" before it has loaded).
 */
@Composable
internal fun AccountSettingsInline(
    onNavigateToAuthQrSignIn: () -> Unit,
    initialFocusRequester: FocusRequester?,
    viewModel: AccountViewModel = hiltViewModel(),
    serverViewModel: ServerConnectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val activeServer = serverViewModel.uiState.collectAsStateWithLifecycle().value.activeServer
    val signInRow = rememberSettingsReturnTarget(ROW_SIGN_IN)
    val toast = LocalMarqueeToast.current
    var showServerPicker by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val serverValue = stringResource(
        if (activeServer.isCustom) R.string.account_server_custom else R.string.account_server_official,
        activeServer.backendUrl.safeHost()
    )
    val syncRunningMessage = stringResource(R.string.account_sync_now_running)
    val syncThrottledMessage = stringResource(R.string.account_sync_now_throttled)
    val openServerPicker = { showServerPicker = true }

    SettingsPane(
        title = stringResource(R.string.settings_account),
        subtitle = stringResource(R.string.settings_account_section_subtitle)
    ) {
        when (val authState = uiState.authState) {
            is AuthState.Loading -> Unit
            is AuthState.SignedOut -> signedOutItems(
                emailLogin = viewModel.usesEmailPasswordLogin,
                serverValue = serverValue,
                onSignIn = { signInRow.launch(onNavigateToAuthQrSignIn) },
                onServer = openServerPicker,
                signInModifier = Modifier
                    .focusRequester(signInRow.requester)
                    .optionalFocusRequester(initialFocusRequester)
            )
            is AuthState.FullAccount -> {
                val status = syncStatus ?: return@SettingsPane
                signedInItems(
                    email = authState.email,
                    profile = activeProfile,
                    status = status,
                    overview = uiState.syncOverview,
                    overviewLoading = uiState.isSyncOverviewLoading,
                    serverValue = serverValue,
                    onSyncNow = {
                        if (!viewModel.syncNow()) {
                            toast?.show(if (status.running) syncRunningMessage else syncThrottledMessage)
                        }
                    },
                    onServer = openServerPicker,
                    onSignOut = { showSignOutConfirm = true },
                    syncNowModifier = Modifier.optionalFocusRequester(initialFocusRequester)
                )
            }
        }
    }

    ServerPickerHost(
        viewModel = serverViewModel,
        showPicker = showServerPicker,
        onPickerDismiss = { showServerPicker = false }
    )

    if (showSignOutConfirm) {
        AccountSignOutConfirmationDialog(
            onConfirm = {
                viewModel.signOut()
                showSignOutConfirm = false
            },
            onDismiss = { showSignOutConfirm = false }
        )
    }
}

/** Signed out (stage `account-out`): what sync does, Sign in, Server, how other devices catch up. */
private fun LazyListScope.signedOutItems(
    emailLogin: Boolean,
    serverValue: String,
    onSignIn: () -> Unit,
    onServer: () -> Unit,
    signInModifier: Modifier
) {
    item(key = "account_intro") {
        Text(
            text = stringResource(R.string.account_sync_intro),
            style = NuvioTheme.textStyles.meta,
            color = NuvioTheme.marquee.onImage2,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .padding(horizontal = NuvioTheme.spacing.xxs)
                .padding(bottom = NuvioTheme.spacing.xs)
        )
    }
    item(key = ROW_SIGN_IN) {
        SettingsValueRow(
            title = stringResource(
                if (emailLogin) R.string.account_signin_email_title else R.string.account_sign_in_qr_title
            ),
            subtitle = stringResource(
                if (emailLogin) R.string.account_signin_email_subtitle else R.string.account_sign_in_qr_subtitle
            ),
            value = null,
            trailingIcon = if (emailLogin) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.QrCode2,
            onClick = onSignIn,
            modifier = signInModifier
        )
    }
    serverRow(serverValue, subtitle = R.string.account_server_signed_out_subtitle, onClick = onServer)
    item(key = "account_other_devices") {
        SettingsNote(
            text = stringResource(R.string.account_sync_other_devices_note),
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
    }
}

/** Signed in (stage `account-in`): account block, Sync now, Server, Synced data, Sign out. */
private fun LazyListScope.signedInItems(
    email: String,
    profile: UserProfile?,
    status: SyncStatus,
    overview: List<ProfileSyncStats>?,
    overviewLoading: Boolean,
    serverValue: String,
    onSyncNow: () -> Unit,
    onServer: () -> Unit,
    onSignOut: () -> Unit,
    syncNowModifier: Modifier
) {
    item(key = "account_block") { AccountBlock(email = email, profile = profile, status = status) }
    item(key = "account_sync_now") {
        SettingsValueRow(
            title = stringResource(R.string.account_sync_now_title),
            subtitle = stringResource(R.string.account_sync_now_subtitle),
            value = null,
            trailingIcon = null,
            onClick = onSyncNow,
            modifier = syncNowModifier
        )
    }
    serverRow(serverValue, subtitle = null, onClick = onServer)
    if (!overview.isNullOrEmpty()) {
        item(key = "account_synced_label") {
            SettingsSectionLabel(text = stringResource(R.string.account_synced_data))
        }
        item(key = "account_synced_table") { SyncedDataTable(rows = overview) }
    } else if (overview == null && overviewLoading) {
        item(key = "account_synced_loading") {
            SettingsNote(text = stringResource(R.string.account_loading_sync))
        }
    }
    item(key = "account_sign_out") {
        SettingsValueRow(
            title = stringResource(R.string.account_sign_out_action),
            value = null,
            destructive = true,
            trailingIcon = null,
            onClick = onSignOut,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
    }
}

private fun LazyListScope.serverRow(value: String, @StringRes subtitle: Int?, onClick: () -> Unit) {
    item(key = "account_server") {
        SettingsValueRow(
            title = stringResource(R.string.account_server_title),
            subtitle = subtitle?.let { stringResource(it) },
            value = value,
            onClick = onClick
        )
    }
}

/** `.l11acc`: avatar, SIGNED IN, the email and the sync line. Not focusable. */
@Composable
private fun AccountBlock(email: String, profile: UserProfile?, status: SyncStatus) {
    val tokens = NuvioTheme.marquee.settings
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxs)
            .padding(bottom = tokens.accountBlockBottom),
        horizontalArrangement = Arrangement.spacedBy(tokens.accountGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileAvatarCircle(
            name = profile?.name ?: email,
            colorHex = profile?.avatarColorHex.orEmpty(),
            size = tokens.accountAvatarSize,
            art = profile?.avatarUrl?.let(AvatarArt::Image)
        )
        Column {
            Text(
                text = stringResource(R.string.account_signed_in_label).uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = NuvioTheme.marquee.readyText
            )
            Text(
                text = email,
                style = NuvioTheme.textStyles.profileName,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SyncLine(status)
        }
    }
}

/** `.l11sy`: "Synced 2 min ago · 3 changes waiting" (the count in amber, hidden at 0). */
@Composable
private fun SyncLine(status: SyncStatus) {
    val marquee = NuvioTheme.marquee
    val now by produceState(System.currentTimeMillis(), status.lastOkAtMs) {
        value = System.currentTimeMillis()
        while (true) {
            delay(SYNC_LINE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val ageText = if (status.running) {
        stringResource(R.string.account_sync_line_running)
    } else {
        when (val age = syncAge(now, status.lastOkAtMs)) {
            SyncAge.Never -> stringResource(R.string.account_sync_line_never)
            SyncAge.JustNow -> stringResource(R.string.account_sync_line_just_now)
            is SyncAge.Minutes -> pluralStringResource(R.plurals.account_sync_line_minutes, age.count, age.count)
            is SyncAge.Hours -> pluralStringResource(R.plurals.account_sync_line_hours, age.count, age.count)
            is SyncAge.Days -> pluralStringResource(R.plurals.account_sync_line_days, age.count, age.count)
        }
    }
    Row(
        modifier = Modifier.padding(top = NuvioTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = marquee.onImage2,
            modifier = Modifier.size(14.dp)
        )
        Text(text = ageText, style = NuvioTheme.textStyles.settingsNote, color = marquee.onImage2)
        if (status.pending > 0) {
            Text(text = "·", style = NuvioTheme.textStyles.settingsNote, color = marquee.onImage2)
            Text(
                text = pluralStringResource(R.plurals.account_changes_waiting, status.pending, status.pending),
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioPrimitives.warning,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** `.l11tbl`: server counts per profile, read-only (not focusable). */
@Composable
private fun SyncedDataTable(rows: List<ProfileSyncStats>) {
    val tokens = NuvioTheme.marquee.settings
    val headers = listOf(
        stringResource(R.string.account_stat_addons),
        stringResource(R.string.account_stat_library),
        stringResource(R.string.account_stat_progress),
        stringResource(R.string.account_stat_watched)
    )
    SettingsResultsPanel {
        TableRow {
            Text(
                text = stringResource(R.string.account_table_profile).uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = NuvioTheme.marquee.onImage3,
                modifier = Modifier.weight(NAME_COLUMN_WEIGHT)
            )
            headers.forEach { header ->
                Text(
                    text = header.uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = NuvioTheme.marquee.onImage3,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        rows.forEach { row ->
            TableRow {
                Row(
                    modifier = Modifier.weight(NAME_COLUMN_WEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatarCircle(
                        name = row.profileName,
                        colorHex = row.avatarColorHex,
                        size = tokens.tableAvatarSize,
                        art = row.avatarUrl?.let(AvatarArt::Image)
                    )
                    Text(
                        text = row.profileName,
                        style = NuvioTheme.textStyles.settingsResultLabel,
                        color = NuvioPrimitives.white,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                listOf(row.addons, row.library, row.watchProgress, row.watchedItems).forEach { count ->
                    Text(
                        text = count.toString(),
                        style = NuvioTheme.textStyles.settingsResultLabel,
                        color = NuvioTheme.marquee.onImage2,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** The mockup grid is 1.7fr for the profile, 1fr per count. */
private const val NAME_COLUMN_WEIGHT = 1.7f

@Composable
private fun TableRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NuvioTheme.marquee.settings.tableCellPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
