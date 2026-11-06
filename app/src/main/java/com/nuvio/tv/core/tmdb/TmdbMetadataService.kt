package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.core.util.getOrNullUnlessCancelled
import com.nuvio.tv.core.util.getOrStartShared
import com.nuvio.tv.data.mapper.metaReleaseDate
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbCastMember
import com.nuvio.tv.data.remote.api.TmdbCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbCrewMember
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbImage
import com.nuvio.tv.data.remote.api.TmdbImagesResponse
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDateCountry
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCast
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCrew
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.data.remote.api.TmdbTvContentRatingItem
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.trailer.isYoutubeVideoId
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaReleaseDateCountry
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.PersonCredit
import com.nuvio.tv.domain.model.PersonDepartment
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val TAG = "TmdbMetadataService"
private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"

/**
 * TMDB detail-page and browse lookups. Since the TMDB enrichment cut no screen
 * merges TMDB data over addon metadata; what remains: [fetchEnrichment] (the
 * synthetic detail meta for a tmdb: id no addon resolves), [fetchDetailFacts]
 * (facts addons lack, filled only where the addon meta is empty: [withTmdbFacts]),
 * [fetchCollectionRef] (the detail Collection row's collection id), More Like This,
 * movie collections, person (cast) detail, and company/network browse.
 */
