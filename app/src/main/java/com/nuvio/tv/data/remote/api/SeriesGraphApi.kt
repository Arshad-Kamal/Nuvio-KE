package com.nuvio.tv.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

@Serializable
data class SeriesGraphEpisodeRatingDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("tconst") val tconst: String? = null
)

@Serializable
data class SeriesGraphSeasonRatingsDto(
    @SerialName("episodes") val episodes: List<SeriesGraphEpisodeRatingDto>? = null
)

interface SeriesGraphApi {
    @GET("api/shows/{tmdbId}/season-ratings")
    suspend fun getSeasonRatings(
        @Path("tmdbId") tmdbId: Int
    ): Response<List<SeriesGraphSeasonRatingsDto>>
}

interface ImdbTapframeApi {
    @GET("api/shows/{imdbId}/season-ratings")
    suspend fun getSeasonRatings(
        @Path("imdbId") imdbId: String
    ): Response<List<SeriesGraphSeasonRatingsDto>>
}
