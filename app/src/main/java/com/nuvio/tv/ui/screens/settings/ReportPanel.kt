package com.nuvio.tv.ui.screens.settings

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.report.ReportFile
import com.nuvio.tv.core.report.ReportParts
import com.nuvio.tv.core.report.ReportSummary
import com.nuvio.tv.core.report.UploadResult
import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.components.sources.SourceFailureMarks
import com.nuvio.tv.ui.screens.collection.CollectionsHeaderButton
import com.nuvio.tv.ui.screens.detail.formatDayMonth
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val WEEK_MS = 7 * 24 * HOUR_MS
private const val SCROLL_STEP_PX = 120f
private const val PAN_STEP_CHARS = 24

/** The six parts of D3, in its order: each a B1 row that opens its text (B2/B3). */
internal enum class ReportPart(val title: Int, val icon: ImageVector) {
    TV(R.string.report_part_tv, Icons.Outlined.Tv),
    APP(R.string.report_part_app, Icons.Outlined.Info),
    CLOSES(R.string.report_part_closes, Icons.Filled.Warning),
    ERRORS(R.string.report_part_errors, Icons.Outlined.PlayCircleOutline),
    SETTINGS(R.string.report_part_settings, Icons.Filled.Tune),
    LOG(R.string.report_part_log, Icons.Filled.Description);

    fun text(parts: ReportParts): String = when (this) {
        TV -> parts.thisTv
        APP -> parts.app
        CLOSES -> parts.lastCloses
        ERRORS -> parts.playbackErrors
        SETTINGS -> parts.settings
        LOG -> parts.appLog
    }
}

/**
 * Spec 116 P5 (sandbox spec-116-report-sharing v6): the panel while [state] is open (B1, C1, E1),
 * a part's text in its place while one is open (B2/B3; Back returns to that part's row), and the
 * code screen once sent (D1). Every way out calls [onClose].
 */
@Composable
internal fun ReportPanelHost(
    state: ReportState,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onClose: () -> Unit
) {
    var openPart by remember { mutableStateOf<ReportPart?>(null) }
    var returnPart by remember { mutableStateOf<ReportPart?>(null) }
    // A new phase (sent, failed, cancelled) moves focus to its button, not back to a part's row.
    val phase = when (state) {
        is ReportState.Ready -> "ready:${state.failure}"
        else -> state::class.simpleName
    }
    LaunchedEffect(phase) { returnPart = null }
    when (state) {
        // The caller composes this only while open, so a reopen starts with no part open.
        ReportState.Closed -> Unit
        is ReportState.Sent -> ReportCodeScreen(state.code, onDone = onClose)
        else -> {
            val file = (state as? ReportState.Ready)?.file ?: (state as? ReportState.Sending)?.file
            val part = openPart
            if (file != null && part != null) {
                ReportPartView(part, file) {
                    openPart = null
                    returnPart = part
                }
            } else {
                ReportSidePanel(
                    state = state,
                    returnPart = returnPart,
                    onOpenPart = { openPart = it },
                    onSend = onSend,
                    onCancelSend = onCancelSend,
                    onDismiss = onClose
                )
            }
        }
    }
}

/**
 * B1 / C1 / E1: the Update panel's shape. What will be sent and its size, the six parts with their
 * summaries, the scrub note; then Send and Cancel, the upload's progress and Cancel, or the
 * failure and Try again (none after a refusal) and Cancel. Back calls [onDismiss].
 */
