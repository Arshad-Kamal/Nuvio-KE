package com.nuvio.tv.core.util

private const val MANIFEST_SUFFIX = "/manifest.json"
private const val STREMIO_SCHEME = "stremio://"

/**
 * The scheme rule of the TV-side addon install, one home for it and the
 * web-config boundary (sanitizeProposedAddonUrls): `stremio://` is rewritten
 * to `https://`; the result is returned only when it starts with `http://` or
 * `https://`, else null. Prefix matches are case-sensitive and [url] is not
 * trimmed, so callers trim first when their input needs it.
 */
internal fun withAcceptedAddonScheme(url: String): String? {
    val rewritten = if (url.startsWith(STREMIO_SCHEME)) "https://" + url.removePrefix(STREMIO_SCHEME) else url
    return rewritten.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

/**
 * Canonical form of an addon URL, used as the identity key across install
 * state, enable/disable state, ordering, user-set names, and remote sync.
 *
 * Main F23: this logic previously existed as three private copies
 * (AddonRepositoryImpl, AddonPreferences, AddonSyncService). They were
 * diffed before unification and found behaviourally identical, so this is
 * a pure extraction - any future change to canonicalisation must happen
 * here and nowhere else, since these keys cross the preferences/repository/
 * sync boundary.
 *
 * Rules: trim whitespace and trailing slashes; strip a trailing
 * `/manifest.json` (case-insensitive) from the *path* while preserving any
 * query string (configurable addons carry their config in the query).
 * Case of the remainder is preserved - callers needing case-insensitive
 * matching use [addonUrlMatchKey].
 */
fun canonicalizeAddonUrl(url: String): String {
    val trimmed = url.trim()
    // Split path/query FIRST (2026-08-26 review): a '/' inside a query value
    // (e.g. …?config=x89+/) is data and must survive byte-exact. The old code
    // trimmed trailing slashes off the whole URL, corrupting such keys.
    val queryStart = trimmed.indexOf('?')
    val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
    val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
    // Path still gets the full old treatment — trim, then suffix strip, then trim —
    // so `host/manifest.json` and `host/manifest.json/` keep the same identity key.
    val pathNoSlash = path.trimEnd('/')
    val cleanPath = if (pathNoSlash.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
        pathNoSlash.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
    } else {
        pathNoSlash
    }
    return cleanPath + query
}

/**
 * Case-insensitive match key for "is this the same addon": [canonicalizeAddonUrl],
 * lowercased. Comparison only; never stored or requested.
 */
fun addonUrlMatchKey(url: String): String = canonicalizeAddonUrl(url).lowercase()
