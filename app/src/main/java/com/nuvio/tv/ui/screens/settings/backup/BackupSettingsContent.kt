package com.nuvio.tv.ui.screens.settings.backup

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.backup.BackupPhoneMode
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeSwitch
import com.nuvio.tv.ui.screens.settings.SettingsPane
import com.nuvio.tv.ui.screens.settings.SettingsSectionLabel
import com.nuvio.tv.ui.screens.settings.SettingsValueRow
import com.nuvio.tv.ui.screens.settings.optionalFocusRequester
import com.nuvio.tv.ui.theme.NuvioPrimitives
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Settings › Backup (Spec 101 S7, sandbox spec-101-backup-and-restore v12): b1's pane, and over it the one
 * panel [BackupViewModel.panel] names. Shown on the primary profile only (SettingsNavigation).
 */
@Composable
internal fun BackupSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val pane by viewModel.pane.collectAsStateWithLifecycle()
    val panel by viewModel.panel.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val toast = LocalMarqueeToast.current
    val context = LocalContext.current
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        val text = when (shown) {
            BackupMessage.NoNetwork -> R.string.error_network_required
            BackupMessage.NoPort -> R.string.error_server_ports_unavailable
        }
        toast?.show(context.getString(text))
        viewModel.messageShown()
    }
    val shortDate = rememberDateFormat("dMMM")

    SettingsPane(title = stringResource(R.string.settings_backup), subtitle = stringResource(R.string.backup_pane_subtitle)) {
        item(key = "back_up_label") { SettingsSectionLabel(stringResource(R.string.backup_section_back_up)) }
        item(key = "now") {
            SettingsValueRow(
                title = stringResource(R.string.backup_now),
                subtitle = stringResource(R.string.backup_now_sub),
                value = pane.lastBackupAtMs?.let(shortDate),
                onClick = { viewModel.openBackUp(toPhone = false) },
                modifier = Modifier.optionalFocusRequester(initialFocusRequester),
            )
        }
        item(key = "phone_save") {
            SettingsValueRow(
                title = stringResource(R.string.backup_to_phone),
                subtitle = stringResource(R.string.backup_to_phone_sub),
                value = null,
                onClick = { viewModel.openBackUp(toPhone = true) },
            )
        }
        item(key = "restore_label") { SettingsSectionLabel(stringResource(R.string.backup_section_restore)) }
        item(key = "tv") {
            SettingsValueRow(
                title = stringResource(R.string.backup_restore_tv),
                subtitle = stringResource(R.string.backup_restore_tv_sub),
                value = pane.filesOnTv.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.backup_restore_tv_files, it, it) },
                onClick = viewModel::openRestoreList,
            )
        }
        item(key = "phone_restore") {
            SettingsValueRow(
                title = stringResource(R.string.backup_restore_phone),
                subtitle = stringResource(R.string.backup_restore_phone_sub),
                value = null,
                onClick = { viewModel.openPhone(BackupPhoneMode.RESTORE) },
            )
        }
        item(key = "safety") {
            BackupFootNote(
                icon = Icons.Filled.VerifiedUser,
                text = leadText(stringResource(R.string.backup_safety_title), " " + stringResource(R.string.backup_safety_body)),
            )
        }
    }

    BackupPanelHost(panel, viewModel)
}

