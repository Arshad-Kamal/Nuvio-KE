package com.nuvio.tv.domain.model

/** URL used by shimmer/skeleton placeholder cards before real catalog data arrives. */
const val PLACEHOLDER_IMAGE_URL = "placeholder://empty"

/** Returns true if this URL is a placeholder (not a real image). */
fun String?.isPlaceholder(): Boolean = this == PLACEHOLDER_IMAGE_URL

/** Id prefix of placeholder catalog items (lazy and skeleton rows before real data arrives). */
const val PLACEHOLDER_ITEM_ID_PREFIX = "__placeholder_"

/** True for a placeholder catalog item id (see [PLACEHOLDER_ITEM_ID_PREFIX]). */
fun String?.isPlaceholderItemId(): Boolean = this?.startsWith(PLACEHOLDER_ITEM_ID_PREFIX) == true