@Composable
private fun ReportSidePanel(
    state: ReportState,
    returnPart: ReportPart?,
    onOpenPart: (ReportPart) -> Unit,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val context = LocalContext.current
    val actionFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val rowFocus = remember { ReportPart.entries.associateWith { FocusRequester() } }
    val ready = state as? ReportState.Ready
    val sending = state as? ReportState.Sending
    val file = ready?.file ?: sending?.file
    val canSend = ready?.canSend == true
    val initialFocus = returnPart?.let { rowFocus.getValue(it) } ?: if (canSend) actionFocus else cancelFocus
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = initialFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.report_panel_label))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = marquee.settings.panelTextInset)) {
                Text(
                    text = stringResource(R.string.report_panel_title),
                    style = NuvioTheme.textStyles.panelStateTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = NuvioPrimitives.white
                )
                Text(
                    text = if (file != null) {
                        stringResource(R.string.report_panel_meta, Formatter.formatShortFileSize(context, file.bytes.size.toLong()))
                    } else {
                        stringResource(R.string.report_panel_building)
                    },
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage3
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (file != null) {
                ReportPart.entries.forEach { part ->
                    ReportPartRow(
                        part = part,
                        summary = summaryLine(part, file.summary),
                        focusRequester = rowFocus.getValue(part),
                        onClick = { onOpenPart(part) }
                    )
                }
                ReportPrivacyNote()
            }
        }
        val error = when {
            state == ReportState.BuildFailed -> R.string.report_build_failed
            else -> ready?.failure?.let(::failureLine)
        }
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsPanelErrorLine(stringResource(error))
        }
        if (sending != null && file != null) ReportProgressBar(sending.sent, file.bytes.size.toLong())
        MarqueePanelActions {
            if (canSend) {
                MarqueePrimaryButton(
                    label = stringResource(if (ready?.failure != null) R.string.update_try_again else R.string.report_send),
                    icon = if (ready?.failure != null) null else Icons.AutoMirrored.Filled.Send,
                    onClick = onSend,
                    focusRequester = actionFocus,
                    compact = true
                )
            }
            CollectionsHeaderButton(
                label = stringResource(R.string.action_cancel),
                onClick = if (sending != null) onCancelSend else onDismiss,
                modifier = Modifier.focusRequester(cancelFocus)
            )
            if (sending != null && file != null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.report_sending,
                        Formatter.formatShortFileSize(context, sending.sent),
                        Formatter.formatShortFileSize(context, file.bytes.size.toLong())
                    ),
                    style = NuvioTheme.textStyles.caption.copy(fontFeatureSettings = "tnum"),
                    color = marquee.onImage2,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}

/** E1's line for a failed send. */
private fun failureLine(result: UploadResult): Int? = when (result) {
    UploadResult.NoInternet -> R.string.report_error_offline
    UploadResult.ServerUnavailable -> R.string.report_error_server
    UploadResult.RateLimited -> R.string.report_error_rate
    UploadResult.Refused -> R.string.report_error_refused
    is UploadResult.Sent -> null
}

/** `.l8pk`: the part's icon, title and summary, a chevron; a white pill when focused. */
@Composable
private fun ReportPartRow(part: ReportPart, summary: String, focusRequester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    PlayerPanelRow(
        title = stringResource(part.title),
        selected = false,
        onClick = onClick,
        // Ahead of the row's own focus target, which both observe (CMP-FOCUS-02).
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        subtitle = summary,
        focusRequester = focusRequester,
        trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        leading = {
            Icon(
                imageVector = part.icon,
                contentDescription = null,
                tint = if (focused) NuvioPrimitives.black else NuvioPrimitives.white,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

/** `.s116priv`: the green shield and what the scrub removes. */
@Composable
private fun ReportPrivacyNote() {
    val marquee = NuvioTheme.marquee
    Row(
        modifier = Modifier.padding(start = marquee.settings.panelTextInset, end = marquee.settings.panelTextInset, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = marquee.readyText,
            modifier = Modifier.padding(top = 1.dp).size(16.dp)
        )
        Text(text = stringResource(R.string.report_privacy), style = NuvioTheme.textStyles.settingsNote, color = marquee.onImage2)
    }
}

/** `.s52prog`: a 4 dp bar of [sent] over [total]. */
@Composable
private fun ReportProgressBar(sent: Long, total: Long) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 10.dp)
            .fillMaxWidth()
            .height(4.dp)
            .background(NuvioPrimitives.white.copy(alpha = 0.18f), shape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progressFraction(sent, total))
                .height(4.dp)
                .background(NuvioPrimitives.white, shape)
        )
    }
}

