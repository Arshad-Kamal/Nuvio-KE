package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File

/** Quality judgement for a stats row, drawn as the value's colour. NONE = informational. */
enum class StatsDot { NONE, GOOD, WARN, BAD }

/** One label/value row; [group] overrides the label-keyed section map (a label shared by two sections). */
data class StatsRow(
    val label: String,
    val value: String,
    val dot: StatsDot = StatsDot.NONE,
    val marquee: Boolean = false,
    val group: StatsGroup? = null
)

/**
 * One sampled frame of the live playback stats panel (task 2.2 / nt26). Rows arrive
 * pre-formatted with their health judgement so this composable stays dumb; all
 * sampling, formatting and threshold judgement happens in
 * PlayerViewModel.samplePlaybackStats().
 */
data class PlaybackStatsSample(
    val isExoPlayer: Boolean,
    val sections: List<StatsSection>
)

/** Section a stats row belongs to; declaration order is display order within its [column] (0 = left). */
enum class StatsGroup(val header: String, val column: Int) {
    SOURCE("Source", 0),
    VIDEO("Video", 0),
    DISPLAY("Display", 0),
    AUDIO("Audio", 1),
    NETWORK("Network", 1),
    P2P("P2P", 1),
    SYSTEM("System", 1)
}

/** A titled group of stats rows, rendered under a small caps header. */
data class StatsSection(
    val group: StatsGroup,
    val rows: List<StatsRow>
)

private val STATS_GROUP_BY_LABEL: Map<String, StatsGroup> = buildMap {
    listOf("Add-on", "Provider", "Server", "File", "Size")
        .forEach { put(it, StatsGroup.SOURCE) }
    listOf("Engine", "Video", "Codec", "HDR", "Colour", "Mastering", "V bitrate", "DV", "DV HDR", "Decoder", "Dropped", "Frame lead")
        .forEach { put(it, StatsGroup.VIDEO) }
    listOf("Output", "Frame rate")
        .forEach { put(it, StatsGroup.DISPLAY) }
    listOf("Audio", "A bitrate", "Underruns", "Route", "A jitter")
        .forEach { put(it, StatsGroup.AUDIO) }
    listOf("Buffer", "Speed", "Ping", "Request", "Rate limit", "Hedge", "Load errors", "Loaded", "Rebuffers", "Pos. freeze")
        .forEach { put(it, StatsGroup.NETWORK) }
    listOf("App CPU", "Memory", "SoC temp", "CPU clock")
        .forEach { put(it, StatsGroup.SYSTEM) }
}

/**
 * Buckets a flat, emission-ordered row list into display sections. Order within a
 * section is preserved from emission; a row's own [StatsRow.group] wins, and an unmapped
 * label falls to SYSTEM so a newly added row can never silently vanish. Empty sections
 * are dropped (no bare header).
 */
internal fun buildStatsSections(rows: List<StatsRow>): List<StatsSection> {
    val byGroup = rows.groupBy { it.group ?: STATS_GROUP_BY_LABEL[it.label] ?: StatsGroup.SYSTEM }
    return StatsGroup.entries.mapNotNull { g -> byGroup[g]?.let { StatsSection(g, it) } }
}

/**
 * All quality thresholds behind the panel's health colours in one place so retuning
 * is a one-line patch. Recency-windowed judgements (dropped frames, underruns, stalls)
 * turn red only when the counter increased within RECENT_EVENT_WINDOW_MS.
 */
internal object PlaybackStatsThresholds {
    const val RECENT_EVENT_WINDOW_MS = 30_000L
    // Tuned for high-bitrate remuxes, where the loader cannot get 20 s ahead during
    // dense action even on an adequate link (the scene's instantaneous demand meets or
    // beats delivered rate), so the old 20/5 pair sat amber through most of such a film
    // and never discriminated a low-but-fine 6 s from a stall-imminent 0.1 s. 10/3
    // makes the dot track stall risk: green when comfortable (dialogue holds >=10 s),
    // amber getting-low, red genuine risk. Low-bitrate streams still hold 45 s and stay
    // green, so nothing regresses there.
    const val BUFFER_GOOD_SECONDS = 10.0
    const val BUFFER_WARN_SECONDS = 3.0
    const val BUFFER_STARTUP_GRACE_MS = 10_000L
    const val BUFFER_EOF_GUARD_MS = 5_000L
    const val SPEED_RATIO_GOOD = 2.0
    const val SPEED_RATIO_WARN = 1.0
    const val PING_GOOD_MS = 50L
    const val PING_WARN_MS = 150L
    const val CPU_GOOD_PERCENT = 30
    const val CPU_BAD_PERCENT = 60
    const val HEAP_GOOD_FRACTION = 0.60
    const val HEAP_BAD_FRACTION = 0.85

