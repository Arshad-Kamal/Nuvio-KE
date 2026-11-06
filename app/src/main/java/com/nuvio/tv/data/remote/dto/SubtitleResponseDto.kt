package com.nuvio.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubtitleResponseDto(
    @SerialName("subtitles") val subtitles: List<SubtitleItemDto>? = null
)

@Serializable
data class SubtitleItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("lang") val lang: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("headers") val headers: Map<String, String>? = null,
    // OpenSubtitles v3 release fields (Spec 26/26l smoke): shown on the Subtitles panel row.
    @SerialName("movieReleaseName") val movieReleaseName: String? = null,
    @SerialName("subtitleFileName") val subtitleFileName: String? = null,
    @SerialName("releaseGroup") val releaseGroup: String? = null
)
