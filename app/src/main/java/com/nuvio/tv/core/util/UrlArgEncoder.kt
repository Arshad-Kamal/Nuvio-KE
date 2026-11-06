package com.nuvio.tv.core.util

import java.net.URLEncoder

/**
 * Percent-encodes a value for interpolation into a navigation route path segment
 * or query argument (G6 consolidation, 2026-08-30).
 *
 * This logic previously existed as eleven identical private copies: seven `encode`
 * members in ui/navigation/Screen.kt and private helpers in SubtitleRepositoryImpl
 * (encodePathSegment), MetaRepositoryImpl (encodePathSegment), StreamRepositoryImpl
 * (encodePathSegment), and CatalogRepositoryImpl (encodeArg). All were diffed before
 * unification and found byte-identical, so this is a pure extraction — change the
 * encoding here and nowhere else.
 *
 * `URLEncoder.encode` is form-encoded: it emits '+' for spaces, which is wrong for
 * path/query positions, so spaces are rewritten to %20. All other encodings it
 * produces are valid percent-encodings.
 */
fun encodeUrlArg(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
