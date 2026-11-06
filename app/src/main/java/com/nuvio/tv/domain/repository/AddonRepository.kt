package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>
    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)

    /**
     * Applies the phone config page's addon list (L5-b): [urls] in order, [removedUrls]
     * removed, every stored addon the page did not list keeps its slot.
     */
    suspend fun spliceAddonOrder(urls: List<String>, removedUrls: Collection<String>)

    /**
     * Moves one installed addon a slot up (-1) or down (+1) against the stored order
     * (L5-68), never a UI snapshot.
     */
    suspend fun moveAddon(url: String, direction: Int)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)

    /**
     * Spec 31 rename: the addon at [url] shows [name] on this profile and the change is pushed;
     * blank or equal to [manifestName] restores the addon's own name. Default no-op so test
     * doubles are unaffected.
     */
    suspend fun renameAddon(url: String, name: String, manifestName: String) {}

    /**
     * Spec 40: canonical base urls of the addons whose search catalogs run only when a search is
     * submitted, never while typing. Local to this TV. Defaults keep test doubles unaffected.
     */
    fun getSearchOnSubmitUrls(): Flow<Set<String>> = flowOf(emptySet())

    /** Spec 40: marks the addon at [url] searched only on submit ([onSubmit]) or as you type. */
    suspend fun setSearchOnSubmit(url: String, onSubmit: Boolean) {}

    /**
     * T5 (ysosrs 7bb4e446f): force a manifest re-fetch of every installed, enabled
     * addon, bypassing the in-memory cache. Heals addons that dropped out of the
     * installed list because their manifest fetch failed. Default no-op so test
     * doubles and any other implementors are unaffected.
     */
    suspend fun refreshAllManifests() {}
}
