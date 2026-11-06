package com.nuvio.tv.data.remote.supabase

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.MemberCatalogStorage
import com.nuvio.tv.domain.model.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val ProfileBackgroundBucket = "membership-profile-backgrounds"
private const val ProfileBackgroundTag = "ProfileBackgrounds"
private const val BackgroundExtension = "png"

@Serializable
private data class StoredProfileBackgroundCatalogPayload(
    val items: List<SupabaseProfileBackgroundCatalogItem> = emptyList()
)

data class ProfileBackgroundCatalogItem(
    val id: String,
    val displayName: String,
    val imageFile: File? = null,
    val assetVersion: Int
)

@Singleton
class ProfileBackgroundRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val storage: Storage,
    private val memberCatalogStorage: MemberCatalogStorage,
    private val authManager: AuthManager,
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _catalog = MutableStateFlow<List<ProfileBackgroundCatalogItem>>(emptyList())
    val catalog: StateFlow<List<ProfileBackgroundCatalogItem>> = _catalog.asStateFlow()
    private val assetCache by lazy { MemberAssetCache(context.cacheDir.resolve("member_profile_backgrounds")) }
    // Catalog state is written on Main only (callers + the load job's publish step).
    // Volatile because the IO asset-load jobs read it.
    @Volatile
    private var remoteCatalog = emptyList<SupabaseProfileBackgroundCatalogItem>()
    private var catalogLoadJob: Job? = null
    private var assetLoadJob: Job? = null
    private var cacheHydrated = false
    private var remoteLoaded = false

    fun ensureLoaded() {
        hydrateFromCacheIfNeeded()
        // Signed out, only the cached catalog shows: an anonymous answer would mark the catalog
        // loaded and hide the account's backgrounds after a mid-session sign-in (Spec 31 S6).
        if (authManager.authState.value !is AuthState.FullAccount) return
        if (remoteLoaded || catalogLoadJob?.isActive == true) return
        catalogLoadJob = scope.launch {
            try {
                val items = postgrest.rpc("get_member_profile_background_catalog")
                    .decodeList<SupabaseProfileBackgroundCatalogItem>()
                withContext(Dispatchers.Main.immediate) {
                    remoteCatalog = items
                    publishMetadata(items)
                    remoteLoaded = true
                }
                saveStoredCatalog(items)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(ProfileBackgroundTag, "Unable to load supporter profile backgrounds", error)
            }
        }
    }

    fun loadSelectedAndPreload(id: String) {
        ensureLoaded()
        remoteCatalog.firstOrNull { it.id == id }?.let(::loadCachedAndPublish)
            ?: loadUnindexedCachedSelection(id)
        assetLoadJob?.cancel()
        assetLoadJob = scope.launch {
            val initialSelected = remoteCatalog.firstOrNull { it.id == id }
            initialSelected?.let { loadAndPublish(it) }
            catalogLoadJob?.join()
            val selected = remoteCatalog.firstOrNull { it.id == id } ?: return@launch
            if (selected.assetVersion != initialSelected?.assetVersion) loadAndPublish(selected)
            coroutineScope {
                remoteCatalog
                    .filterNot { it.id == id }
                    .map { item -> launch { loadAndPublish(item) } }
                    .joinAll()
            }
        }
    }

    fun preloadImages() {
        ensureLoaded()
        assetLoadJob?.cancel()
        assetLoadJob = scope.launch {
            val initialVersions = remoteCatalog.associate { it.id to it.assetVersion }
            preload(remoteCatalog)
            catalogLoadJob?.join()
            preload(remoteCatalog.filter { initialVersions[it.id] != it.assetVersion })
        }
    }

    private fun hydrateFromCacheIfNeeded() {
        if (cacheHydrated) return
        cacheHydrated = true
        val payload = memberCatalogStorage.loadProfileBackgroundCatalogPayload().orEmpty().trim()
        if (payload.isEmpty()) return
        val stored = runCatching {
            json.decodeFromString<StoredProfileBackgroundCatalogPayload>(payload)
        }.getOrNull() ?: return
        remoteCatalog = stored.items
        publishMetadata(stored.items)
    }

    private fun publishMetadata(items: List<SupabaseProfileBackgroundCatalogItem>) {
        val current = _catalog.value.associateBy(ProfileBackgroundCatalogItem::id)
        _catalog.value = items.map { item ->
            val cached = current[item.id]?.takeIf { it.assetVersion == item.assetVersion }
            ProfileBackgroundCatalogItem(
                id = item.id,
                displayName = item.displayName,
                imageFile = cached?.imageFile ?: cachedImageFile(item),
                assetVersion = item.assetVersion
            )
        }
    }

    private fun saveStoredCatalog(items: List<SupabaseProfileBackgroundCatalogItem>) {
        memberCatalogStorage.saveProfileBackgroundCatalogPayload(
            json.encodeToString(StoredProfileBackgroundCatalogPayload(items))
        )
    }

    private fun loadCachedAndPublish(item: SupabaseProfileBackgroundCatalogItem) {
        val imageFile = cachedImageFile(item) ?: return
        publishImage(item, imageFile)
    }

    private fun loadUnindexedCachedSelection(id: String) {
        val (imageFile, version) = assetCache.latest(id, BackgroundExtension) ?: return
        _catalog.update { catalog ->
            if (catalog.any { it.id == id }) {
                catalog
            } else {
                catalog + ProfileBackgroundCatalogItem(
                    id = id,
                    displayName = id,
                    imageFile = imageFile,
                    assetVersion = version
                )
            }
        }
    }

    private suspend fun preload(items: List<SupabaseProfileBackgroundCatalogItem>) {
        coroutineScope {
            items.map { item -> launch { loadAndPublish(item) } }.joinAll()
        }
    }

    private suspend fun loadAndPublish(item: SupabaseProfileBackgroundCatalogItem) {
        try {
            val imageFile = cacheImage(item)
            publishImage(item, imageFile)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(ProfileBackgroundTag, "Unable to load supporter profile background ${item.id}", error)
        }
    }

    private fun publishImage(item: SupabaseProfileBackgroundCatalogItem, imageFile: File) {
        _catalog.update { catalog ->
            catalog.map { background ->
                if (background.id == item.id && background.assetVersion == item.assetVersion) {
                    background.copy(imageFile = imageFile)
                } else {
                    background
                }
            }
        }
    }

    private suspend fun cacheImage(item: SupabaseProfileBackgroundCatalogItem): File =
        assetCache.getOrDownload(item.id, item.assetVersion, BackgroundExtension) {
            storage[ProfileBackgroundBucket].downloadAuthenticated(item.storagePath)
        }

    private fun cachedImageFile(item: SupabaseProfileBackgroundCatalogItem): File? =
        assetCache.cachedFile(item.id, item.assetVersion, BackgroundExtension)
}
