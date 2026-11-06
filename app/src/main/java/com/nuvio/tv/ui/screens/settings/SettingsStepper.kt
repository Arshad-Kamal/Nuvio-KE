package com.nuvio.tv.ui.screens.settings

// Pure stepper rules for the Settings kit's stepper and segment rows (Spec 31 S8). Left / Right
// move one step; at either end the value stays put (the row still consumes the key).

/**
 * One stepper move of [delta] steps over [values] (ascending). A [current] not in the list (a
 * value stored before the list changed) moves to the nearest entry in that direction first; with
 * no entry that way it stays put.
 */
internal fun stepValue(values: List<Int>, current: Int, delta: Int): Int {
    require(values.isNotEmpty()) { "stepValue needs at least one value" }
    val index = values.indexOf(current)
    if (index >= 0) return values[(index + delta).coerceIn(0, values.lastIndex)]
    if (delta == 0) return current
    val firstAbove = firstIndexAbove(values, current)
    if (delta > 0 && firstAbove == values.size) return current
    if (delta < 0 && firstAbove == 0) return current
    val target = if (delta > 0) firstAbove + delta - 1 else firstAbove + delta
    return values[target.coerceIn(0, values.lastIndex)]
}

/** One stepper move of [delta] steps of [step] over [min]..[max]. */
internal fun stepValue(min: Int, max: Int, step: Int, current: Int, delta: Int): Int =
    (current + delta * step).coerceIn(min, max)

/** One segment move of [delta] over [size] options, clamped at the ends. */
internal fun stepIndex(size: Int, index: Int, delta: Int): Int =
    if (size <= 0) 0 else (index + delta).coerceIn(0, size - 1)

/** How full the stepper track is: [current]'s place in [values] (an off-list value by its nearest entry below). */
internal fun stepFraction(values: List<Int>, current: Int): Float {
    if (values.size < 2) return 0f
    val index = values.indexOf(current).takeIf { it >= 0 }
        ?: (firstIndexAbove(values, current) - 1).coerceAtLeast(0)
    return index.toFloat() / values.lastIndex
}

/** How full the stepper track is over [min]..[max]. */
internal fun stepFraction(min: Int, max: Int, current: Int): Float =
    if (max <= min) 0f else ((current - min).toFloat() / (max - min)).coerceIn(0f, 1f)

/** Index of the first entry above [current], or `values.size` when none is. */
private fun firstIndexAbove(values: List<Int>, current: Int): Int =
    values.indexOfFirst { it > current }.let { if (it < 0) values.size else it }
