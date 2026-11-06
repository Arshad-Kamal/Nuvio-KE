package com.nuvio.tv.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    
    @GET("find/{external_id}")
    suspend fun findByExternalId(
        @Path("external_id") externalId: String,
        @Query("api_key") apiKey: String,
        @Query("external_source") externalSource: String = "imdb_id"
    ): Response<TmdbFindResponse>
    
    @GET("movie/{movie_id}/external_ids")
    suspend fun getMovieExternalIds(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbExternalIdsResponse>
    
    @GET("tv/{tv_id}/external_ids")
    suspend fun getTvExternalIds(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbExternalIdsResponse>

    @GET("movie/{movie_id}/videos")
    suspend fun getMovieVideos(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): Response<TmdbVideosResponse>

    @GET("tv/{tv_id}/videos")
    suspend fun getTvVideos(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): Response<TmdbVideosResponse>

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbDetailsResponse>

    @GET("tv/{tv_id}")
    suspend fun getTvDetails(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbDetailsResponse>

    @GET("movie/{movie_id}/credits")
    suspend fun getMovieCredits(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbCreditsResponse>

    @GET("tv/{tv_id}/aggregate_credits")
    suspend fun getTvAggregateCredits(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbAggregateCreditsResponse>

    @GET("movie/{movie_id}/images")
    suspend fun getMovieImages(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): Response<TmdbImagesResponse>

    @GET("tv/{tv_id}/images")
    suspend fun getTvImages(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): Response<TmdbImagesResponse>

    @GET("movie/{movie_id}/release_dates")
    suspend fun getMovieReleaseDates(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbMovieReleaseDatesResponse>

    @GET("tv/{tv_id}/content_ratings")
    suspend fun getTvContentRatings(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbTvContentRatingsResponse>

    @GET("movie/{movie_id}/recommendations")
    suspend fun getMovieRecommendations(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbRecommendationsResponse>

    @GET("tv/{tv_id}/recommendations")
    suspend fun getTvRecommendations(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbRecommendationsResponse>

    @GET("collection/{collection_id}")
    suspend fun getCollectionDetails(
        @Path("collection_id") collectionId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbCollectionResponse>

    @GET("person/{person_id}")
    suspend fun getPersonDetails(
        @Path("person_id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbPersonResponse>

    @GET("person/{person_id}/combined_credits")
    suspend fun getPersonCombinedCredits(
        @Path("person_id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbPersonCreditsResponse>

    @GET("company/{company_id}")
    suspend fun getCompanyDetails(
        @Path("company_id") companyId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbCompanyDetailsResponse>

    @GET("network/{network_id}")
    suspend fun getNetworkDetails(
        @Path("network_id") networkId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbNetworkDetailsResponse>

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String? = null,
        @Query("with_companies") withCompanies: String? = null,
        @Query("primary_release_date.lte") releaseDateLte: String? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("with_genres") withGenres: String? = null,
        @Query("primary_release_date.gte") releaseDateGte: String? = null,
        @Query("vote_average.gte") voteAverageGte: Double? = null,
        @Query("vote_average.lte") voteAverageLte: Double? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null,
        @Query("with_keywords") withKeywords: String? = null,
        @Query("year") year: Int? = null,
        @Query("watch_region") watchRegion: String? = null,
        @Query("with_watch_providers") withWatchProviders: String? = null,
        @Query("with_watch_monetization_types") withWatchMonetizationTypes: String? = null,
        @Query("without_companies") withoutCompanies: String? = null,
        @Query("without_genres") withoutGenres: String? = null,
        @Query("without_keywords") withoutKeywords: String? = null,
        @Query("without_watch_providers") withoutWatchProviders: String? = null
    ): Response<TmdbDiscoverResponse>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String? = null,
        @Query("with_companies") withCompanies: String? = null,
        @Query("with_networks") withNetworks: String? = null,
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("with_genres") withGenres: String? = null,
        @Query("first_air_date.gte") firstAirDateGte: String? = null,
        @Query("vote_average.gte") voteAverageGte: Double? = null,
        @Query("vote_average.lte") voteAverageLte: Double? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null,
        @Query("with_keywords") withKeywords: String? = null,
        @Query("first_air_date_year") firstAirDateYear: Int? = null,
        @Query("with_status") withStatus: String? = null,
        @Query("watch_region") watchRegion: String? = null,
        @Query("with_watch_providers") withWatchProviders: String? = null,
        @Query("with_watch_monetization_types") withWatchMonetizationTypes: String? = null,
        @Query("without_companies") withoutCompanies: String? = null,
        @Query("without_genres") withoutGenres: String? = null,
        @Query("without_keywords") withoutKeywords: String? = null,
        @Query("without_watch_providers") withoutWatchProviders: String? = null
    ): Response<TmdbDiscoverResponse>

    @GET("list/{list_id}")
    suspend fun getListDetails(
        @Path("list_id") listId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbListDetailsResponse>

    @GET("search/company")
    suspend fun searchCompanies(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): Response<TmdbCompanySearchResponse>

    @GET("search/collection")
    suspend fun searchCollections(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false
    ): Response<TmdbCollectionSearchResponse>

    /**
     * Spec 52 P3, the key check: 200 for a key TMDB accepts, 401 for one it does not. Only the status
     * is read. no-store keeps the candidate key out of the HTTP cache, both ways.
     */
    @Headers("Cache-Control: no-store")
    @GET("configuration")
    suspend fun checkApiKey(@Query("api_key") apiKey: String): Response<Unit>
}

@Serializable
data class TmdbFindResponse(
    @SerialName("movie_results") val movieResults: List<TmdbFindResult>? = null,
    @SerialName("tv_results") val tvResults: List<TmdbFindResult>? = null,
    @SerialName("tv_episode_results") val tvEpisodeResults: List<TmdbFindResult>? = null,
    @SerialName("tv_season_results") val tvSeasonResults: List<TmdbFindResult>? = null
)

@Serializable
data class TmdbFindResult(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("media_type") val mediaType: String? = null
)

@Serializable
data class TmdbExternalIdsResponse(
    @SerialName("id") val id: Int,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null
)

@Serializable
data class TmdbVideosResponse(
    @SerialName("id") val id: Int,
    @SerialName("results") val results: List<TmdbVideoResult> = emptyList()
)

@Serializable
data class TmdbVideoResult(
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("key") val key: String? = null,
    @SerialName("site") val site: String? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("official") val official: Boolean? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("id") val id: String? = null
)

@Serializable
data class TmdbDetailsResponse(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("genres") val genres: List<TmdbGenre>? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreatedBy>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("runtime") val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int>? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany>? = null,
    @SerialName("networks") val networks: List<TmdbNetwork>? = null,
    @SerialName("production_countries") val productionCountries: List<TmdbCountry>? = null,
    @SerialName("origin_country") val originCountry: List<String>? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("belongs_to_collection") val belongsToCollection: TmdbCollectionSummary? = null
)

@Serializable
data class TmdbCreatedBy(
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbGenre(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String
)

@Serializable
data class TmdbCompany(
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null
)

@Serializable
data class TmdbNetwork(
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null
)

@Serializable
data class TmdbCreditsResponse(
    @SerialName("cast") val cast: List<TmdbCastMember>? = null,
    @SerialName("crew") val crew: List<TmdbCrewMember>? = null
)

@Serializable
data class TmdbAggregateCreditsResponse(
    @SerialName("cast") val cast: List<TmdbAggregateCastMember>? = null,
    @SerialName("crew") val crew: List<TmdbAggregateCrewMember>? = null
)

@Serializable
data class TmdbAggregateCastMember(
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("roles") val roles: List<TmdbAggregateRole>? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("total_episode_count") val totalEpisodeCount: Int? = null
)

@Serializable
data class TmdbAggregateRole(
    @SerialName("character") val character: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null
)

@Serializable
data class TmdbAggregateCrewMember(
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("jobs") val jobs: List<TmdbAggregateJob>? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("department") val department: String? = null,
    @SerialName("total_episode_count") val totalEpisodeCount: Int? = null
)

@Serializable
data class TmdbAggregateJob(
    @SerialName("job") val job: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null
)

@Serializable
data class TmdbCastMember(
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("character") val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbCrewMember(
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("job") val job: String? = null,
    @SerialName("department") val department: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbImagesResponse(
    @SerialName("logos") val logos: List<TmdbImage>? = null,
    @SerialName("backdrops") val backdrops: List<TmdbImage>? = null
)

@Serializable
data class TmdbMovieReleaseDatesResponse(
    @SerialName("results") val results: List<TmdbMovieReleaseDateCountry>? = null
)

@Serializable
data class TmdbMovieReleaseDateCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("release_dates") val releaseDates: List<TmdbMovieReleaseDateItem>? = null
)

@Serializable
data class TmdbMovieReleaseDateItem(
    @SerialName("certification") val certification: String? = null,
    @SerialName("descriptors") val descriptors: List<String>? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("type") val type: Int? = null
)

@Serializable
data class TmdbTvContentRatingsResponse(
    @SerialName("results") val results: List<TmdbTvContentRatingItem>? = null
)

@Serializable
data class TmdbTvContentRatingItem(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("descriptors") val descriptors: List<String>? = null
)

@Serializable
data class TmdbImage(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("iso_3166_1") val iso31661: String? = null
)

@Serializable
data class TmdbCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("name") val name: String? = null
)

@Serializable
data class TmdbRecommendationsResponse(
    @SerialName("results") val results: List<TmdbRecommendationResult>? = null
)

@Serializable
data class TmdbDiscoverResponse(
    @SerialName("page") val page: Int? = null,
    @SerialName("results") val results: List<TmdbDiscoverResult>? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
    @SerialName("total_results") val totalResults: Int? = null
)

@Serializable
data class TmdbListDetailsResponse(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("item_count") val itemCount: Int? = null,
    @SerialName("items") val items: List<TmdbListItem>? = null,
    @SerialName("page") val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null
)

@Serializable
data class TmdbListItem(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("genre_ids") val genreIds: List<Int>? = null
)

@Serializable
data class TmdbCompanySearchResponse(
    @SerialName("page") val page: Int? = null,
    @SerialName("results") val results: List<TmdbCompanySearchResult>? = null,
    @SerialName("total_pages") val totalPages: Int? = null
)

@Serializable
data class TmdbCompanySearchResult(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null
)

@Serializable
data class TmdbCollectionSearchResponse(
    @SerialName("page") val page: Int? = null,
    @SerialName("results") val results: List<TmdbCollectionSearchResult>? = null,
    @SerialName("total_pages") val totalPages: Int? = null
)

@Serializable
data class TmdbCollectionSearchResult(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("overview") val overview: String? = null
)

@Serializable
data class TmdbDiscoverResult(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("popularity") val popularity: Double? = null
)

@Serializable
data class TmdbRecommendationResult(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null
)

// ── Person / Cast Detail DTOs ──

@Serializable
data class TmdbPersonResponse(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("biography") val biography: String? = null,
    @SerialName("birthday") val birthday: String? = null,
    @SerialName("deathday") val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("also_known_as") val alsoKnownAs: List<String>? = null,
    @SerialName("imdb_id") val imdbId: String? = null
)

@Serializable
data class TmdbPersonCreditsResponse(
    @SerialName("cast") val cast: List<TmdbPersonCreditCast>? = null,
    @SerialName("crew") val crew: List<TmdbPersonCreditCrew>? = null
)

@Serializable
data class TmdbPersonCreditCast(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("character") val character: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int>? = null
)

@Serializable
data class TmdbPersonCreditCrew(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("department") val department: String? = null,
    @SerialName("job") val job: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int>? = null
)

@Serializable
data class TmdbCollectionResponse(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("parts") val parts: List<TmdbCollectionPart>? = null
)

@Serializable
data class TmdbCollectionPart(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null
)

@Serializable
data class TmdbCollectionSummary(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null
)

@Serializable
data class TmdbCompanyDetailsResponse(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("headquarters") val headquarters: String? = null,
    @SerialName("homepage") val homepage: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null
)

@Serializable
data class TmdbNetworkDetailsResponse(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("headquarters") val headquarters: String? = null,
    @SerialName("homepage") val homepage: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null
)
