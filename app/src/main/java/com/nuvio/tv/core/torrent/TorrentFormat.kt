package com.nuvio.tv.core.torrent

import android.content.Context
import com.nuvio.tv.R

/** One home for torrent stat formatting (player status lines, rebuffer line, stats overlay). */
internal fun formatTorrentSpeed(context: Context, bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> context.getString(R.string.unit_speed_mb_s, String.format("%.1f", bytesPerSec / 1_048_576.0))
        bytesPerSec >= 1_024 -> context.getString(R.string.unit_speed_kb_s, String.format("%.0f", bytesPerSec / 1_024.0))
        else -> context.getString(R.string.unit_speed_b_s, bytesPerSec)
    }
}

internal fun formatTorrentMB(context: Context, bytes: Long): String =
    context.getString(R.string.unit_size_mb, String.format("%.1f", bytes / 1_048_576.0))
