package com.nuvio.tv.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface ParentalGuideApi {

    @GET("titles/{imdbId}/parentsGuide")
    suspend fun getParentsGuide(
        @Path("imdbId") imdbId: String
    ): Response<ImdbApiParentsGuideResponse>
}

@Serializable
data class ImdbApiParentsGuideResponse(
    @SerialName("parentsGuide") val parentsGuide: List<ImdbApiParentsGuideCategory>? = null
)

@Serializable
data class ImdbApiParentsGuideCategory(
    @SerialName("category") val category: String,
    @SerialName("severityBreakdowns") val severityBreakdowns: List<ImdbApiSeverityBreakdown>? = null,
    @SerialName("reviews") val reviews: List<ImdbApiParentsGuideReview>? = null
)

@Serializable
data class ImdbApiSeverityBreakdown(
    @SerialName("severityLevel") val severityLevel: String,
    @SerialName("voteCount") val voteCount: Int
)

@Serializable
data class ImdbApiParentsGuideReview(
    @SerialName("text") val text: String? = null,
    @SerialName("isSpoiler") val isSpoiler: Boolean? = null
)
