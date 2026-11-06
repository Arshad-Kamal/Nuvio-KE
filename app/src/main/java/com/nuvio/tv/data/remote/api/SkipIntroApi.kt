package com.nuvio.tv.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

// --- IntroDB API ---

interface IntroDbApi {
    @GET("segments")
    suspend fun getSegments(
        @Query("imdb_id") imdbId: String,
        @Query("season") season: Int,
        @Query("episode") episode: Int
    ): Response<IntroDbSegmentsResponse>
}

@Serializable
data class IntroDbSegmentsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("intro") val intro: IntroDbSegment? = null,
    @SerialName("recap") val recap: IntroDbSegment? = null,
    @SerialName("outro") val outro: IntroDbSegment? = null
)

@Serializable
data class IntroDbSegment(
    @SerialName("start_sec") val startSec: Double? = null,
    @SerialName("end_sec") val endSec: Double? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("submission_count") val submissionCount: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
