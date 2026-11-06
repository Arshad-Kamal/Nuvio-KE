package com.nuvio.tv.domain.model

/**
 * TMDB settings still read in-app since the TMDB enrichment cut. The retired
 * enrichment toggles stay on disk and in the synced "tmdb_settings" blob with no
 * reader (see ProfileSettingsSyncService.profileSettingsSyncedFeatures).
 */
data class TmdbSettings(
    // Master TMDB switch. At runtime it gates only the detail-page More Like This
    // and Collection rows and the detail facts fill (Spec 26e), so the Integrations pane dims
    // just those two rows while it is off. TrailerService reads useTrailers and the language
    // (like the collection sources, cast and release regions) independently of this flag.
    val enabled: Boolean = false,
    // TMDB language preference (ISO-639-1, default English)
    val language: String = "en",
    // Trailers (TMDB->YouTube trailer lookup in TrailerService, TMDB fallback meta); off unless
    // stored, matching TmdbSettingsDataStore's read default.
    val useTrailers: Boolean = false,
    // Recommendations (more like this)
    val useMoreLikeThis: Boolean = true,
    // Collections
    val useCollections: Boolean = true
)
