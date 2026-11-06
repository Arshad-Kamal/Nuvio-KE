package com.nuvio.tv.core.tmdb

/** The key unit tests pass to the TMDB services; a stub matching it proves the call site sends the source's key. */
internal const val TEST_TMDB_KEY = "test-key"

internal val TEST_TMDB_KEYS = TmdbApiKeySource { TEST_TMDB_KEY }