@Composable
private fun BackupPanelHost(panel: BackupPanel, viewModel: BackupViewModel) {
    when (panel) {
        BackupPanel.None -> Unit
        is BackupPanel.Choose -> ChoosePanel(panel, viewModel)
        is BackupPanel.SetPin -> BackupPinEntry(
            eyebrow = stringResource(R.string.backup_pin_eyebrow),
            title = stringResource(if (panel.confirming) R.string.backup_pin_again else R.string.backup_pin_set),
            note = stringResource(R.string.backup_pin_set_note),
            error = if (panel.mismatch) stringResource(R.string.backup_pin_mismatch) else null,
            primary = stringResource(if (panel.working) R.string.backup_backing_up else R.string.backup_next),
            working = panel.working,
            resetKey = panel.confirming to panel.mismatch,
            onSkip = viewModel::skipPin,
            onSubmit = viewModel::pinEntered,
            onDismiss = viewModel::dismiss,
        )
        is BackupPanel.Saved -> SavedPanel(panel, viewModel)
        is BackupPanel.CantBackUp -> CantBackUpPanel(panel.problem, viewModel::dismiss)
        is BackupPanel.Phone -> PhonePanel(panel, viewModel::dismiss)
        is BackupPanel.RestoreList -> RestoreListPanel(panel, viewModel)
        is BackupPanel.Checklist -> ChecklistPanel(panel, viewModel)
        is BackupPanel.EnterPin -> BackupPinEntry(
            eyebrow = stringResource(R.string.backup_pin_eyebrow),
            title = stringResource(R.string.backup_pin_enter),
            note = stringResource(R.string.backup_pin_enter_note),
            error = if (panel.wrong) stringResource(R.string.backup_pin_wrong) else null,
            primary = stringResource(if (panel.working) R.string.backup_restoring else R.string.backup_restore_confirm),
            working = panel.working,
            resetKey = panel.wrong,
            onSkip = viewModel::skipRestorePin,
            onSubmit = viewModel::restoreWithPin,
            onDismiss = viewModel::dismiss,
        )
        is BackupPanel.CantRestore -> CantRestorePanel(panel, viewModel::dismiss)
    }
}

// ---- b2 ----

@Composable
private fun ChoosePanel(panel: BackupPanel.Choose, viewModel: BackupViewModel) {
    val secretsFocus = remember { FocusRequester() }
    val summary = panel.summary
    val count = rememberCount()
    BackupPanelFrame(
        eyebrow = stringResource(R.string.backup_section_back_up),
        onDismiss = viewModel::dismiss,
        initialFocus = secretsFocus,
        actions = {
            BackupSecondaryButton(stringResource(R.string.action_cancel), viewModel::dismiss)
            BackupPrimaryButton(
                label = stringResource(if (panel.working) R.string.backup_backing_up else R.string.backup_next),
                onClick = viewModel::chooseNext,
                working = panel.working,
            )
        },
    ) {
        BackupRow(
            title = stringResource(R.string.backup_part_profiles),
            subtitle = summary?.let { stringResource(R.string.backup_part_profiles_sub, it.profiles.joinToString(", ") { p -> p.name }) },
            leading = { BackupRowIcon(Icons.Filled.People) },
            trailing = summary?.profiles?.size?.let(count),
            plain = true,
        )
        BackupRow(
            title = stringResource(R.string.backup_part_settings),
            subtitle = stringResource(R.string.backup_part_settings_sub),
            leading = { BackupRowIcon(Icons.Filled.Palette) },
            plain = true,
        )
        BackupRow(
            title = stringResource(R.string.backup_part_addons),
            subtitle = stringResource(R.string.backup_part_addons_sub),
            leading = { BackupRowIcon(Icons.Filled.Link) },
            trailing = summary?.addons?.let(count),
            plain = true,
        )
        BackupRow(
            title = stringResource(R.string.backup_part_library),
            subtitle = stringResource(R.string.backup_part_library_sub),
            leading = { BackupRowIcon(Icons.Filled.GridView) },
            trailing = summary?.library?.let(count),
            plain = true,
        )
        BackupRow(
            title = stringResource(R.string.backup_part_history),
            subtitle = stringResource(R.string.backup_part_history_sub),
            leading = { BackupRowIcon(Icons.Filled.History) },
            trailing = summary?.history?.let(count),
            plain = true,
        )
        BackupRow(
            title = stringResource(R.string.backup_secrets),
            subtitle = stringResource(R.string.backup_secrets_sub),
            leading = { BackupRowIcon(Icons.Filled.Lock) },
            end = { MarqueeSwitch(panel.secrets) },
            onClick = { viewModel.setSecrets(!panel.secrets) },
            modifier = Modifier.padding(top = 6.dp).focusRequester(secretsFocus),
        )
    }
}

// ---- b4 ----

