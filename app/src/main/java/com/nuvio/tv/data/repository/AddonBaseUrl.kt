package com.nuvio.tv.data.repository

/**
 * Splits an addon base URL into its path (trailing slashes trimmed) and its query
 * ("?…" or ""), for resource URLs built as "$path/<resource>…$query" and for the
 * meta cache keys. One home for the manifest, meta, stream, subtitle and catalog
 * repositories.
 *
 * Splits at the first '?' before trimming, as canonicalizeAddonUrl does: a '/' that
 * ends the query (e.g. "?config=x89+/") is addon config data and passes through
 * byte-exact (L8-19). Catalog URLs built from this are disk-cache keys, so a change
 * to its output changes those keys.
 */
internal fun splitAddonBaseUrl(baseUrl: String): Pair<String, String> {
    val queryStart = baseUrl.indexOf('?')
    val basePath = (if (queryStart >= 0) baseUrl.substring(0, queryStart) else baseUrl).trimEnd('/')
    val baseQuery = if (queryStart >= 0) baseUrl.substring(queryStart) else ""
    return basePath to baseQuery
}
