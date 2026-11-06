package com.nuvio.tv.data.repository

import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.core.util.canonicalizeAddonUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Process-lifetime scope for passive health writes, shared by the addon repositories
// (each previously held its own detached scope for this alone).
private val addonHealthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Fire-and-forget one passive health sample for the addon at [baseUrl], keyed by its
 * canonical base URL like the store. Never adds latency to the fetch being recorded.
 */
internal fun AddonHealthStore.recordAddonOutcome(baseUrl: String, outcome: HealthOutcome, latencyMs: Long) {
    addonHealthScope.launch {
        record(AddonHealthStore.addonKey(canonicalizeAddonUrl(baseUrl)), outcome, latencyMs)
    }
}