@Composable
private fun SavedPanel(panel: BackupPanel.Saved, viewModel: BackupViewModel) {
    val doneFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val number = rememberCount()
    val summary = panel.summary
    val chips = buildList {
        summary?.let { add(BackupChip(Icons.Filled.People, pluralStringResource(R.plurals.backup_chip_profiles, it.profiles.size, it.profiles.size))) }
        add(BackupChip(Icons.Filled.Palette, stringResource(R.string.backup_part_settings)))
        summary?.addons?.let { add(BackupChip(Icons.Filled.Link, pluralStringResource(R.plurals.backup_chip_addons, it, it))) }
        summary?.library?.let { add(BackupChip(Icons.Filled.GridView, stringResource(R.string.backup_chip_library, number(it)))) }
        summary?.history?.let { add(BackupChip(Icons.Filled.History, stringResource(R.string.backup_chip_history, number(it)))) }
        if (panel.sealed) add(BackupChip(Icons.Filled.Lock, stringResource(R.string.backup_chip_secrets)))
    }
    BackupPanelFrame(
        eyebrow = stringResource(R.string.backup_saved_eyebrow),
        onDismiss = viewModel::dismiss,
        initialFocus = doneFocus,
        actions = {
            BackupSecondaryButton(stringResource(R.string.backup_save_to_phone_too), { viewModel.openPhone(BackupPhoneMode.SAVE) })
            BackupPrimaryButton(stringResource(R.string.backup_done), viewModel::dismiss, focusRequester = doneFocus)
        },
    ) {
        BackupFileCard(
            icon = Icons.Filled.VerifiedUser,
            title = panel.backup.name,
            detail = stringResource(R.string.backup_saved_where, android.text.format.Formatter.formatShortFileSize(context, panel.backup.sizeBytes)),
        )
        BackupChips(chips)
        BackupLine(stringResource(R.string.backup_keep_private))
    }
}

// ---- b13 and the backup failures ----

@Composable
private fun CantBackUpPanel(problem: BackupProblem, onDismiss: () -> Unit) {
    val (title, body) = when (problem) {
        is BackupProblem.TooLarge -> when {
            problem.profileName != null && problem.store.isHistoryStore() ->
                stringResource(R.string.backup_too_large_title) to stringResource(R.string.backup_too_large_body, problem.profileName)
            problem.profileName != null ->
                stringResource(R.string.backup_too_large_other_title) to stringResource(R.string.backup_too_large_other_body, problem.profileName)
            else -> stringResource(R.string.backup_too_large_other_title) to stringResource(R.string.backup_too_large_tv_body)
        }
        BackupProblem.Failed -> stringResource(R.string.backup_failed_title) to stringResource(R.string.backup_failed_body)
    }
    VerdictPanel(stringResource(R.string.backup_cant_eyebrow), source = null, title = title, body = body, onDismiss = onDismiss)
}

/** The stores b13's "watch history" line names. */
private fun String?.isHistoryStore(): Boolean = this != null && this.startsWith("watch")

// ---- b5 ----

@Composable
private fun PhonePanel(panel: BackupPanel.Phone, onDismiss: () -> Unit) {
    val doneFocus = remember { FocusRequester() }
    val save = panel.mode == BackupPhoneMode.SAVE
    BackupPanelFrame(
        eyebrow = stringResource(if (save) R.string.backup_phone_save_eyebrow else R.string.backup_phone_restore_eyebrow),
        onDismiss = onDismiss,
        initialFocus = doneFocus,
        actions = { BackupPrimaryButton(stringResource(R.string.backup_done), onDismiss, focusRequester = doneFocus) },
    ) {
        BackupQrBlock(
            url = panel.url,
            title = stringResource(if (save) R.string.backup_phone_save_title else R.string.backup_phone_restore_title),
            body = stringResource(R.string.backup_phone_wifi),
        )
        if (save) BackupLine(stringResource(R.string.backup_phone_private))
    }
}

// ---- b7 ----

