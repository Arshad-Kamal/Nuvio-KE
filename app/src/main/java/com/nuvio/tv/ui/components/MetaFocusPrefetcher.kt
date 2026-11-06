package com.nuvio.tv.ui.components

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.repository.MetaRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Warms the MetaRepository cache when a poster card gains focus, so the detail screen opens
 * instantly. Shared by the poster-grid ViewModels (Search/Discover, Library).
 *
 * Inject into a ViewModel via Hilt and call [bind] from the VM's init with `viewModelScope`
 * (same shape as PosterOptionsController); each injection is its own instance.
 */
class MetaFocusPrefetcher @Inject constructor(
    private val metaRepository: MetaRepository
) {
    private val prefetchedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var scope: CoroutineScope? = null
    private var prefetchJob: Job? = null
    private var inFlightId: String? = null

    fun bind(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
    }

    /** Debounced so rapid D-pad scrolling doesn't fetch every card it passes. */
    fun prefetchOnFocus(id: String, type: String) {
        val launchScope = scope ?: return
        if (id.isBlank() || id in prefetchedIds) return
        // Refocusing the card whose fetch is already pending or running keeps that fetch.
        if (id == inFlightId && prefetchJob?.isActive == true) return
        prefetchJob?.cancel()
        inFlightId = id
        prefetchJob = launchScope.launch {
            delay(FOCUS_DEBOUNCE_MS)
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
            // Marked only once a fetch succeeds: a fetch cancelled by the next focus (or a
            // failed one) is retried the next time this card gains focus.
            if (result is NetworkResult.Success) prefetchedIds.add(id)
        }
    }

    /** Backdrop URL of a previously fetched meta, or null. */
    fun cachedBackdrop(id: String, type: String): String? =
        metaRepository.getCachedMeta(type, id)?.backdropUrl

    private companion object {
        const val FOCUS_DEBOUNCE_MS = 150L
    }
}
