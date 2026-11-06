package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.enabledAddons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

// Cold start: the addon manifest loads from disk asynchronously, so the
// first addons emission can be the seeded empty list. Wait briefly for
// real data before concluding there are no addons.
private const val ADDON_LIST_WAIT_MS = 5_000L

/**
 * Waits out the cold-start manifest load: returns the first non-empty addon
 * list (enabled addons only), or an empty list after [timeoutMs] or when [addons]
 * completes without one. [AddonRepository.getInstalledAddons] is a StateFlow
 * seeded with an empty list, so a plain first() reads the seed, not the addons.
 * A profile with no addons pays the full wait; cancellation propagates, so a
 * cancelled fetch cannot be misread as "no addons". One home for the meta,
 * stream and subtitle repositories; top-level so the wait is testable on a
 * virtual-time dispatcher (the repository bodies run on Dispatchers.IO).
 */
internal suspend fun awaitInstalledAddons(
    addons: Flow<List<Addon>>,
    timeoutMs: Long = ADDON_LIST_WAIT_MS
): List<Addon> =
    withTimeoutOrNull(timeoutMs) { addons.firstOrNull { it.isNotEmpty() } }.orEmpty().enabledAddons()
