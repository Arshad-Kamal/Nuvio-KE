package com.nuvio.tv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedLibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val addonBaseUrl: String?,
    val logo: String? = null,
    val addedAt: Long = 0L
) {
    fun toMetaPreview(): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.fromString(type),
            rawType = type,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres
        )
    }

    fun toLibraryEntry(): LibraryEntry = LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = posterShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        listedAt = addedAt
    )
}

fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem = SavedLibraryItem(
    id = itemId,
    type = itemType,
    name = title,
    poster = poster,
    posterShape = posterShape,
    background = background,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    logo = logo
)