@Singleton
class TmdbMetadataService(
    private val tmdbApi: TmdbApi,
    private val ioDispatcher: CoroutineDispatcher,
    private val apiKeys: TmdbApiKeySource
) {
    @Inject
    constructor(tmdbApi: TmdbApi, apiKeys: TmdbApiKeySource) : this(tmdbApi, Dispatchers.IO, apiKeys)

    // Shared enrichment requests run here, not in the first caller's coroutine, so a
    // cancelled caller never cancels the request other callers are awaiting.
    private val serviceScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // In-memory caches
    private val enrichmentCache = ConcurrentHashMap<String, TmdbEnrichment>()
    private val enrichmentInFlight = ConcurrentHashMap<String, Deferred<TmdbEnrichment?>>()
    private val detailFactsCache = ConcurrentHashMap<String, TmdbDetailFacts>()
    private val detailFactsInFlight = ConcurrentHashMap<String, Deferred<TmdbDetailFacts?>>()
    private val collectionRefCache = ConcurrentHashMap<String, CachedCollectionRef>()
    private val personCache = ConcurrentHashMap<String, PersonDetail>()
    private val moreLikeThisCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val entityHeaderCache = ConcurrentHashMap<String, TmdbEntityHeader>()
    private val entityRailCache = ConcurrentHashMap<String, TmdbEntityRailPageResult>()
    private val entityBrowseCache = ConcurrentHashMap<String, TmdbEntityBrowseData>()

    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en"
    ): TmdbEnrichment? {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage"
        enrichmentCache[cacheKey]?.let { return it }
        val numericId = tmdbId.toIntOrNull() ?: return null
        return enrichmentInFlight.getOrStartShared(serviceScope, cacheKey) {
            loadEnrichment(numericId, contentType, normalizedLanguage)
                ?.also { enrichmentCache[cacheKey] = it }
        }.await()
    }

    private suspend fun loadEnrichment(
        numericId: Int,
        contentType: ContentType,
        normalizedLanguage: String
    ): TmdbEnrichment? {
        val tmdbType = contentType.tmdbType()
        return try {
            // Fetch details, credits, images, age rating, and trailers in parallel
            coroutineScope {
                val detailsDeferred = async { fetchDetails(numericId, tmdbType, normalizedLanguage) }
                val creditsDeferred = async {
                    when (tmdbType) {
                        "tv" -> {
                            val aggregate = tmdbApi.getTvAggregateCredits(numericId, apiKeys.key(), normalizedLanguage).body()
                            // Map aggregate credits to standard format for unified processing
                            aggregate?.let { agg ->
                                TmdbCreditsResponse(
                                    cast = agg.cast?.map { member ->
                                        TmdbCastMember(
                                            id = member.id,
                                            name = member.name,
                                            character = member.roles?.firstOrNull()?.character,
                                            profilePath = member.profilePath
                                        )
                                    },
                                    crew = agg.crew?.flatMap { member ->
                                        member.jobs?.map { job ->
                                            TmdbCrewMember(
                                                id = member.id,
                                                name = member.name,
                                                job = job.job,
                                                department = member.department,
                                                profilePath = member.profilePath
                                            )
                                        } ?: emptyList()
                                    }
                                )
                            }
                        }
                        else -> tmdbApi.getMovieCredits(numericId, apiKeys.key(), normalizedLanguage).body()
                    }
                }
                val imagesDeferred = async {
                    val includeImageLanguage = buildIncludeImageLanguage(normalizedLanguage)
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvImages(numericId, apiKeys.key(), includeImageLanguage)
                        else -> tmdbApi.getMovieImages(numericId, apiKeys.key(), includeImageLanguage)
                    }.body()
                }
                val certificationDeferred = async {
                    fetchCertification(numericId, tmdbType, normalizedLanguage).certification
                }
                val trailersDeferred = async {
                    fetchTmdbTrailers(
                        tmdbId = numericId,
                        tmdbType = tmdbType,
                        preferredLanguage = normalizedLanguage
                    )
                }
                mapEnrichment(
                    isTv = tmdbType == "tv",
                    normalizedLanguage = normalizedLanguage,
                    details = detailsDeferred.await(),
                    credits = creditsDeferred.await(),
                    images = imagesDeferred.await(),
                    certification = certificationDeferred.await(),
                    trailers = trailersDeferred.await()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch TMDB enrichment: ${e.message}", e)
            null
        }
    }

    private fun mapEnrichment(
        isTv: Boolean,
        normalizedLanguage: String,
        details: TmdbDetailsResponse?,
        credits: TmdbCreditsResponse?,
        images: TmdbImagesResponse?,
        certification: TmdbCertification?,
        trailers: List<MetaTrailer>
    ): TmdbEnrichment? {
        val ageRating = certification?.rating
        val genres = details?.genres?.mapNotNull { genre ->
            genre.name.trim().takeIf { name -> name.isNotBlank() }
        } ?: emptyList()
        val description = details?.overview?.takeIf { it.isNotBlank() }
        val status = details?.status?.trim()?.takeIf { it.isNotBlank() }
        val releaseInfo = if (isTv) {
            details?.firstAirDate.yearPart()?.let { startYear ->
                buildShowYearRange(startYear, details?.lastAirDate.yearPart(), status)
            }
        } else {
            details?.releaseDate.yearPart()
        }
        val rating = details?.voteAverage
        val runtime = details?.runtime ?: details?.episodeRunTime?.firstOrNull()
        val countries = details?.productionCountries
            ?.mapNotNull { it.iso31661?.trim()?.uppercase()?.takeIf { code -> code.isNotBlank() } }
            ?.takeIf { it.isNotEmpty() }
            ?: details?.originCountry?.takeIf { it.isNotEmpty() }
        val language = details?.originalLanguage?.takeIf { it.isNotBlank() }
        val rawLocalizedTitle = (details?.title ?: details?.name)?.takeIf { it.isNotBlank() }
        val originalTitle = details?.originalTitleOrNull()

        // If TMDB returned the original title because no translation
        // exists for the user's language, treat as no localized title
        // so the caller keeps the addon-provided title instead.
        val localizedTitle = if (
            rawLocalizedTitle != null &&
            originalTitle != null &&
            rawLocalizedTitle == originalTitle &&
            !normalizedLanguage.startsWith("en") &&
            language != null &&
            !normalizedLanguage.startsWith(language)
        ) {
            null
        } else {
            rawLocalizedTitle
        }
        val productionCompanies = details.productionCompanies()
        val networks = details.networks()
        val poster = tmdbImageUrl(details?.posterPath, size = "w500")
        val backdrop = tmdbImageUrl(details?.backdropPath, size = TmdbImageSizes.backdrop)

        val logoPath = images?.logos?.let {
            selectBestLocalizedImagePath(it, normalizedLanguage)
        }

        val logo = tmdbImageUrl(logoPath, size = "original")

        val castMembers = credits?.cast
            .orEmpty()
            .mapNotNull { member ->
                val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MetaCastMember(
                    name = name,
                    character = member.character?.takeIf { it.isNotBlank() },
                    photo = tmdbImageUrl(member.profilePath, size = "w500"),
                    tmdbId = member.id
                )
            }

        val creator = if (isTv) {
            details?.createdBy
                .orEmpty()
                .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }
        } else {
            emptyList()
        }

        val director = credits?.crew
            .orEmpty()
            .filter { it.job.equals("Director", ignoreCase = true) }
            .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }

        val writer = credits?.crew
            .orEmpty()
            .filter { crew ->
                val job = crew.job?.lowercase() ?: ""
                job.contains("writer") || job.contains("screenplay")
            }
            .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }

        // Expose the director (the creator for TV); writers only when there is none.
        val exposedDirector = if (isTv) creator else director
        val exposedWriter = if (exposedDirector.isEmpty()) writer else emptyList()

        if (
            genres.isEmpty() && description == null && backdrop == null && logo == null &&
            poster == null && castMembers.isEmpty() && director.isEmpty() && writer.isEmpty() &&
            releaseInfo == null && rating == null && runtime == null && countries.isNullOrEmpty() && language == null &&
            productionCompanies.isEmpty() && networks.isEmpty() && ageRating == null && status == null &&
            trailers.isEmpty()
        ) {
            return null
        }

        return TmdbEnrichment(
            localizedTitle = localizedTitle,
            description = description,
            genres = genres,
            backdrop = backdrop,
            logo = logo,
            poster = poster,
            castMembers = castMembers,
            releaseInfo = releaseInfo,
            rating = rating,
            runtimeMinutes = runtime,
            director = exposedDirector,
            writer = exposedWriter,
            productionCompanies = productionCompanies,
            networks = networks,
            ageRating = ageRating,
            ageRatingDescriptors = certification?.descriptors.orEmpty(),
            status = status,
            countries = countries,
            language = language,
            originalTitle = originalTitle,
            trailers = trailers
        )
    }

    /**
     * Regions whose dates and ratings a viewer with this TMDB [language] wants first: the
     * language's region, else its default region, then US, GB (Spec 26 R2; the order the
     * certification pick uses). The About sheet picks its release country with it.
     */
    fun preferredReleaseRegions(language: String?): List<String> =
        preferredRegions(normalizeTmdbLanguage(language))

    /**
     * Facts addon metas lack (studios/networks, original title, TMDB score and votes,
     * the local certification and its descriptors, movie release dates): one details
     * and one certification request in parallel, cached per id, type and language.
     * The movie's collection ref is cached on the way, so [fetchCollectionRef] after
     * this makes no request. Failure returns null and is not cached.
     */
    suspend fun fetchDetailFacts(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en"
    ): TmdbDetailFacts? {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${contentType.tmdbType()}:$normalizedLanguage"
        detailFactsCache[cacheKey]?.let { return it }
        val numericId = tmdbId.toIntOrNull() ?: return null
        return detailFactsInFlight.getOrStartShared(serviceScope, cacheKey) {
            loadDetailFacts(numericId, contentType.tmdbType(), normalizedLanguage)
                ?.also { detailFactsCache[cacheKey] = it }
        }.await()
    }

    private suspend fun loadDetailFacts(
        numericId: Int,
        tmdbType: String,
        normalizedLanguage: String
    ): TmdbDetailFacts? = try {
        coroutineScope {
            // A failed certification request leaves the details facts standing.
            val certificationDeferred = async {
                runCatching { fetchCertification(numericId, tmdbType, normalizedLanguage) }
                    .getOrNullUnlessCancelled() ?: FetchedCertification(null, emptyList())
            }
            val details = fetchDetails(numericId, tmdbType, normalizedLanguage)
            val fetched = certificationDeferred.await()
            if (details == null) return@coroutineScope null
            if (tmdbType != "tv") cacheCollectionRef(numericId, normalizedLanguage, details)
            TmdbDetailFacts(
                originalTitle = details.originalTitleOrNull(),
                voteAverage = details.voteAverage?.takeIf { it > 0.0 },
                voteCount = details.voteCount?.takeIf { it > 0 },
                productionCompanies = details.productionCompanies(),
                networks = details.networks(),
                ageRating = fetched.certification?.rating,
                ageRatingDescriptors = fetched.certification?.descriptors.orEmpty(),
                releaseDates = fetched.releaseDates
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch TMDB detail facts for $numericId: ${e.message}")
        null
    }

    private suspend fun fetchDetails(numericId: Int, tmdbType: String, normalizedLanguage: String) =
        when (tmdbType) {
            "tv" -> tmdbApi.getTvDetails(numericId, apiKeys.key(), normalizedLanguage)
            else -> tmdbApi.getMovieDetails(numericId, apiKeys.key(), normalizedLanguage)
        }.body()

    /** Caches a movie's collection ref (null = no collection) for [fetchCollectionRef]. */
    private fun cacheCollectionRef(
        numericId: Int,
        normalizedLanguage: String,
        details: TmdbDetailsResponse
    ): TmdbCollectionRef? {
        val ref = details.belongsToCollection?.let { TmdbCollectionRef(id = it.id, name = it.name) }
        collectionRefCache["$numericId:$normalizedLanguage"] = CachedCollectionRef(ref)
        return ref
    }

    /** The local certification (and, for movies, every country's release dates). */
    private suspend fun fetchCertification(
        numericId: Int,
        tmdbType: String,
        normalizedLanguage: String
    ): FetchedCertification = when (tmdbType) {
        "tv" -> {
            val ratings = tmdbApi.getTvContentRatings(numericId, apiKeys.key()).body()?.results.orEmpty()
            FetchedCertification(selectTvCertification(ratings, normalizedLanguage), emptyList())
        }
        else -> {
            val releases = tmdbApi.getMovieReleaseDates(numericId, apiKeys.key()).body()?.results.orEmpty()
                .toMetaReleaseDates()
            FetchedCertification(selectMovieCertification(releases, normalizedLanguage), releases)
        }
    }

    /**
     * The TMDB collection ("franchise") a movie belongs to, from one movie-details
     * request. TV has no belongs_to_collection, so non-movies return null without a
     * request. A successful lookup is cached, including "no collection".
     */
    suspend fun fetchCollectionRef(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en"
    ): TmdbCollectionRef? {
        if (contentType.tmdbType() == "tv") return null
        val numericId = tmdbId.toIntOrNull() ?: return null
        val normalizedLanguage = normalizeTmdbLanguage(language)
        collectionRefCache["$numericId:$normalizedLanguage"]?.let { return it.ref }

        return withContext(ioDispatcher) {
            try {
                val response = tmdbApi.getMovieDetails(numericId, apiKeys.key(), normalizedLanguage)
                val body = response.body() ?: return@withContext null
                cacheCollectionRef(numericId, normalizedLanguage, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch collection ref for $tmdbId: ${e.message}")
                null
            }
        }
    }

    private suspend fun fetchTmdbTrailers(
        tmdbId: Int,
        tmdbType: String,
        preferredLanguage: String
    ): List<MetaTrailer> {
        val localizedResults = fetchTmdbVideos(tmdbId, tmdbType, preferredLanguage, label = "localized")

        val mergedResults = if (
            localizedResults.isNotEmpty() ||
            preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)
        ) {
            localizedResults
        } else {
            localizedResults + fetchTmdbVideos(tmdbId, tmdbType, TMDB_TRAILER_FALLBACK_LANGUAGE, label = "fallback")
        }

        return rankTmdbTrailers(mergedResults)
            .mapNotNull { video ->
                val ytId = video.key?.trim()?.takeIf(::isYoutubeVideoId) ?: return@mapNotNull null
                MetaTrailer(
                    source = "TMDB",
                    type = video.type?.takeIf(String::isNotBlank),
                    name = video.name?.takeIf(String::isNotBlank),
                    ytId = ytId,
                    lang = video.iso6391?.takeIf(String::isNotBlank)
                )
            }
            .distinctBy { it.ytId }
    }

    /** TMDB videos for one language; a failed request degrades to an empty list. */
    private suspend fun fetchTmdbVideos(
        tmdbId: Int,
        tmdbType: String,
        language: String,
        label: String
    ): List<TmdbVideoResult> = try {
        when (tmdbType) {
            "tv" -> tmdbApi.getTvVideos(tmdbId, apiKeys.key(), language)
            else -> tmdbApi.getMovieVideos(tmdbId, apiKeys.key(), language)
        }.body()?.results.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val kind = if (tmdbType == "tv") "TV" else "movie"
        Log.w(TAG, "Failed to fetch $label $kind trailers for $tmdbId: ${e.message}")
        emptyList()
    }

    private fun rankTmdbTrailers(results: List<TmdbVideoResult>): List<TmdbVideoResult> {
        fun typePriority(type: String?): Int = when (type?.trim()?.lowercase(Locale.US)) {
            "trailer" -> 0
            "teaser" -> 1
            "clip" -> 2
            "featurette" -> 3
            else -> 4
        }

        return results
            .asSequence()
            .filter { video ->
                video.site.equals("YouTube", ignoreCase = true) &&
                    !video.key.isNullOrBlank()
            }
            .sortedWith(
                compareBy<TmdbVideoResult> { typePriority(it.type) }
                    .thenByDescending { it.official == true }
                    .thenByDescending { it.publishedAt.orEmpty() }
            )
            .toList()
    }

    suspend fun fetchMoreLikeThis(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en",
        maxItems: Int = 12
    ): List<MetaPreview> = withContext(ioDispatcher) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage:more_like"
        moreLikeThisCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyList()
        val tmdbType = contentType.tmdbType()

        val includeImageLanguage = buildIncludeImageLanguage(normalizedLanguage)

        try {
            // No body = a failed request (a 401 without a key included): return empty, never cache it,
            // so the row fills once the request can succeed.
            val recommendations = when (tmdbType) {
                "tv" -> tmdbApi.getTvRecommendations(numericId, apiKeys.key(), normalizedLanguage).body()
                else -> tmdbApi.getMovieRecommendations(numericId, apiKeys.key(), normalizedLanguage).body()
            } ?: return@withContext emptyList()

            val rawResults = recommendations.results
                .orEmpty()
                .filter { it.id > 0 }
                .distinctBy { it.id }
            val languageCode = normalizedLanguage.substringBefore("-")
            val sortedResults = rawResults
                .sortedWith(
                    compareByDescending<TmdbRecommendationResult> {
                        it.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                    }
                        .thenByDescending { it.voteCount ?: 0 }
                        .thenByDescending { it.voteAverage ?: 0.0 }
                )
            val qualityFilteredResults = sortedResults.filter { rec ->
                val voteCount = rec.voteCount ?: 0
                val voteAverage = rec.voteAverage ?: 0.0
                val localized = rec.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                localized || voteCount >= 20 || voteAverage >= 6.0
            }
            val recommendationResults = (if (qualityFilteredResults.isNotEmpty()) {
                qualityFilteredResults
            } else {
                sortedResults
            }).take(maxItems.coerceAtLeast(1))

            val items = coroutineScope {
                recommendationResults.map { rec ->
                    async {
                        val recTmdbType = when (rec.mediaType?.trim()?.lowercase()) {
                            "tv" -> "tv"
                            "movie" -> "movie"
                            else -> tmdbType
                        }
                        val recContentType = if (recTmdbType == "tv") ContentType.SERIES else ContentType.MOVIE
                        val title = rec.title?.takeIf { it.isNotBlank() }
                            ?: rec.name?.takeIf { it.isNotBlank() }
                            ?: rec.originalTitle?.takeIf { it.isNotBlank() }
                            ?: rec.originalName?.takeIf { it.isNotBlank() }
                            ?: return@async null

                        val localizedBackdropPath = runCatching {
                            when (recTmdbType) {
                                "tv" -> tmdbApi.getTvImages(rec.id, apiKeys.key(), includeImageLanguage).body()
                                else -> tmdbApi.getMovieImages(rec.id, apiKeys.key(), includeImageLanguage).body()
                            }
                        }.getOrNullUnlessCancelled()?.let { images ->
                            selectBestLocalizedImagePath(
                                images = images.backdrops.orEmpty(),
                                normalizedLanguage = normalizedLanguage
                            )
                        }

                        val backdrop = tmdbImageUrl(localizedBackdropPath ?: rec.backdropPath, size = TmdbImageSizes.backdrop)
                        val fallbackPoster = tmdbImageUrl(rec.posterPath, size = "w780")

                        val releaseInfo = if (recTmdbType == "tv") {
                            val startYear = rec.firstAirDate.yearPart()
                            if (startYear != null) {
                                val tvDetails = runCatching {
                                    tmdbApi.getTvDetails(rec.id, apiKeys.key(), normalizedLanguage).body()
                                }.getOrNullUnlessCancelled()
                                val status = tvDetails?.status
                                val endYear = tvDetails?.lastAirDate.yearPart()
                                buildShowYearRange(startYear, endYear, status)
                            } else null
                        } else {
                            rec.releaseDate.yearPart()
                        }

                        MetaPreview(
                            id = "tmdb:${rec.id}",
                            type = recContentType,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = rec.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = null,
                            tmdbRating = rec.voteAverage?.toFloat(),
                            genres = emptyList(),
                            landscapePoster = backdrop,
                            rawPosterUrl = fallbackPoster
                        )
                    }
                }.awaitAll().filterNotNull()
            }

            moreLikeThisCache[cacheKey] = items
            items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch recommendations for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    private val collectionCache = ConcurrentHashMap<String, List<MetaPreview>>()

    suspend fun fetchMovieCollection(
        collectionId: Int,
        language: String = "en"
    ): List<MetaPreview> = withContext(ioDispatcher) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$collectionId:$normalizedLanguage:collection"
        collectionCache[cacheKey]?.let { return@withContext it }

        try {
            // No body = a failed request: empty and uncached, as in fetchMoreLikeThis.
            val collectionResponse = tmdbApi.getCollectionDetails(collectionId, apiKeys.key(), normalizedLanguage).body()
                ?: return@withContext emptyList()
            val rawParts = collectionResponse.parts.orEmpty()

            // Show in release order; distinctBy guards LazyRow keys against a
            // malformed response repeating a part id (remote data is untrusted).
            val sortedParts = rawParts.distinctBy { it.id }.sortedBy { it.releaseDate ?: "9999" }

            val includeImageLanguage = buildIncludeImageLanguage(normalizedLanguage)

            val items = coroutineScope {
                sortedParts.map { part ->
                    async {
                        val title = part.title ?: return@async null

                        val localizedBackdropPath = runCatching {
                            tmdbApi.getMovieImages(part.id, apiKeys.key(), includeImageLanguage).body()
                        }.getOrNullUnlessCancelled()?.let { images ->
                            selectBestLocalizedImagePath(
                                images = images.backdrops.orEmpty(),
                                normalizedLanguage = normalizedLanguage
                            )
                        }

                        val backdrop = tmdbImageUrl(localizedBackdropPath ?: part.backdropPath, size = TmdbImageSizes.backdrop)
                        val fallbackPoster = tmdbImageUrl(part.posterPath, size = "w780")
                        val releaseInfo = part.releaseDate?.take(4)

                        MetaPreview(
                            id = "tmdb:${part.id}",
                            type = ContentType.MOVIE,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = part.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = null,
                            tmdbRating = part.voteAverage?.toFloat(),
                            genres = emptyList(),
                            landscapePoster = backdrop,
                            rawPosterUrl = fallbackPoster
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            collectionCache[cacheKey] = items
            items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch collection for $collectionId: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchEntityBrowse(
        entityKind: TmdbEntityKind,
        entityId: Int,
        sourceType: String,
        fallbackName: String? = null,
        language: String = "en"
    ): TmdbEntityBrowseData? = withContext(ioDispatcher) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val normalizedSourceType = normalizeEntitySourceType(sourceType)
        val cacheKey = "${entityKind.routeValue}:$entityId:$normalizedSourceType:$normalizedLanguage"
        entityBrowseCache[cacheKey]?.let { return@withContext it }

        val railKeys = buildEntityMediaOrder(entityKind, normalizedSourceType)
            .flatMap { mediaType -> TmdbEntityRailType.entries.map { railType -> mediaType to railType } }

        // Header and up to six rails are independent requests: fetch them concurrently.
        // awaitAll keeps the results in railKeys order, so rail order is unchanged.
        val (tmdbHeader, railPages) = coroutineScope {
            val headerDeferred = async {
                fetchEntityHeader(entityKind = entityKind, entityId = entityId, fallbackName = fallbackName, language = normalizedLanguage)
            }
            val pagesDeferred = railKeys.map { (mediaType, railType) ->
                async {
                    loadEntityRailPage(
                        entityKind = entityKind,
                        entityId = entityId,
                        mediaType = mediaType,
                        railType = railType,
                        language = normalizedLanguage,
                        page = 1
                    )
                }
            }
            headerDeferred.await() to pagesDeferred.awaitAll()
        }

        val rails = railKeys.zip(railPages).mapNotNull { (key, pageResult) ->
            val items = pageResult?.items.orEmpty()
            if (items.isEmpty()) {
                null
            } else {
                TmdbEntityRail(
                    mediaType = key.first,
                    railType = key.second,
                    items = items,
                    currentPage = 1,
                    hasMore = pageResult?.hasMore == true,
                    isLoading = false
                )
            }
        }

        // The header and every rail request failed: null (an error page), not a fallback header
        // over no rails, which would read as "no titles" (Spec 29g, mockup stage 11).
        if (tmdbHeader == null && railPages.all { it == null }) return@withContext null
        val fallbackHeaderName = fallbackName?.takeIf { it.isNotBlank() }
        if (tmdbHeader == null && fallbackHeaderName == null && rails.isEmpty()) return@withContext null

        val data = TmdbEntityBrowseData(
            header = tmdbHeader ?: TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = fallbackHeaderName ?: "Unknown",
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null
            ),
            rails = rails
        )
        // Cache only real TMDB data: a fallback header or a failed rail must be retried
        // on the next visit, not pinned for the app's lifetime.
        if (tmdbHeader != null && railPages.none { it == null }) {
            entityBrowseCache[cacheKey] = data
        }
        data
    }

    /** The entity header from TMDB, or null when TMDB has none (failure or empty body). */
    private suspend fun fetchEntityHeader(
        entityKind: TmdbEntityKind,
        entityId: Int,
        fallbackName: String?,
        language: String
    ): TmdbEntityHeader? {
        val cacheKey = "${entityKind.routeValue}:$entityId:$language:header"
        entityHeaderCache[cacheKey]?.let { return it }

        val header = try {
            when (entityKind) {
                TmdbEntityKind.COMPANY -> {
                    val body = tmdbApi.getCompanyDetails(entityId, apiKeys.key()).body()
                    if (body == null) {
                        null
                    } else {
                        TmdbEntityHeader(
                            id = body.id,
                            kind = entityKind,
                            name = body.name?.takeIf { it.isNotBlank() }
                                ?: fallbackName?.takeIf { it.isNotBlank() }
                                ?: "Unknown",
                            logo = tmdbImageUrl(body.logoPath, size = "w500"),
                            originCountry = body.originCountry?.takeIf { it.isNotBlank() },
                            secondaryLabel = body.headquarters?.takeIf { it.isNotBlank() },
                            description = body.description?.takeIf { it.isNotBlank() }
                        )
                    }
                }

                TmdbEntityKind.NETWORK -> {
                    val body = tmdbApi.getNetworkDetails(entityId, apiKeys.key()).body()
                    if (body == null) {
                        null
                    } else {
                        TmdbEntityHeader(
                            id = body.id,
                            kind = entityKind,
                            name = body.name?.takeIf { it.isNotBlank() }
                                ?: fallbackName?.takeIf { it.isNotBlank() }
                                ?: "Unknown",
                            logo = tmdbImageUrl(body.logoPath, size = "w500"),
                            originCountry = body.originCountry?.takeIf { it.isNotBlank() },
                            secondaryLabel = body.headquarters?.takeIf { it.isNotBlank() },
                            description = null
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch ${entityKind.routeValue} header for $entityId: ${e.message}")
            null
        }

        if (header != null) {
            entityHeaderCache[cacheKey] = header
        }
        return header
    }

    suspend fun fetchEntityRailPage(
        entityKind: TmdbEntityKind,
        entityId: Int,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int
    ): TmdbEntityRailPageResult =
        loadEntityRailPage(entityKind, entityId, mediaType, railType, language, page)
            ?: TmdbEntityRailPageResult(items = emptyList(), hasMore = false)

    /** One rail page; null when the TMDB request failed or returned no body. */
    private suspend fun loadEntityRailPage(
        entityKind: TmdbEntityKind,
        entityId: Int,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int
    ): TmdbEntityRailPageResult? {
        if (entityKind == TmdbEntityKind.NETWORK && mediaType == TmdbEntityMediaType.MOVIE) {
            return TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }

        val cacheKey = "${entityKind.routeValue}:$entityId:${mediaType.value}:${railType.value}:$language:page:$page"
        entityRailCache[cacheKey]?.let { return it }

        val today = LocalDate.now().toString()
        val voteCountFloor = if (railType == TmdbEntityRailType.TOP_RATED) TOP_RATED_VOTE_COUNT_FLOOR else null
        val result = try {
            val response = when (mediaType) {
                TmdbEntityMediaType.MOVIE -> {
                    tmdbApi.discoverMovies(
                        apiKey = apiKeys.key(),
                        language = language,
                        page = page,
                        sortBy = movieSortBy(railType),
                        withCompanies = entityId.toString(),
                        releaseDateLte = if (railType == TmdbEntityRailType.RECENT) today else null,
                        voteCountGte = voteCountFloor
                    ).body()
                }

                TmdbEntityMediaType.TV -> {
                    tmdbApi.discoverTv(
                        apiKey = apiKeys.key(),
                        language = language,
                        page = page,
                        sortBy = tvSortBy(railType),
                        withCompanies = if (entityKind == TmdbEntityKind.COMPANY) entityId.toString() else null,
                        withNetworks = if (entityKind == TmdbEntityKind.NETWORK) entityId.toString() else null,
                        firstAirDateLte = if (railType == TmdbEntityRailType.RECENT || entityKind == TmdbEntityKind.NETWORK) today else null,
                        voteCountGte = voteCountFloor,
                        withStatus = if (entityKind == TmdbEntityKind.NETWORK) "0|3|4" else null
                    ).body()
                }
            } ?: return null

            val results = response.results.orEmpty()
            val totalPages = response.totalPages ?: page

            val mappedItems = results
                .filter { it.id > 0 }
                .mapNotNull { discoverItem ->
                    mapEntityDiscoverResult(
                        result = discoverItem,
                        mediaType = mediaType
                    )
                }
                .take(ENTITY_RAIL_MAX_ITEMS)

            TmdbEntityRailPageResult(
                items = mappedItems,
                hasMore = page < totalPages && mappedItems.isNotEmpty()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to fetch ${entityKind.routeValue} rail ${railType.value}/${mediaType.value} for $entityId: ${e.message}"
            )
            return null
        }

        if (result.items.isNotEmpty()) {
            entityRailCache[cacheKey] = result
        }
        return result
    }

    private fun mapEntityDiscoverResult(
        result: TmdbDiscoverResult,
        mediaType: TmdbEntityMediaType
    ): MetaPreview? {
        val title = result.title?.takeIf { it.isNotBlank() }
            ?: result.name?.takeIf { it.isNotBlank() }
            ?: result.originalTitle?.takeIf { it.isNotBlank() }
            ?: result.originalName?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = tmdbImageUrl(result.posterPath, size = "w500")
            ?: tmdbImageUrl(result.backdropPath, size = "w780")
            ?: return null
        val background = tmdbImageUrl(result.backdropPath, size = TmdbImageSizes.backdrop)
        val releaseInfo = when (mediaType) {
            TmdbEntityMediaType.MOVIE -> result.releaseDate?.take(4)
            TmdbEntityMediaType.TV -> result.firstAirDate?.take(4)
        }

        return MetaPreview(
            id = "tmdb:${result.id}",
            type = if (mediaType == TmdbEntityMediaType.TV) ContentType.SERIES else ContentType.MOVIE,
            name = title,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = null,
            description = result.overview?.takeIf { it.isNotBlank() },
            releaseInfo = releaseInfo,
            imdbRating = null,
            tmdbRating = result.voteAverage?.toFloat(),
            genres = emptyList()
        )
    }

    internal fun buildEntityMediaOrder(
        entityKind: TmdbEntityKind,
        sourceType: String
    ): List<TmdbEntityMediaType> {
        if (entityKind == TmdbEntityKind.NETWORK) {
            return listOf(TmdbEntityMediaType.TV)
        }

        return when (normalizeEntitySourceType(sourceType)) {
            "movie" -> listOf(TmdbEntityMediaType.MOVIE, TmdbEntityMediaType.TV)
            else -> listOf(TmdbEntityMediaType.TV, TmdbEntityMediaType.MOVIE)
        }
    }

    private fun normalizeEntitySourceType(sourceType: String): String {
        return when (sourceType.trim().lowercase(Locale.US)) {
            "movie" -> "movie"
            "tv", "series", "show" -> "tv"
            else -> "tv"
        }
    }

    private fun movieSortBy(railType: TmdbEntityRailType): String = when (railType) {
        TmdbEntityRailType.POPULAR -> "popularity.desc"
        TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
        TmdbEntityRailType.RECENT -> "primary_release_date.desc"
    }

    private fun tvSortBy(railType: TmdbEntityRailType): String = when (railType) {
        TmdbEntityRailType.POPULAR -> "popularity.desc"
        TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
        TmdbEntityRailType.RECENT -> "first_air_date.desc"
    }

    /** The TMDB media path segment for [this]: "tv" for series, "movie" otherwise. */
    private fun ContentType.tmdbType(): String = when (this) {
        ContentType.SERIES, ContentType.TV -> "tv"
        else -> "movie"
    }

    private fun buildShowYearRange(startYear: String, endYear: String?, status: String?): String {
        val isEnded = status != null && status != "Returning Series" && status != "In Production"
        return when {
            isEnded && endYear != null && endYear != startYear -> "$startYear-$endYear"
            isEnded -> startYear
            else -> "$startYear-"
        }
    }

    private fun String?.yearPart(): String? {
        val value = this?.trim()?.takeIf { it.length >= 4 }?.take(4) ?: return null
        return value.takeIf { it.all(Char::isDigit) }
    }

    private fun buildIncludeImageLanguage(normalizedLanguage: String): String =
        "${normalizedLanguage.substringBefore("-")},$normalizedLanguage,en,null"

    private fun normalizeTmdbLanguage(language: String?): String {
        val raw = language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?: return "en"
        // Normalize region code to uppercase (e.g. pt-br -> pt-BR)
        val normalized = raw.split("-").let { parts ->
            if (parts.size == 2) "${parts[0].lowercase(Locale.US)}-${parts[1].uppercase(Locale.US)}"
            else raw.lowercase(Locale.US)
        }
        // Map codes unsupported by TMDB to their closest equivalent
        return when (normalized) {
            "es-419" -> "es-MX"
            else -> normalized
        }
    }

    private fun selectBestLocalizedImagePath(
        images: List<TmdbImage>,
        normalizedLanguage: String
    ): String? {
        if (images.isEmpty()) return null
        val languageCode = normalizedLanguage.substringBefore("-")
        val explicitRegion = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
        val regionCode = explicitRegion
            ?: LANGUAGE_DEFAULT_REGION[languageCode]
            ?: DEFAULT_LANGUAGE_REGIONS[languageCode]
        // Once we have any region (explicit like fr-FR, or inferred for a bare "fr" via
        // the default-region map), prefer the exact region match first, then same-language
        // with no region, then same-language from any other region (cross-region fallback
        // e.g. pt-PT for pt-BR), and only then fall back to English.
        return images
            .sortedWith(
                compareByDescending<TmdbImage> { it.iso6391 == languageCode && it.iso31661 == regionCode }
                    .thenByDescending { it.iso6391 == languageCode && it.iso31661 == null }
                    .thenByDescending { it.iso6391 == languageCode }
                    .thenByDescending { it.iso6391 == "en" }
                    .thenByDescending { it.iso6391 == null }
            )
            .firstOrNull()
            ?.filePath
    }

    companion object {
        private val DEFAULT_LANGUAGE_REGIONS = mapOf(
            "pt" to "PT",
            "es" to "ES"
        )
        private const val ENTITY_RAIL_MAX_ITEMS = 20
        private const val TOP_RATED_VOTE_COUNT_FLOOR = 200
    }

    /** A person and every credited department (29/Q2: no cast-OR-crew pick; the page chooses the first tab). */
    suspend fun fetchPersonDetail(
        personId: Int,
        language: String = "en"
    ): PersonDetail? =
        withContext(ioDispatcher) {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            val cacheKey = "$personId:$normalizedLanguage"
            personCache[cacheKey]?.let { return@withContext it }

            try {
                val (person, credits) = coroutineScope {
                    val personDeferred = async {
                        tmdbApi.getPersonDetails(personId, apiKeys.key(), normalizedLanguage).body()
                    }
                    val creditsDeferred = async {
                        tmdbApi.getPersonCombinedCredits(personId, apiKeys.key(), normalizedLanguage).body()
                    }
                    Pair(personDeferred.await(), creditsDeferred.await())
                }

                if (person == null) return@withContext null

                // If biography is empty and language is not English, fetch English fallback
                val biography = if (person.biography.isNullOrBlank() && normalizedLanguage != "en") {
                    runCatching {
                        tmdbApi.getPersonDetails(personId, apiKeys.key(), "en").body()?.biography
                    }.getOrNullUnlessCancelled()
                } else {
                    person.biography
                }?.takeIf { it.isNotBlank() }

                val allCredits = credits?.cast.orEmpty().map { it.toRawPersonCredit() } +
                    credits?.crew.orEmpty().map { it.toRawPersonCredit() }
                val name = person.name ?: "Unknown"

                val detail = PersonDetail(
                    tmdbId = person.id,
                    name = name,
                    biography = biography,
                    birthday = person.birthday?.takeIf { it.isNotBlank() },
                    deathday = person.deathday?.takeIf { it.isNotBlank() },
                    placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                    profilePhoto = tmdbImageUrl(person.profilePath, "w500"),
                    knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                    alsoKnownAs = person.alsoKnownAs.orEmpty()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && it != name }
                        .distinct(),
                    departments = personDepartments(allCredits),
                    creditsWithoutPoster = allCredits
                        .filter { it.isTitleCredit && it.posterPath == null }
                        .distinctBy { it.mediaType to it.id }
                        .size
                )
                personCache[cacheKey] = detail
                detail
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch person detail: ${e.message}", e)
                null
            }
        }

    /**
     * A person's credits grouped by department (cast = [ACTING_DEPARTMENT]), most credits first:
     * posterless credits dropped, one entry per title (type + id) per department with its roles
     * joined, newest first (upcoming kept, 29/Q5), undated last, then best-rated.
     */
    private fun personDepartments(credits: List<RawPersonCredit>): List<PersonDepartment> =
        credits
            .filter { it.isTitleCredit && it.posterPath != null }
            .groupBy { it.department }
            .map { (department, departmentCredits) ->
                PersonDepartment(
                    name = department,
                    credits = departmentCredits
                        .groupBy { it.mediaType to it.id }
                        .values
                        .mapNotNull(::mergePersonCredits)
                        .sortedWith(
                            compareByDescending<PersonCredit> { it.date.orEmpty() }
                                .thenByDescending { it.preview.tmdbRating ?: 0f }
                        )
                )
            }
            .filter { it.credits.isNotEmpty() }
            .sortedWith(compareByDescending<PersonDepartment> { it.credits.size }.thenBy { it.name })

    /** One title's credits within a department (several characters or jobs) as one [PersonCredit]. */
    private fun mergePersonCredits(sameTitle: List<RawPersonCredit>): PersonCredit? {
        val credit = sameTitle.first()
        val isTv = credit.mediaType == TmdbEntityMediaType.TV.value
        val title = (if (isTv) credit.name ?: credit.title else credit.title ?: credit.name)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val date = (if (isTv) credit.firstAirDate else credit.releaseDate)?.takeIf { it.isNotBlank() }
        return PersonCredit(
            preview = MetaPreview(
                id = "tmdb:${credit.id}",
                type = if (isTv) ContentType.SERIES else ContentType.MOVIE,
                name = title,
                poster = tmdbImageUrl(credit.posterPath, "w500"),
                posterShape = PosterShape.POSTER,
                background = tmdbImageUrl(credit.backdropPath, TmdbImageSizes.backdrop),
                logo = null,
                description = credit.overview?.takeIf { it.isNotBlank() },
                releaseInfo = date?.take(4),
                imdbRating = null,
                tmdbRating = credit.voteAverage?.toFloat(),
                genres = emptyList()
            ),
            roles = sameTitle.mapNotNull { it.role?.trim()?.takeIf(String::isNotEmpty) }.distinct(),
            episodeCount = sameTitle.mapNotNull { it.episodeCount }.maxOrNull(),
            date = date
        )
    }
}

/** TMDB department name given to cast credits. */
internal const val ACTING_DEPARTMENT = "Acting"

/** The fields [TmdbMetadataService] maps from a cast or crew person credit. */
private class RawPersonCredit(
    val id: Int,
    val title: String?,
    val name: String?,
    val mediaType: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val firstAirDate: String?,
    val voteAverage: Double?,
    val overview: String?,
    val department: String,
    /** Cast character or crew job. */
    val role: String?,
    val episodeCount: Int?
) {
    val isTitleCredit: Boolean
        get() = mediaType == TmdbEntityMediaType.MOVIE.value || mediaType == TmdbEntityMediaType.TV.value
}

private fun TmdbPersonCreditCast.toRawPersonCredit() = RawPersonCredit(
    id = id, title = title, name = name, mediaType = mediaType, posterPath = posterPath?.takeIf { it.isNotBlank() },
    backdropPath = backdropPath, releaseDate = releaseDate, firstAirDate = firstAirDate,
    voteAverage = voteAverage, overview = overview, department = ACTING_DEPARTMENT,
    role = character, episodeCount = episodeCount
)

private fun TmdbPersonCreditCrew.toRawPersonCredit() = RawPersonCredit(
    id = id, title = title, name = name, mediaType = mediaType, posterPath = posterPath?.takeIf { it.isNotBlank() },
    backdropPath = backdropPath, releaseDate = releaseDate, firstAirDate = firstAirDate,
    voteAverage = voteAverage, overview = overview,
    department = department?.trim()?.takeIf { it.isNotEmpty() } ?: CREW_DEPARTMENT_FALLBACK,
    role = job, episodeCount = episodeCount
)

/** TMDB's catch-all crew department, used when a crew credit names none. */
private const val CREW_DEPARTMENT_FALLBACK = "Crew"

// Fallback regions for language codes that don't carry a region tag (e.g. "fr"
// instead of "fr-FR"). Without this, non-hyphenated locales fall straight through
// to the US/GB defaults in preferredRegions and users see American ratings.
private val LANGUAGE_DEFAULT_REGION: Map<String, String> = mapOf(
    "ar" to "SA", "bg" to "BG", "bs" to "BA", "cs" to "CZ", "da" to "DK",
    "de" to "DE", "el" to "GR", "es" to "ES", "et" to "EE", "fi" to "FI",
    "fr" to "FR", "he" to "IL", "hi" to "IN", "hr" to "HR", "hu" to "HU",
    "id" to "ID", "it" to "IT", "ja" to "JP", "ko" to "KR", "lt" to "LT",
    "lv" to "LV", "nl" to "NL", "no" to "NO", "pl" to "PL", "pt" to "PT",
    "ro" to "RO", "ru" to "RU", "sk" to "SK", "sl" to "SI", "sr" to "RS",
    "sv" to "SE", "th" to "TH", "tr" to "TR", "uk" to "UA", "vi" to "VN",
    "zh" to "CN"
)

private fun TmdbDetailsResponse.originalTitleOrNull(): String? =
    (originalTitle ?: originalName)?.trim()?.takeIf { it.isNotBlank() }

private fun TmdbDetailsResponse?.productionCompanies(): List<MetaCompany> =
    this?.productionCompanies.orEmpty().mapNotNull { toMetaCompany(it.name, it.logoPath, it.id) }

private fun TmdbDetailsResponse?.networks(): List<MetaCompany> =
    this?.networks.orEmpty().mapNotNull { toMetaCompany(it.name, it.logoPath, it.id) }

private fun toMetaCompany(name: String?, logoPath: String?, id: Int?): MetaCompany? {
    val trimmed = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return MetaCompany(name = trimmed, logo = tmdbImageUrl(logoPath, size = "w300"), tmdbId = id)
}

private fun List<TmdbMovieReleaseDateCountry>.toMetaReleaseDates(): List<MetaReleaseDateCountry> =
    mapNotNull { country ->
        val code = country.iso31661?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        MetaReleaseDateCountry(
            countryCode = code,
            releaseDates = country.releaseDates.orEmpty().map {
                metaReleaseDate(it.certification, it.descriptors, it.iso6391, it.note, it.releaseDate, it.type)
            }
        )
    }

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val languageCode = normalizedLanguage.substringBefore("-").lowercase(Locale.US)
    val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
        ?: LANGUAGE_DEFAULT_REGION[languageCode]
    return buildList {
        if (!fromLanguage.isNullOrBlank()) add(fromLanguage)
        add("US")
        add("GB")
    }.distinct()
}

/**
 * First certified release in the preferred regions (the TMDB language's region, else
 * its default region, then US, GB), else in any region. Spec 26 R2.
 */
private fun selectMovieCertification(
    countries: List<MetaReleaseDateCountry>,
    normalizedLanguage: String
): TmdbCertification? {
    val byRegion = countries.associateBy { it.countryCode.uppercase(Locale.US) }
    val ordered = preferredRegions(normalizedLanguage).mapNotNull { byRegion[it] } + countries
    return ordered.asSequence()
        .flatMap { it.releaseDates.asSequence() }
        .firstNotNullOfOrNull { release ->
            release.certification?.let { TmdbCertification(it, release.descriptors) }
        }
}

private fun selectTvCertification(
    ratings: List<TmdbTvContentRatingItem>,
    normalizedLanguage: String
): TmdbCertification? {
    val byRegion = ratings.associateBy { it.iso31661?.uppercase(Locale.US) }
    val ordered = preferredRegions(normalizedLanguage).mapNotNull { byRegion[it] } + ratings
    return ordered.firstNotNullOfOrNull { item ->
        item.rating?.trim()?.takeIf { it.isNotBlank() }?.let { rating ->
            TmdbCertification(rating, item.descriptors.orEmpty().filter(String::isNotBlank))
        }
    }
}

/** A certification ("PG-13") and its content descriptors ("violence, language"). */
private data class TmdbCertification(val rating: String, val descriptors: List<String>)

private class FetchedCertification(
    val certification: TmdbCertification?,
    val releaseDates: List<MetaReleaseDateCountry>
)

/** See [TmdbMetadataService.fetchDetailFacts]. */
data class TmdbDetailFacts(
    val originalTitle: String?,
    val voteAverage: Double?,
    val voteCount: Int?,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val ageRating: String?,
    val ageRatingDescriptors: List<String>,
    val releaseDates: List<MetaReleaseDateCountry>
)

/**
 * [facts] fill only what the addon meta leaves empty; an addon value always wins.
 * The descriptors travel with the rating they describe.
 */
fun Meta.withTmdbFacts(facts: TmdbDetailFacts?): Meta {
    if (facts == null) return this
    val takeRating = ageRating == null && facts.ageRating != null
    return copy(
        productionCompanies = productionCompanies.ifEmpty { facts.productionCompanies },
        networks = networks.ifEmpty { facts.networks },
        ageRating = if (takeRating) facts.ageRating else ageRating,
        ageRatingDescriptors = if (takeRating) facts.ageRatingDescriptors else ageRatingDescriptors,
        releaseDates = releaseDates.ifEmpty { facts.releaseDates },
        originalTitle = originalTitle ?: facts.originalTitle,
        tmdbRating = tmdbRating ?: facts.voteAverage?.toFloat(),
        tmdbVoteCount = tmdbVoteCount ?: facts.voteCount
    )
}

data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val castMembers: List<MetaCastMember>,
    val releaseInfo: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val director: List<String>,
    val writer: List<String>,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val ageRating: String?,
    val ageRatingDescriptors: List<String> = emptyList(),
    val status: String?,
    val countries: List<String>?,
    val language: String?,
    val originalTitle: String? = null,
    val trailers: List<MetaTrailer> = emptyList()
)

/** A movie's TMDB collection ("franchise"): the id for [TmdbMetadataService.fetchMovieCollection] and its display name. */
data class TmdbCollectionRef(val id: Int, val name: String?)

/** Cache slot for [TmdbMetadataService.fetchCollectionRef]; [ref] null = the movie belongs to no collection. */
private class CachedCollectionRef(val ref: TmdbCollectionRef?)

enum class TmdbEntityKind(val routeValue: String) {
    COMPANY("company"),
    NETWORK("network");

    companion object {
        fun fromRouteValue(value: String): TmdbEntityKind = when (value.trim().lowercase(Locale.US)) {
            "network" -> NETWORK
            else -> COMPANY
        }
    }
}

enum class TmdbEntityMediaType(val value: String) {
    MOVIE("movie"),
    TV("tv")
}

enum class TmdbEntityRailType(val value: String) {
    POPULAR("popular"),
    TOP_RATED("top_rated"),
    RECENT("recent")
}

data class TmdbEntityHeader(
    val id: Int,
    val kind: TmdbEntityKind,
    val name: String,
    val logo: String?,
    val originCountry: String?,
    val secondaryLabel: String?,
    val description: String?
)

data class TmdbEntityRail(
    val mediaType: TmdbEntityMediaType,
    val railType: TmdbEntityRailType,
    val items: List<MetaPreview>,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false
)

data class TmdbEntityBrowseData(
    val header: TmdbEntityHeader,
    val rails: List<TmdbEntityRail>
)

data class TmdbEntityRailPageResult(
    val items: List<MetaPreview>,
    val hasMore: Boolean
)
