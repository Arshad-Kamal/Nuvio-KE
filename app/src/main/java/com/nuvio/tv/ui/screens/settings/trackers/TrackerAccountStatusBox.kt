package com.nuvio.tv.ui.screens.settings.trackers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingAccountStatus
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.ceil

/**
 * E2/E5 `.s106q`: "<tracker> requests left today" with "368 of 500" (amber while paused), a bar of what is left, then
 * when it resets and who shares it (or, paused, that plays reach the tracker after the reset), and the service's usage
 * page. With a [TrackingAccountStatus.plan] a Plan line comes first (Spec 105 A2: "Plan  Free").
 */
@Composable
internal fun TrackerAccountStatusBox(name: String, status: TrackingAccountStatus, nowEpochMs: Long) {
    val marquee = NuvioTheme.marquee
    val paused = status.isPaused(nowEpochMs)
    val remaining = if (paused) 0 else status.remaining
    val count = when {
        remaining != null && status.limit != null -> stringResource(R.string.trackers_quota_of, "%,d".format(remaining), "%,d".format(status.limit))
        remaining != null -> "%,d".format(remaining)
        else -> null
    }
    val lines = buildList {
        status.resetsAtEpochMs?.let { it - nowEpochMs }?.takeIf { it > 0 }?.let { left ->
            add(stringResource(R.string.trackers_quota_resets, timeLeftLabel(left)))
        }
        add(stringResource(if (paused) R.string.trackers_quota_paused else R.string.trackers_quota_shared, name))
        status.usagePage?.let { add(stringResource(R.string.trackers_quota_usage, it)) }
    }
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 6.dp)
            .fillMaxWidth()
            .background(NuvioPrimitives.white.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        status.plan?.let { plan ->
            Row(modifier = Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    text = stringResource(R.string.trackers_quota_plan),
                    style = NuvioTheme.textStyles.caption.copy(fontWeight = FontWeight.Medium),
                    color = marquee.onImage2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = planLabel(plan),
                    style = NuvioTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = NuvioPrimitives.white,
                    maxLines = 1
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stringResource(R.string.trackers_quota_title, name),
                style = NuvioTheme.textStyles.caption.copy(fontWeight = FontWeight.Medium),
                color = marquee.onImage2,
                modifier = Modifier.weight(1f)
            )
            count?.let {
                Text(
                    text = it,
                    style = NuvioTheme.textStyles.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (paused || remaining == 0) NuvioPrimitives.warning else NuvioPrimitives.white
                )
            }
        }
        if (remaining != null && status.limit != null && status.limit > 0) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 6.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(NuvioPrimitives.white.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((remaining.toFloat() / status.limit).coerceIn(0f, 1f))
                        .background(NuvioPrimitives.successOnImage, RoundedCornerShape(2.dp))
                )
            }
        }
        Text(
            text = lines.joinToString(" "),
            style = NuvioTheme.textStyles.caption,
            color = marquee.onImage3
        )
    }
}

/** The service's plan name as shown: its own word, first letter up ("free" -> "Free"). */
internal fun planLabel(plan: String): String = plan.trim().replaceFirstChar { it.uppercaseChar() }

/** A time left as the panel says it: whole hours, rounded up, from one hour; minutes below that (at least 1). */
internal enum class TimeUnitShown { HOURS, MINUTES }

internal data class TrackerTimeLeft(val unit: TimeUnitShown, val count: Int)

internal fun trackerTimeLeft(ms: Long): TrackerTimeLeft {
    val minutes = ceil(ms.coerceAtLeast(0) / 60_000.0).toInt().coerceAtLeast(1)
    return if (minutes >= 60) {
        TrackerTimeLeft(TimeUnitShown.HOURS, ceil(minutes / 60.0).toInt())
    } else {
        TrackerTimeLeft(TimeUnitShown.MINUTES, minutes)
    }
}

@Composable
internal fun timeLeftLabel(ms: Long): String {
    val left = trackerTimeLeft(ms)
    val res = when (left.unit) {
        TimeUnitShown.HOURS -> R.string.trackers_time_hours
        TimeUnitShown.MINUTES -> R.string.trackers_time_minutes
    }
    return stringResource(res, left.count)
}