/** B1's line under [part]: only values [summary] kept (ReportSummary.shownIn). */
@Composable
private fun summaryLine(part: ReportPart, summary: ReportSummary): String = when (part) {
    ReportPart.TV -> listOfNotNull(
        summary.tv.model,
        summary.tv.android?.let { stringResource(R.string.report_tv_android, it) },
        summary.tv.ramMb?.let { stringResource(R.string.report_tv_ram, ((it + 512) / 1024).toInt()) },
        stringResource(R.string.report_tv_dolby_vision).takeIf { summary.tv.dolbyVision }
    ).joinToString(DOT)
    ReportPart.APP -> listOfNotNull(
        summary.app.versionName?.let { "$it (${summary.app.versionCode})" },
        when (summary.app.build) {
            "public" -> stringResource(R.string.report_build_public)
            "household" -> stringResource(R.string.report_build_household)
            "debug" -> stringResource(R.string.report_build_debug)
            else -> null
        },
        summary.app.abi
    ).joinToString(DOT)
    ReportPart.CLOSES -> closesLine(summary)
    ReportPart.ERRORS -> errorsLine(summary)
    ReportPart.SETTINGS -> settingsLine(summary)
    ReportPart.LOG -> stringResource(
        if (summary.log.trimmed) R.string.report_log_trimmed else R.string.report_log_day,
        NumberFormat.getIntegerInstance().format(summary.log.lines),
        Formatter.formatShortFileSize(LocalContext.current, summary.log.bytes.toLong())
    )
}

private const val DOT = " \u00b7 "

/** "Crash 2 h ago · memory kill yesterday at 21:14": the two newest. */
@Composable
private fun closesLine(summary: ReportSummary): String {
    if (summary.closes.isEmpty()) return stringResource(R.string.report_closes_none)
    val now = System.currentTimeMillis()
    return summary.closes.take(2).map { close ->
        val kind = when (close.kind) {
            "crash" -> R.string.report_close_crash
            "native-crash" -> R.string.report_close_native_crash
            "anr" -> R.string.report_close_anr
            else -> R.string.report_close_memory_kill
        }
        stringResource(R.string.report_close_entry, stringResource(kind), closeWhen(close.timeMs, now))
    }.joinToString(DOT).replaceFirstChar { it.uppercase() }
}

/** Today: "2 h ago" (Sources' ages); else "yesterday at 21:14" or "12 Nov at 21:14". */
@Composable
private fun closeWhen(timeMs: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val elapsed = (now - timeMs).coerceAtLeast(0L)
    val day = checkedDay(timeMs, LocalDate.now(zone), zone)
    if (day == CheckedDay.TODAY) {
        return when {
            elapsed < MINUTE_MS -> stringResource(R.string.failure_ago_just_now)
            elapsed < HOUR_MS -> stringResource(R.string.failure_ago_minutes, (elapsed / MINUTE_MS).toInt())
            else -> stringResource(R.string.failure_ago_hours, (elapsed / HOUR_MS).toInt())
        }
    }
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(timeMs))
    return if (day == CheckedDay.YESTERDAY) {
        stringResource(R.string.update_when_yesterday, time)
    } else {
        stringResource(R.string.update_when_date, formatDayMonth(Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()), time)
    }
}

/** "3 this week · Link expired, Too slow, Decoder stuck": the count, then up to three causes, newest first. */
@Composable
private fun errorsLine(summary: ReportSummary): String {
    val errors = summary.errors
    if (errors.isEmpty()) return stringResource(R.string.report_errors_none)
    val now = System.currentTimeMillis()
    val count = pluralStringResource(
        if (errors.all { now - it.timeMs <= WEEK_MS }) R.plurals.report_errors_week else R.plurals.report_errors_fortnight,
        errors.size,
        errors.size
    )
    val causes = errors.map { it.cause }.distinct().take(3).map { name ->
        stringResource(SourceFailureMarks.labelRes(FailureCause.entries.firstOrNull { it.name == name }))
    }
    return (listOf(count) + causes.joinToString(", ")).joinToString(DOT)
}