    // Measured mux (V bitrate). Bytes and media time accumulate per HUD tick; a jump
    // in buffered position larger than MUX_MAX_MEDIA_DELTA_MS in one ~1 s tick cannot
    // be a legitimate buffer fill (it would need tens of gigabits per second) and is
    // therefore a seek, which starts a fresh measurement epoch.
    const val MUX_MIN_MEDIA_MS = 5_000L
    const val MUX_MAX_MEDIA_DELTA_MS = 60_000L

    // Windowed ("now") mux rate. The HUD keeps the last MUX_WINDOW_TICKS running-total
    // snapshots (one per ~1 s tick) and divides the byte span by the media span across
    // them, provided that media span is at least MUX_WINDOW_MIN_MEDIA_MS. A ~10 s window
    // is long enough to smooth per-GOP swing but short enough to expose a scene change.
    const val MUX_WINDOW_TICKS = 10
    const val MUX_WINDOW_MIN_MEDIA_MS = 3_000L

    // No 720p-or-better stream muxes below a megabit, and no SD stream below a fifth
    // of one. A figure under the floor for its resolution means the byte counter is not
    // seeing the source's traffic, and the row says so instead of printing the number.
    // The floor is resolution-aware so a genuinely poor SD source is not misreported as
    // a broken instrument.
    const val MUX_IMPLAUSIBLE_FLOOR_BPS = 1_000_000L
    const val MUX_IMPLAUSIBLE_FLOOR_SD_BPS = 200_000L
    const val MUX_FLOOR_HD_MIN_HEIGHT = 720

    // Measured link throughput, sampled only across ticks where the loader was pulling.
    const val THROUGHPUT_MIN_WINDOW_MS = 500L
    const val THROUGHPUT_MAX_WINDOW_MS = 5_000L
    const val THROUGHPUT_SMOOTHING = 0.3

    // The bandwidth meter is trusted only while it has seen at least this fraction
    // (1/N) of the bytes the HUD counted crossing the network. On a plain progressive
    // stream it sees almost none of them.
    const val METER_COVERAGE_DIVISOR = 4L

    // SoC thermal row. Degree thresholds pin to the vendor HAL throttle ladder read
    // from this box (dumpsys thermalservice, 2026-07-13: light 65 / moderate 75 /
    // severe 95 / emergency 105 / shutdown 120 °C — the box idles ~66 °C, so "light"
    // is normal operation): amber at moderate, red at severe. Headroom is normalised
    // by the framework so 1.0 = severe on whatever device this runs on; amber when
    // within 10% of it. Re-pin the degree pair per device (task 4.0 for the Xiaomi).
    // Audio-clock jitter: deviation of reported audio position from wall clock. A healthy
    // passthrough clock tracks within a few ms. The Yellowstone TrueHD failure showed
    // ~50-57 ms jumps at ~30/sec while every other counter read clean. Provisional — re-pin
    // once the row has seen the reproducer and a known-good remux side by side.
    // nt35: A jitter dot is driven by 1 s-window drift (see PlaybackSpeedAwareAudioSink).
    // Clean-capture baseline: |drift| p90 ~23 ms/s. Red end provisional - needs a session
    // with a genuinely failing HAL clock to validate.
    const val AUDIO_DRIFT_GOOD_MS = 40L
    const val AUDIO_DRIFT_BAD_MS = 100L

    const val SOC_TEMP_WARN_C = 75f
    const val SOC_TEMP_BAD_C = 95f
    const val THERMAL_HEADROOM_WARN = 0.90f
    const val THERMAL_HEADROOM_BAD = 1.0f
}

