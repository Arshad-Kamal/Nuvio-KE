package com.nuvio.tv.data.remote.supabase

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.MemberCatalogStorage
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MemberAvatarBucket = "membership-profile-avatars"
private const val MemberAvatarTag = "MemberAvatars"
private const val AvatarCatalogRefreshIntervalMs = 15 * 60_000L

internal fun isAvatarCatalogRefreshDue(lastRefreshAtMs: Long, nowMs: Long): Boolean {
    return lastRefreshAtMs <= 0L ||
        nowMs < lastRefreshAtMs ||
        nowMs - lastRefreshAtMs >= AvatarCatalogRefreshIntervalMs
}

@Serializable
private data class StoredAvatarCatalogPayload(
    val standardItems: List<SupabaseAvatarCatalogItem> = emptyList(),
    val memberItems: List<SupabaseMemberAvatarCatalogItem> = emptyList(),
    val standardLoaded: Boolean = false,
    val memberLoaded: Boolean = false
)

data class AvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String,
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null,
    val memberOnly: Boolean = false
)

@Singleton
class AvatarRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val storage: Storage,
    private val serverConfiguration: ServerConfiguration,
    private val memberCatalogStorage: MemberCatalogStorage,
    private val authManager: AuthManager,
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val assetCache by lazy { MemberAssetCache(context.cacheDir.resolve("member_profile_avatars")) }
    private var standardMetadata = emptyList<SupabaseAvatarCatalogItem>()
    private var memberMetadata = emptyList<SupabaseMemberAvatarCatalogItem>()
    private var standardCatalogLoaded = false
    private var memberCatalogLoaded = false
    private var cachedStandardCatalog: List<AvatarCatalogItem>? = null
    private var cachedMemberCatalog: List<AvatarCatalogItem>? = null
    private val storedCatalogMutex = Mutex()
    @Volatile
    private var storedCatalogRestored = false
    private var standardRefreshJob: Job? = null
    private var memberRefreshJob: Job? = null
    private var lastStandardRefreshAtMs = 0L
    private var lastMemberRefreshAtMs = 0L
    private val catalogLoadMutex = Mutex()
    private val _catalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())

    /**
     * The catalog last published by [loadCatalog]: the one home the top-bar avatar
     * and the profile picker both read (L5-36).
     */
    val catalog: StateFlow<List<AvatarCatalogItem>> = _catalog.asStateFlow()

    /**
     * Loads the standard catalog, plus the supporter catalog when signed in, into [catalog]
     * (supporter avatars unlock locally, Spec 31 S6; a failed supporter fetch adds nothing). Signed
     * out it never asks for supporter avatars, so no anonymous answer is cached over the account's.
     * A failed load keeps the last published catalog. Loads are serialised, so concurrent callers
     * at cold start share one fetch (the second hits the in-memory cache).
     */
    suspend fun loadCatalog() {
        catalogLoadMutex.withLock {
            try {
                _catalog.value = getAvatarCatalog()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(MemberAvatarTag, "Unable to load avatar catalog", error)
            }
        }
    }

    private suspend fun getAvatarCatalog(): List<AvatarCatalogItem> {
        restoreStoredCatalogOnce()
        val hadStandardCache = cachedStandardCatalog != null
        val standardCatalog = getStandardAvatarCatalog()
        if (hadStandardCache) refreshStandardCatalogInBackground()
        if (authManager.authState.value !is AuthState.FullAccount) return standardCatalog
        val hadMemberCache = cachedMemberCatalog != null
        val memberCatalog = getMemberAvatarCatalog()
        if (hadMemberCache) refreshMemberCatalogInBackground()
        return standardCatalog + memberCatalog
    }

    private suspend fun getStandardAvatarCatalog(): List<AvatarCatalogItem> {
        cachedStandardCatalog?.let { return it }
        return fetchStandardAvatarCatalog()
    }

    private suspend fun fetchStandardAvatarCatalog(): List<AvatarCatalogItem> {
        val response = postgrest.rpc("get_avatar_catalog")
        val remote = response.decodeList<SupabaseAvatarCatalogItem>()
        standardMetadata = remote
        standardCatalogLoaded = true
        val catalog = remote.map(::toStandardCatalogItem)
        cachedStandardCatalog = catalog
        lastStandardRefreshAtMs = SystemClock.elapsedRealtime()
        saveStoredCatalog()
        return catalog
    }

    private suspend fun getMemberAvatarCatalog(): List<AvatarCatalogItem> {
        cachedMemberCatalog?.let { return it }
        return fetchMemberAvatarCatalog()
    }

    private suspend fun fetchMemberAvatarCatalog(): List<AvatarCatalogItem> {
        val remote = try {
            postgrest.rpc("get_member_profile_avatar_catalog")
                .decodeList<SupabaseMemberAvatarCatalogItem>()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(MemberAvatarTag, "Unable to load supporter avatar catalog", error)
            return emptyList()
        }
        memberMetadata = remote
        memberCatalogLoaded = true
        lastMemberRefreshAtMs = SystemClock.elapsedRealtime()
        saveStoredCatalog()
        val catalog = coroutineScope {
            remote.map { item ->
                async {
                    try {
                        toMemberCatalogItem(item, cacheMemberAvatar(item))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(MemberAvatarTag, "Unable to load supporter avatar ${item.id}", error)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        cachedMemberCatalog = catalog
        return catalog
    }

    fun getAvatarImageUrl(avatarId: String): String? {
        return _catalog.value.find { it.id == avatarId }?.imageUrl
            ?: latestCachedMemberAvatar(avatarId)?.toURI()?.toString()
    }

    private fun refreshStandardCatalogInBackground() {
        if (standardRefreshJob?.isActive == true) return
        if (!isAvatarCatalogRefreshDue(lastStandardRefreshAtMs, SystemClock.elapsedRealtime())) return
        standardRefreshJob = scope.launch {
            try {
                fetchStandardAvatarCatalog()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(MemberAvatarTag, "Unable to refresh avatar catalog", error)
            }
        }
    }

    private fun refreshMemberCatalogInBackground() {
        if (memberRefreshJob?.isActive == true) return
        if (!isAvatarCatalogRefreshDue(lastMemberRefreshAtMs, SystemClock.elapsedRealtime())) return
        memberRefreshJob = scope.launch {
            try {
                fetchMemberAvatarCatalog()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(MemberAvatarTag, "Unable to refresh supporter avatars", error)
            }
        }
    }

    /**
     * Restores the persisted catalog on first use, off the caller's thread: it reads prefs and
     * stats cached files, and this repository is injected into MainActivity (main thread).
     */
    private suspend fun restoreStoredCatalogOnce() {
        if (storedCatalogRestored) return
        storedCatalogMutex.withLock {
            if (storedCatalogRestored) return
            withContext(Dispatchers.IO) {
                val stored = loadStoredCatalog() ?: return@withContext
                standardMetadata = stored.standardItems
                memberMetadata = stored.memberItems
                standardCatalogLoaded = stored.standardLoaded
                memberCatalogLoaded = stored.memberLoaded
                if (standardCatalogLoaded) cachedStandardCatalog = standardMetadata.map(::toStandardCatalogItem)
                if (memberCatalogLoaded) cachedMemberCatalog = memberMetadata.mapNotNull(::loadCachedMemberAvatar)
            }
            storedCatalogRestored = true
        }
    }

    private fun loadStoredCatalog(): StoredAvatarCatalogPayload? {
        val payload = memberCatalogStorage.loadAvatarCatalogPayload().orEmpty().trim()
        if (payload.isEmpty()) return null
        return runCatching { json.decodeFromString<StoredAvatarCatalogPayload>(payload) }.getOrNull()
    }

    private fun saveStoredCatalog() {
        memberCatalogStorage.saveAvatarCatalogPayload(
            json.encodeToString(
                StoredAvatarCatalogPayload(
                    standardItems = standardMetadata,
                    memberItems = memberMetadata,
                    standardLoaded = standardCatalogLoaded,
                    memberLoaded = memberCatalogLoaded
                )
            )
        )
    }

    private fun toStandardCatalogItem(item: SupabaseAvatarCatalogItem): AvatarCatalogItem =
        AvatarCatalogItem(
            id = item.id,
            displayName = item.displayName,
            imageUrl = avatarImageUrl(item.storagePath),
            category = item.category,
            sortOrder = item.sortOrder,
            bgColor = item.bgColor
        )

    private fun loadCachedMemberAvatar(item: SupabaseMemberAvatarCatalogItem): AvatarCatalogItem? {
        val imageFile = assetCache.cachedFile(item.id, item.assetVersion, item.imageExtension()) ?: return null
        return toMemberCatalogItem(item, imageFile)
    }

    private fun toMemberCatalogItem(item: SupabaseMemberAvatarCatalogItem, imageFile: File) =
        AvatarCatalogItem(
            id = item.id,
            displayName = item.displayName,
            imageUrl = imageFile.toURI().toString(),
            category = item.category,
            sortOrder = item.sortOrder,
            bgColor = item.bgColor,
            memberOnly = true
        )

    private fun avatarImageUrl(storagePath: String): String {
        if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) return storagePath
        val baseUrl = serverConfiguration.avatarPublicBaseUrl.orEmpty().trimEnd('/')
        return if (baseUrl.isNotEmpty()) "$baseUrl/$storagePath" else storagePath
    }

    private suspend fun cacheMemberAvatar(item: SupabaseMemberAvatarCatalogItem): File =
        assetCache.getOrDownload(item.id, item.assetVersion, item.imageExtension()) {
            storage[MemberAvatarBucket].downloadAuthenticated(item.storagePath)
        }

    private fun SupabaseMemberAvatarCatalogItem.imageExtension(): String =
        storagePath.substringAfterLast('.', "img")
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "img"

    private fun latestCachedMemberAvatar(avatarId: String): File? = assetCache.latest(avatarId)?.first
}