/** "ExoPlayer · frame rate matching · managed buffer", from the part's own lines (`default` = the app's default). */
@Composable
private fun settingsLine(summary: ReportSummary): String {
    val values = summary.settings.associate { it.key to it.value }
    return listOfNotNull(
        values["internal_player_engine"]?.let {
            stringResource(
                when (it) {
                    "MVP_PLAYER" -> R.string.report_engine_mpv
                    "AUTO" -> R.string.report_engine_auto
                    else -> R.string.report_engine_exoplayer
                }
            )
        },
        values["frame_rate_matching_mode"]?.let {
            stringResource(if (it == "OFF" || it == "default") R.string.report_afr_off else R.string.report_afr_on)
        },
        values["buffer_budget_managed"]?.let {
            stringResource(if (it == "false") R.string.report_buffer_custom else R.string.report_buffer_managed)
        }
    ).joinToString(DOT)
}

/**
 * B2 / B3: one part's text exactly as sent, in the opaque card over the dimmed screen. The header
 * names the part (App log: its window, lines and size; Playback settings: its count), the key of
 * the amber marks (B2), then the text; Playback settings in two columns (B3). Up and Down scroll;
 * Left and Right pan the lines sideways (not drawn in the mockup, whose box clips: a long line's end
 * must stay checkable, D1). Back and the Back button call [onBack].
 */
@Composable
private fun ReportPartView(part: ReportPart, file: ReportFile, onBack: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val card = marquee.settings
    val context = LocalContext.current
    val back = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val text = part.text(file.parts)
    val lines = remember(text) { if (text.isEmpty()) emptyList() else text.split('\n') }
    val listState = rememberLazyListState()
    val columnsState = rememberScrollState()
    val scrollable: ScrollableState = if (part == ReportPart.SETTINGS) columnsState else listState
    // Left / Right pan every line together: a long line's end is where a scrub miss would sit (D1).
    val measurer = rememberTextMeasurer()
    val charWidth = remember(measurer) { measurer.measure("M", LOG_STYLE).size.width }
    var boxWidth by remember { mutableIntStateOf(0) }
    var pan by remember(part) { mutableIntStateOf(0) }
    // ASCII lines by count x the monospace width; others (wide CJK fallbacks) measured, so no tail stays out of reach.
    val widest = remember(lines, charWidth) {
        lines.maxOfOrNull { line ->
            if (line.all { it.code < 128 }) line.length * charWidth
            else measurer.measure(line, LOG_STYLE, softWrap = false, maxLines = 1).size.width
        } ?: 0
    }
    val maxPan = (widest - boxWidth).coerceAtLeast(0)
    val meta = when (part) {
        ReportPart.LOG -> stringResource(
            if (file.summary.log.trimmed) R.string.report_part_log_meta_trimmed else R.string.report_part_log_meta,
            NumberFormat.getIntegerInstance().format(lines.size),
            Formatter.formatShortFileSize(context, text.toByteArray(Charsets.UTF_8).size.toLong())
        )
        ReportPart.SETTINGS -> {
            val count = lines.count { it.isNotBlank() && !it.startsWith("#") }
            pluralStringResource(R.plurals.report_part_settings_meta, count, count)
        }
        else -> stringResource(R.string.report_part_exact)
    }
    MarqueeVeilDialog(onDismissRequest = onBack, veil = NuvioPrimitives.black.copy(alpha = 0.82f), initialFocus = back) {
        val shape = RoundedCornerShape(card.signInCardRadius)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 96.dp, end = 96.dp, top = 48.dp, bottom = 24.dp)
                .background(card.signInCardFill, shape)
                .border(NuvioTheme.spacing.hairline, card.signInCardEdge, shape)
                .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 16.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> scope.launch { scrollable.animateScrollBy(SCROLL_STEP_PX) }
                        Key.DirectionUp -> scope.launch { scrollable.animateScrollBy(-SCROLL_STEP_PX) }
                        Key.DirectionRight -> pan = (pan + PAN_STEP_CHARS * charWidth).coerceAtMost(maxPan)
                        Key.DirectionLeft -> pan = (pan - PAN_STEP_CHARS * charWidth).coerceAtLeast(0)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(part.title),
                    style = NuvioTheme.textStyles.welcomeDevice,
                    color = NuvioPrimitives.white,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(text = meta, style = NuvioTheme.textStyles.caption, color = marquee.onImage2)
            }
            if (part != ReportPart.SETTINGS) RemovedKey()
            if (part == ReportPart.SETTINGS) {
                // B3: player, video and audio, then buffer and network and the network test.
                val split = lines.indexOfFirst { it.startsWith("# buffer") }.takeIf { it > 0 } ?: lines.size
                Row(
                    modifier = Modifier.weight(1f).verticalScroll(columnsState),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(lines.subList(0, split), lines.subList(split, lines.size)).forEach { column ->
                        Column(modifier = Modifier.weight(1f).logBox().onSizeChanged { boxWidth = it.width }) {
                            column.forEach { ReportTextLine(it, pan) }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false,
                    modifier = Modifier.weight(1f).fillMaxWidth().logBox().onSizeChanged { boxWidth = it.width }
                ) {
                    items(lines) { ReportTextLine(it, pan) }
                }
                // The log opens at its newest lines (B2: newest at the bottom).
                if (part == ReportPart.LOG) {
                    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.focusRequester(back)
                )
            }
        }
    }
}