/**
 * The stats panel (data-additions st10): a compact-panel shell top start, the eyebrow + hint over
 * a rule, then two columns of sections (StatsGroup.column). Values carry their row's health colour.
 * The caller bounds its height (insets) and clips the tail when the rows outrun it.
 */
@Composable
fun PlaybackStatsOverlay(
    visible: Boolean,
    sample: PlaybackStatsSample?,
    modifier: Modifier = Modifier
) {
    val osd = NuvioTheme.marquee.osd
    AnimatedVisibility(
        visible = visible && sample != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        CompactPanel(
            modifier = Modifier.width(osd.statsWidth),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelEyebrowText(text = stringResource(R.string.player_tray_stats))
                Text(
                    text = stringResource(R.string.player_stats_hint),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 0.4.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
            PanelRule()
            val sections = sample?.sections.orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(osd.statsColumnGap)) {
                for (column in 0..1) {
                    Column(modifier = Modifier.weight(1f)) {
                        sections.filter { it.group.column == column }.forEach { section ->
                            StatsSectionHeader(section.group.header)
                            section.rows.forEach { StatsRowLine(it) }
                        }
                        if (column == 0 && sample?.isExoPlayer == false) {
                            Text(
                                text = stringResource(R.string.player_stats_unavailable_mpv),
                                style = statsTextStyle(),
                                color = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Row text: Inter medium 12 sp on an 18 sp line, no tracking. */
@Composable
private fun statsTextStyle(): TextStyle =
    MaterialTheme.typography.labelMedium.copy(lineHeight = 18.sp, letterSpacing = 0.sp)

@Composable
private fun StatsSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 8.dp, bottom = 3.dp)
    )
}

@Composable
private fun StatsRowLine(row: StatsRow) {
    val osd = NuvioTheme.marquee.osd
    val valueColor = when (row.dot) {
        StatsDot.GOOD -> osd.statsGood
        StatsDot.WARN -> osd.statsWarn
        StatsDot.BAD -> osd.statsBad
        StatsDot.NONE -> Color.White
    }
    val textStyle = statsTextStyle()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = row.label,
            style = textStyle,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            softWrap = false
        )
        Spacer(modifier = Modifier.width(10.dp))
        val valueModifier = Modifier.weight(1f)
        Text(
            text = row.value,
            // Tabular figures, so digits hold still from one 1 s sample to the next.
            style = textStyle.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = if (row.marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (row.marquee) {
                valueModifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 20.dp, initialDelayMillis = 2000)
            } else {
                valueModifier
            }
        )
    }
}

/**
 * Samples this process's own CPU usage from /proc/self/stat deltas. Global
 * /proc/stat is not readable on modern Android, so this is app CPU only
 * (normalised to total capacity across all cores); the hardware video
 * decoder's load does not appear here.
 */
internal class ProcessCpuSampler {
    private var lastCpuTicks: Long = -1L
    private var lastElapsedMs: Long = -1L
    private val clockTicksPerSecond: Long =
        try {
            android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
        } catch (_: Throwable) {
            100L
        }
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** App CPU as a whole percentage of total capacity, or null until the second sample. */
    fun samplePercent(): Int? {
        val ticks = readOwnCpuTicks() ?: return null
        val nowMs = SystemClock.elapsedRealtime()
        val result = if (lastCpuTicks >= 0L && nowMs > lastElapsedMs) {
            val cpuMs = (ticks - lastCpuTicks) * 1000.0 / clockTicksPerSecond
            val wallMs = (nowMs - lastElapsedMs).toDouble()
            ((cpuMs / (wallMs * cores)) * 100.0).toInt().coerceIn(0, 100)
        } else {
            null
        }
        lastCpuTicks = ticks
        lastElapsedMs = nowMs
        return result
    }

    private fun readOwnCpuTicks(): Long? = try {
        val stat = File("/proc/self/stat").readText()
        // utime and stime are stat fields 14 and 15 (1-indexed). The comm field
        // is parenthesised and may contain spaces, so index from after the ')':
        // the first post-comm field is field 3, putting utime/stime at split
        // indices 11 and 12.
        val fields = stat.substringAfterLast(')').trim().split(' ')
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        utime + stime
    } catch (_: Throwable) {
        null
    }
}
