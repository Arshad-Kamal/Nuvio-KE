package com.nuvio.tv.core.player

internal fun nextConsecutiveAutoPlayCount(
    currentCount: Int,
    isAutoPlay: Boolean,
): Int = if (isAutoPlay) currentCount + 1 else 0
