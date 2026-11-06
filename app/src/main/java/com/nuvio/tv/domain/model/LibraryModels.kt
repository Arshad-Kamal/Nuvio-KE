package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class LibraryEntry(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: PosterShape = PosterShape.POSTER,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val addonBaseUrl: String?,
    val listedAt: Long = 0L
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
}

@Immutable
data class LibraryListTab(
    val key: String,
    val title: String,
    val type: Type
) {
    enum class Type {
        WATCHLIST
    }
}

@Immutable
data class LibraryEntryInput(
    val itemId: String,
    val itemType: String,
    val title: String,
    val poster: String? = null,
    val posterShape: PosterShape = PosterShape.POSTER,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val addonBaseUrl: String? = null
)
