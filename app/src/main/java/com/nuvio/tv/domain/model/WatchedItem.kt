package com.nuvio.tv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WatchedItem(
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAt: Long,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val trackingProviderId: String? = null,
    val trackingProviderItemId: String? = null,
    val trackingSourceUrl: String? = null
)
