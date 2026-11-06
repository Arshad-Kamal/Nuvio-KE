package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.TraktListSort
import com.nuvio.tv.domain.model.TraktSortHow
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.core.util.numberField
import com.nuvio.tv.core.util.stringField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ValidationResult(
    val valid: Boolean,
    val error: String? = null,
    val collectionCount: Int = 0,
    val folderCount: Int = 0
)

@Singleton
class CollectionsDataStore @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "collections"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val collectionsKey = stringPreferencesKey("collections_json")
    private fun string(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    val collections: Flow<List<Collection>> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                parseCollections(prefs[collectionsKey])
            }
        }

    // Explicit profileId: a sync apply writes the profile it fetched for (L9-04).
    suspend fun setCollections(
        collections: List<Collection>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { prefs ->
            if (collections.isEmpty()) {
                prefs.remove(collectionsKey)
            } else {
                prefs[collectionsKey] = encodeCollections(collections)
            }
        }
    }

    /** Upsert: a repeated add of the same id replaces it in place (L4-24/86). */
    suspend fun addCollection(collection: Collection) {
        upsertCollections(listOf(collection))
    }

    /**
     * Read-modify-write merge inside one edit{} (L4-85): each collection whose id is
     * stored replaces it in place, the rest are appended in order. Merging against
     * the stored list (not a UI snapshot) keeps a concurrent delete or sync pull.
     */
    suspend fun upsertCollections(collections: List<Collection>) {
        // An empty merge result skips the write.
        editCollections(removeKeyWhenEmpty = false) { current ->
            val merged = current.toMutableList()
            for (collection in collections) {
                val index = merged.indexOfFirst { it.id == collection.id }
                if (index >= 0) merged[index] = collection else merged.add(collection)
            }
            merged.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Moves [collectionId] by [offset] positions within the stored list, read-modify-write
     * inside one edit{} (L4-23/85). An unknown id or an out-of-range target is a no-op and
     * returns false (nothing written).
     */
    suspend fun moveCollection(collectionId: String, offset: Int): Boolean {
        var moved = false
        editCollections(removeKeyWhenEmpty = false) { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            val target = index + offset
            moved = index >= 0 && target in current.indices
            if (moved) current.toMutableList().apply { add(target, removeAt(index)) } else null
        }
        return moved
    }

    // Always writes, even an empty list ("[]") or an unknown id (re-encoded list).
    suspend fun updateCollection(collection: Collection) {
        editCollections(removeKeyWhenEmpty = false) { current ->
            // parseCollections de-duplicates by id, so at most one entry matches.
            current.map { if (it.id == collection.id) collection else it }
        }
    }

    suspend fun removeCollection(collectionId: String) {
        editCollections(removeKeyWhenEmpty = true) { current ->
            current.filterNot { it.id == collectionId }
        }
    }

    /**
     * Read-modify-write of the stored list inside one edit{}. [transform] returns the new
     * list, or null to skip the write. An empty result removes the key when
     * [removeKeyWhenEmpty], else is stored as "[]". Each caller keeps its own historical
     * empty-list rule so the stored (and synced) JSON stays byte-identical.
     */
    private suspend fun editCollections(
        removeKeyWhenEmpty: Boolean,
        transform: (List<Collection>) -> List<Collection>?
    ) {
        store().edit { prefs ->
            val updated = transform(parseCollections(prefs[collectionsKey])) ?: return@edit
            if (updated.isEmpty() && removeKeyWhenEmpty) {
                prefs.remove(collectionsKey)
            } else {
                prefs[collectionsKey] = encodeCollections(updated)
            }
        }
    }

    fun generateId(): String = UUID.randomUUID().toString()

    fun exportToJson(collections: List<Collection>): String {
        return encodeCollections(collections)
    }

    fun importFromJson(json: String): List<Collection> {
        return parseCollections(json)
    }

    suspend fun getCurrentCollections(profileId: Int = profileManager.activeProfileId.value): List<Collection> {
        val prefs = store(profileId).data.first()
        return parseCollections(prefs[collectionsKey])
    }

    suspend fun exportCurrentProfileJson(profileId: Int = profileManager.activeProfileId.value): String? {
        val prefs = store(profileId).data.first()
        return prefs[collectionsKey]
    }

    fun validateCollectionsJson(json: String): ValidationResult {
        if (json.isBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_empty_input))
        val parsed = runCatching { localStoreJson.parseToJsonElement(json).jsonArray }
            .getOrElse { e ->
                return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_json_parse, e.message.orEmpty()))
            }
        if (parsed.isEmpty()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_empty_array))

        var folderCount = 0
        val validShapes = setOf("POSTER", "LANDSCAPE", "SQUARE", "poster", "wide", "square")

        for ((i, element) in parsed.withIndex()) {
            val item = element as? JsonObject
                ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_json_parse, "item ${i + 1} is not an object"))
            val id = item.stringField("id")
            if (id.isNullOrBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_id, i + 1))
            val title = item.stringField("title")
                ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_title, id))
            val folders = item["folders"] as? JsonArray
                ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folders_array, title))

            for ((j, f) in folders.withIndex()) {
                val folder = f as? JsonObject
                    ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_invalid, title, j + 1))
                val folderId = folder.stringField("id")
                if (folderId.isNullOrBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_missing_id, title, j + 1))
                val folderTitle = folder.stringField("title")
                    ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_missing_title, title, folderId))
                val sources = (folder["sources"] as? JsonArray) ?: (folder["catalogSources"] as? JsonArray)
                    ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_sources_array, title, folderTitle))
                val shape = folder.stringField("tileShape")
                if (shape != null && shape !in validShapes) {
                    return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_invalid_tile_shape, title, folderTitle, shape))
                }
                for ((k, s) in sources.withIndex()) {
                    val source = s as? JsonObject
                        ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_source_invalid, title, folderTitle, k + 1))
                    val provider = source.stringField("provider")?.lowercase()
                    val isAddonSource = provider == null || provider == "addon"
                    val isTmdbSource = provider == "tmdb"
                    val isTraktSource = provider == "trakt"
                    if (isAddonSource && (source.stringField("addonId") == null || source.stringField("type") == null || source.stringField("catalogId") == null)) {
                        return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_source_required_fields, title, folderTitle, k + 1))
                    }
                    if (isTmdbSource && source.stringField("tmdbSourceType") == null) {
                        return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_tmdb_type, title, folderTitle, k + 1))
                    }
                    if (isTraktSource && source.numberField("traktListId") == null) {
                        return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_trakt_list_id, title, folderTitle, k + 1))
                    }
                }
                folderCount++
            }
        }
        return ValidationResult(true, collectionCount = parsed.size, folderCount = folderCount)
    }

    private fun parseCollections(json: String?): List<Collection> {
        if (json.isNullOrBlank()) return emptyList()
        val parsed = runCatching {
            localStoreJson.decodeFromString(
                ListSerializer(SerializableCollection.serializer()),
                json
            )
        }.getOrNull().orEmpty()
        // Deduplicate by ID at parse level to prevent duplicate LazyColumn keys
        // even if stored data contains duplicates (e.g. from sync or corrupted state).
        return parsed.map { it.toDomain() }
            .associateBy { it.id }
            .values.toList()
    }

    private fun encodeCollections(collections: List<Collection>): String =
        localStoreJson.encodeToString(
            ListSerializer(SerializableCollection.serializer()),
            collections.map { it.toSerializable() }
        )

    @Serializable
    private data class SerializableCollection(
        val id: String,
        val title: String,
        val backdropImageUrl: String? = null,
        val pinToTop: Boolean = false,
        val focusGlowEnabled: Boolean? = null,
        val viewMode: String = "TABBED_GRID",
        val showAllTab: Boolean = true,
        val folders: List<SerializableFolder> = emptyList()
    )

    @Serializable
    private data class SerializableFolder(
        val id: String,
        val title: String,
        val coverImageUrl: String? = null,
        val focusGifUrl: String? = null,
        val focusGifEnabled: Boolean? = null,
        val coverEmoji: String? = null,
        val tileShape: String = "SQUARE",
        val hideTitle: Boolean = false,
        val sources: List<SerializableSource>? = null,
        val catalogSources: List<SerializableCatalogSource> = emptyList(),
        val heroBackdropUrl: String? = null,
        val heroVideoUrl: String? = null,
        val titleLogoUrl: String? = null
    )

    @Serializable
    private data class SerializableSource(
        val provider: String = "addon",
        val addonId: String? = null,
        val type: String? = null,
        val catalogId: String? = null,
        val genre: String? = null,
        val tmdbSourceType: String? = null,
        val title: String? = null,
        val tmdbId: Int? = null,
        val traktListId: Long? = null,
        val mediaType: String? = null,
        val sortBy: String? = null,
        val sortHow: String? = null,
        val filters: SerializableTmdbFilters? = null
    )

    @Serializable
    private data class SerializableTmdbFilters(
        val withGenres: String? = null,
        val withoutGenres: String? = null,
        val releaseDateGte: String? = null,
        val releaseDateLte: String? = null,
        val voteAverageGte: Double? = null,
        val voteAverageLte: Double? = null,
        val voteCountGte: Int? = null,
        val withOriginalLanguage: String? = null,
        val withOriginCountry: String? = null,
        val withKeywords: String? = null,
        val withoutKeywords: String? = null,
        val withCompanies: String? = null,
        val withoutCompanies: String? = null,
        val withNetworks: String? = null,
        val year: Int? = null,
        val watchRegion: String? = null,
        val withWatchProviders: String? = null,
        val withoutWatchProviders: String? = null
    )

    @Serializable
    private data class SerializableCatalogSource(
        val addonId: String,
        val type: String,
        val catalogId: String,
        val genre: String? = null
    )

    private fun Collection.toSerializable() = SerializableCollection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        focusGlowEnabled = focusGlowEnabled,
        viewMode = viewMode.name,
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            SerializableFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled,
                coverEmoji = folder.coverEmoji,
                tileShape = folder.tileShape.name,
                hideTitle = folder.hideTitle,
                heroBackdropUrl = folder.heroBackdropUrl,
                heroVideoUrl = folder.heroVideoUrl,
                titleLogoUrl = folder.titleLogoUrl,
                sources = folder.sources.map { it.toSerializableSource() },
                catalogSources = folder.catalogSources.map { source ->
                    SerializableCatalogSource(
                        addonId = source.addonId,
                        type = source.type,
                        catalogId = source.catalogId,
                        genre = source.genre
                    )
                }
            )
        }
    )

    private fun CollectionSource.toSerializableSource(): SerializableSource {
        return when (this) {
            is AddonCatalogCollectionSource -> SerializableSource(
                provider = "addon",
                addonId = addonId,
                type = type,
                catalogId = catalogId,
                genre = genre
            )
            is TmdbCollectionSource -> SerializableSource(
                provider = "tmdb",
                tmdbSourceType = sourceType.name,
                title = title,
                tmdbId = tmdbId,
                mediaType = mediaType.name,
                sortBy = sortBy,
                filters = filters.toSerializable()
            )
            is TraktCollectionSource -> SerializableSource(
                provider = "trakt",
                title = title,
                traktListId = traktListId,
                mediaType = mediaType.name,
                sortBy = sortBy,
                sortHow = sortHow
            )
        }
    }

    private fun TmdbCollectionFilters.toSerializable() = SerializableTmdbFilters(
        withGenres = withGenres,
        withoutGenres = withoutGenres,
        releaseDateGte = releaseDateGte,
        releaseDateLte = releaseDateLte,
        voteAverageGte = voteAverageGte,
        voteAverageLte = voteAverageLte,
        voteCountGte = voteCountGte,
        withOriginalLanguage = withOriginalLanguage,
        withOriginCountry = withOriginCountry,
        withKeywords = withKeywords,
        withoutKeywords = withoutKeywords,
        withCompanies = withCompanies,
        withoutCompanies = withoutCompanies,
        withNetworks = withNetworks,
        year = year,
        watchRegion = watchRegion,
        withWatchProviders = withWatchProviders,
        withoutWatchProviders = withoutWatchProviders
    )

    private fun SerializableCollection.toDomain() = Collection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        focusGlowEnabled = focusGlowEnabled ?: true,
        viewMode = FolderViewMode.fromString(viewMode),
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            CollectionFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled ?: true,
                coverEmoji = folder.coverEmoji,
                tileShape = PosterShape.fromString(folder.tileShape),
                hideTitle = folder.hideTitle,
                heroBackdropUrl = folder.heroBackdropUrl,
                heroVideoUrl = folder.heroVideoUrl,
                titleLogoUrl = folder.titleLogoUrl,
                sources = folder.sources?.mapNotNull { it.toDomainSource() }
                    ?: folder.catalogSources.map { source ->
                        AddonCatalogCollectionSource(
                            addonId = source.addonId,
                            type = source.type,
                            catalogId = source.catalogId,
                            genre = source.genre
                        )
                    }
            )
        }
    )

    private fun SerializableSource.toDomainSource(): CollectionSource? {
        return when (provider.lowercase()) {
            "tmdb" -> {
                val type = tmdbSourceType?.let { raw ->
                    runCatching { TmdbCollectionSourceType.valueOf(raw.uppercase()) }.getOrNull()
                } ?: return null
                val sourceSortBy = sortBy?.takeIf { it.isNotBlank() } ?: TmdbCollectionSort.POPULAR_DESC.value
                val normalizedSortBy = if (
                    type in setOf(TmdbCollectionSourceType.LIST, TmdbCollectionSourceType.COLLECTION) &&
                    sourceSortBy == TmdbCollectionSort.POPULAR_DESC.value
                ) {
                    TmdbCollectionSort.ORIGINAL.value
                } else {
                    sourceSortBy
                }
                TmdbCollectionSource(
                    sourceType = type,
                    title = title?.takeIf { it.isNotBlank() } ?: type.name.lowercase().replaceFirstChar { it.uppercase() },
                    tmdbId = tmdbId,
                    mediaType = mediaType?.let { raw ->
                        runCatching { TmdbCollectionMediaType.valueOf(raw.uppercase()) }.getOrNull()
                    } ?: TmdbCollectionMediaType.MOVIE,
                    sortBy = normalizedSortBy,
                    filters = filters?.toDomain() ?: TmdbCollectionFilters()
                )
            }
            "trakt" -> {
                val id = traktListId?.takeIf { it > 0L } ?: return null
                TraktCollectionSource(
                    title = title?.takeIf { it.isNotBlank() }
                        ?: string(R.string.collections_editor_trakt_list_with_id, id),
                    traktListId = id,
                    mediaType = mediaType?.let { raw ->
                        runCatching { TmdbCollectionMediaType.valueOf(raw.uppercase()) }.getOrNull()
                    } ?: TmdbCollectionMediaType.MOVIE,
                    sortBy = TraktListSort.normalize(sortBy),
                    sortHow = TraktSortHow.normalize(sortHow)
                )
            }
            else -> {
                val sourceAddonId = addonId?.takeIf { it.isNotBlank() } ?: return null
                val sourceType = type?.takeIf { it.isNotBlank() } ?: return null
                val sourceCatalogId = catalogId?.takeIf { it.isNotBlank() } ?: return null
                AddonCatalogCollectionSource(
                    addonId = sourceAddonId,
                    type = sourceType,
                    catalogId = sourceCatalogId,
                    genre = genre
                )
            }
        }
    }

    private fun SerializableTmdbFilters.toDomain() = TmdbCollectionFilters(
        withGenres = withGenres,
        withoutGenres = withoutGenres,
        releaseDateGte = releaseDateGte,
        releaseDateLte = releaseDateLte,
        voteAverageGte = voteAverageGte,
        voteAverageLte = voteAverageLte,
        voteCountGte = voteCountGte,
        withOriginalLanguage = withOriginalLanguage,
        withOriginCountry = withOriginCountry,
        withKeywords = withKeywords,
        withoutKeywords = withoutKeywords,
        withCompanies = withCompanies,
        withoutCompanies = withoutCompanies,
        withNetworks = withNetworks,
        year = year,
        watchRegion = watchRegion,
        withWatchProviders = withWatchProviders,
        withoutWatchProviders = withoutWatchProviders
    )
}