@Composable
private fun RestoreListPanel(panel: BackupPanel.RestoreList, viewModel: BackupViewModel) {
    val firstFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val longDate = rememberDateFormat("dMMMyyyy")
    BackupPanelFrame(
        eyebrow = stringResource(R.string.backup_restore_tv),
        onDismiss = viewModel::dismiss,
        initialFocus = if (panel.items.isEmpty()) cancelFocus else firstFocus,
        actions = { BackupSecondaryButton(stringResource(R.string.action_cancel), viewModel::dismiss, Modifier.focusRequester(cancelFocus)) },
    ) {
        if (panel.items.isEmpty()) BackupLine(stringResource(R.string.backup_list_empty))
        panel.items.forEachIndexed { index, item ->
            val header = item.header
            val copy = item.file is RestoreFile.Copy
            val parts = listOfNotNull(
                stringResource(if (copy) R.string.backup_list_copy else R.string.web_backup_item_manual),
                header?.let { pluralStringResource(R.plurals.web_backup_item_profiles, it.profiles.size, it.profiles.size) },
                android.text.format.Formatter.formatShortFileSize(context, item.sizeBytes),
            )
            BackupRow(
                title = header?.let { longDate(it.createdAtMs) } ?: item.file.fileName,
                subtitle = parts.joinToString(" · "),
                leading = { BackupRowIcon(if (copy) Icons.Filled.VerifiedUser else Icons.Filled.Description) },
                titleEnd = if (header?.sealed == true) {
                    { BackupLockMark() }
                } else {
                    null
                },
                onClick = { viewModel.pick(item) },
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
        BackupLine(stringResource(R.string.backup_list_other_device))
    }
}

// ---- b8, b9 ----

@Composable
private fun ChecklistPanel(panel: BackupPanel.Checklist, viewModel: BackupViewModel) {
    val confirmFocus = remember { FocusRequester() }
    val header = panel.header
    val longDate = rememberDateFormat("dMMMyyyy")
    val pairSub = stringResource(if (panel.signedOut) R.string.backup_restore_pair_out else R.string.backup_restore_pair_in)
    BackupPanelFrame(
        eyebrow = stringResource(R.string.backup_restore_eyebrow),
        onDismiss = viewModel::dismiss,
        initialFocus = confirmFocus,
        actions = {
            BackupSecondaryButton(stringResource(R.string.action_cancel), viewModel::dismiss)
            BackupPrimaryButton(
                label = stringResource(if (panel.working) R.string.backup_restoring else R.string.backup_restore_confirm),
                onClick = viewModel::confirmRestore,
                working = panel.working,
                enabled = panel.canRestore(),
                focusRequester = confirmFocus,
            )
        },
    ) {
        BackupSourceLine(sourceLine(header.deviceName, longDate(header.createdAtMs), header.appVersionName))
        BackupPanelSection(stringResource(R.string.backup_restore_matched))
        panel.matching.matches.forEach { match ->
            val backupName = header.profiles.first { it.id == match.backupId }.name
            val tvName = panel.tvNames[match.tvId].orEmpty()
            BackupRow(
                title = stringResource(R.string.backup_restore_pair, backupName, tvName),
                subtitle = if (match.backupId == 1) stringResource(R.string.backup_restore_primary, pairSub) else pairSub,
                leading = { BackupTickBox(match.backupId in panel.profiles) },
                onClick = { viewModel.toggleProfile(match.backupId) },
            )
        }
        BackupRow(
            title = stringResource(R.string.backup_restore_device),
            subtitle = stringResource(R.string.backup_restore_device_sub, header.deviceName.ifEmpty { stringResource(R.string.backup_unknown_device) }),
            leading = { BackupTickBox(panel.device) },
            onClick = viewModel::toggleDevice,
        )
        if (panel.hasSecrets) {
            BackupRow(
                title = stringResource(R.string.backup_restore_secrets),
                subtitle = stringResource(if (header.sealed) R.string.backup_restore_secrets_sub else R.string.backup_restore_secrets_copy_sub),
                leading = { BackupTickBox(panel.secrets) },
                titleEnd = if (header.sealed) {
                    { BackupLockMark() }
                } else {
                    null
                },
                onClick = viewModel::toggleSecrets,
            )
        }
        val skipped = panel.matching.skipped.map {
            if (it.reason == com.nuvio.tv.core.backup.SkippedProfile.Reason.NOT_ON_TV) {
                stringResource(R.string.backup_restore_skipped_not_on_tv, it.name)
            } else {
                stringResource(R.string.backup_restore_skipped_not_unique, it.name)
            }
        }
        if (skipped.isNotEmpty()) {
            BackupLine(leadText(stringResource(R.string.backup_restore_skipped), " " + skipped.joinToString(" · ")))
        }
        panel.matching.matches.filter { it.addonsDiffer && it.backupId in panel.profiles && panel.signedOut }.forEach { match ->
            BackupLine(stringResource(R.string.backup_restore_addons_kept, panel.tvNames[match.tvId].orEmpty()))
        }
        if (panel.signedOut) BackupLine(stringResource(R.string.backup_restore_sign_in_later))
        BackupLine(stringResource(if (panel.signedOut) R.string.backup_restore_warn_out else R.string.backup_restore_warn_in), info = false)
    }
}

// ---- b12 and the restore failures ----

@Composable
private fun CantRestorePanel(panel: BackupPanel.CantRestore, onDismiss: () -> Unit) {
    val header = panel.header
    val longDate = rememberDateFormat("dMMMyyyy")
    val (title, body) = when (val problem = panel.problem) {
        is RestoreProblem.Newer -> stringResource(R.string.web_backup_error_newer) to
            stringResource(R.string.backup_newer_body, problem.versionName, BuildConfig.VERSION_NAME)
        is RestoreProblem.Older -> stringResource(R.string.web_backup_error_older) to
            stringResource(R.string.backup_older_body, problem.versionName, BuildConfig.VERSION_NAME, com.nuvio.tv.core.backup.MIN_BACKUP_VERSION_LABEL)
        RestoreProblem.Damaged -> stringResource(R.string.web_backup_error_damaged) to stringResource(R.string.backup_restore_nothing_changed)
        RestoreProblem.TooLarge -> stringResource(R.string.web_backup_error_too_large) to stringResource(R.string.backup_restore_nothing_changed)
        RestoreProblem.NoSpace -> stringResource(R.string.backup_restore_no_space_title) to stringResource(R.string.backup_restore_no_space_body)
        RestoreProblem.CopyFailed -> stringResource(R.string.backup_restore_copy_failed_title) to stringResource(R.string.backup_restore_copy_failed_body)
        RestoreProblem.Failed -> stringResource(R.string.backup_restore_failed_title) to stringResource(R.string.backup_restore_failed_body)
    }
    VerdictPanel(
        eyebrow = stringResource(R.string.backup_cant_restore_eyebrow),
        source = header?.let { sourceLine(it.deviceName, longDate(it.createdAtMs), it.appVersionName) },
        title = title,
        body = body,
        onDismiss = onDismiss,
    )
}

/** b12 / b13's shape: an amber verdict card and one OK. */
@Composable
private fun VerdictPanel(eyebrow: String, source: androidx.compose.ui.text.AnnotatedString?, title: String, body: String, onDismiss: () -> Unit) {
    val okFocus = remember { FocusRequester() }
    BackupPanelFrame(
        eyebrow = eyebrow,
        onDismiss = onDismiss,
        initialFocus = okFocus,
        actions = { BackupPrimaryButton(stringResource(R.string.backup_ok), onDismiss, focusRequester = okFocus) },
    ) {
        source?.let { BackupSourceLine(it) }
        BackupFileCard(icon = Icons.Outlined.ErrorOutline, title = title, detail = body, warn = true)
    }
}

// ---- helpers ----

/** `.b1src`: "SHIELD Android TV · 16 Nov 2026, 21:42 · Nuvio KE 1.4.0", the device name in white. */
@Composable
private fun sourceLine(deviceName: String, date: String, versionName: String): androidx.compose.ui.text.AnnotatedString {
    val device = deviceName.ifEmpty { stringResource(R.string.backup_unknown_device) }
    val full = stringResource(R.string.backup_restore_source, device, date, versionName)
    return leadText(device, full.removePrefix(device))
}

/** `.b1lock`: the amber lock after a sealed backup's title. */
@Composable
private fun BackupLockMark() {
    Icon(
        Icons.Filled.Lock,
        contentDescription = stringResource(R.string.cd_backup_sealed),
        tint = NuvioPrimitives.premium,
        modifier = Modifier.padding(start = 5.dp).size(12.dp),
    )
}

/** Grouped thousands ("1,184"), in the app's locale. */
@Composable
private fun rememberCount(): (Int) -> String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        val format = NumberFormat.getIntegerInstance(locale)
        val f: (Int) -> String = { format.format(it) }
        f
    }
}

/** [skeleton] (day, month, maybe year) plus the time in this TV's 12/24-hour setting, in the app's locale. */
@Composable
private fun rememberDateFormat(skeleton: String): (Long) -> String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, skeleton) {
        val hours = if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "hm"
        val format = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton + hours), locale)
        val f: (Long) -> String = { format.format(Date(it)) }
        f
    }
}