/** `.s116lg`: the monospace box. */
@Composable
private fun Modifier.logBox(): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return this
        .fillMaxHeight()
        .background(NuvioPrimitives.black.copy(alpha = 0.45f), shape)
        .border(1.dp, NuvioPrimitives.white.copy(alpha = 0.08f), shape)
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .clipToBounds()
}

private val LOG_STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
private val MARK_FILL = Color(0xFFFFB74D)

/** A scrub marker (D6): `<secret>`, `<url host=…>`, `<addon=Name>` and the rest; nested ones match their inner part. */
private val MARKER = Regex("""<(url[^<>]*|addon=[^<>]*|secret|token|ip lan|ip|email|hash|magnet|id)>""")

/** threadtime's level letter after the pid and tid. */
private val LEVEL = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+ ([VDIWEF]) """)

/**
 * One line, unwrapped and moved [pan] px left: markers as amber marks (B2), warnings amber, errors
 * red, comment lines dim.
 */
@Composable
private fun ReportTextLine(line: String, pan: Int) {
    val dim = NuvioTheme.marquee.onImage3
    val styled = remember(line) { styledLine(line, dim) }
    Text(
        text = styled,
        style = LOG_STYLE,
        color = NuvioPrimitives.white.copy(alpha = 0.82f),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .offset { IntOffset(-pan, 0) }
    )
}

private fun styledLine(line: String, dim: Color): AnnotatedString = buildAnnotatedString {
    append(line)
    when {
        line.startsWith("#") || line.startsWith("---------") -> addStyle(SpanStyle(color = dim), 0, line.length)
        else -> LEVEL.find(line)?.let { m ->
            val color = when (m.groupValues[1]) {
                "W" -> NuvioPrimitives.warningOnTint
                "E", "F" -> NuvioPrimitives.errorOnImage
                else -> null
            }
            if (color != null) addStyle(SpanStyle(color = color), m.groups[1]!!.range.first, line.length)
        }
    }
    MARKER.findAll(line).forEach { m ->
        addStyle(SpanStyle(color = NuvioPrimitives.black, background = MARK_FILL), m.range.first, m.range.last + 1)
    }
}

/** B2's key: "Removed:" and each mark with what it stands for. */
@Composable
private fun RemovedKey() {
    val marquee = NuvioTheme.marquee
    val entries = listOf(
        listOf("url host=…") to R.string.report_removed_link,
        listOf("url addon=…") to R.string.report_removed_addon,
        listOf("secret") to R.string.report_removed_secret,
        listOf("token") to R.string.report_removed_token,
        listOf("ip") to R.string.report_removed_ip,
        listOf("email") to R.string.report_removed_email,
        listOf("hash", "magnet") to R.string.report_removed_torrent,
        listOf("id") to R.string.report_removed_id,
    )
    val label = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(R.string.report_removed), style = label, color = marquee.onImage2)
        entries.forEach { (marks, meaning) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                marks.forEach { mark ->
                    Text(
                        text = mark,
                        style = LOG_STYLE.copy(fontWeight = FontWeight.SemiBold),
                        color = NuvioPrimitives.black,
                        modifier = Modifier
                            .background(MARK_FILL, RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp)
                    )
                }
                Text(text = stringResource(meaning), style = label, color = marquee.onImage2)
            }
        }
    }
}
