package com.nuvio.tv.core.backup

import com.nuvio.tv.data.local.MyPhotoKind

/** Which part of a backup a store belongs to (R3). Secret groups travel only sealed (D2) or in a copy. */
enum class BackupGroup(val secret: Boolean) {
    /** Per-profile data, restored into the matched profile (R1, R4). */
    PROFILE(false),
    /** Per-profile passwords and keys: need the backup PIN. */
    PROFILE_SECRET(true),
    /** "This TV" settings, unticked by default on restore (D5). */
    DEVICE(false),
    /** TV-wide passwords and keys: need the backup PIN. */
    TV_SECRET(true),
}

enum class StoreKind {
    /** A `ProfileDataStoreFactory` DataStore: `<id>` for profile 1, `<id>_p<n>` for the others. */
    PROFILE_DATASTORE,
    /** A standalone DataStore: `files/datastore/<id>.preferences_pb`, or `files/<id>.preferences_pb` when the id holds a folder. */
    DATASTORE,
    /** `shared_prefs/<id>.xml`. */
    SHARED_PREFS,
    /** A JSON file at `files/<id>`. */
    TEXT_FILE,
    /** The My photos folder `files/<id>/`; only names passing [BackupCatalog.isPhotoName]. */
    PHOTOS,
}

data class CatalogStore(
    val id: String,
    val group: BackupGroup,
    val kind: StoreKind,
    /** Restores while signed in too (D29): data the account does not sync. */
    val tvOnly: Boolean = false,
) {
    val perProfile: Boolean get() = kind == StoreKind.PROFILE_DATASTORE
    val isPrefs: Boolean get() = kind == StoreKind.PROFILE_DATASTORE || kind == StoreKind.DATASTORE || kind == StoreKind.SHARED_PREFS

    /** R4, D29: signed in, a per-profile store restores only when the account does not sync it. */
    fun restores(signedOut: Boolean): Boolean = signedOut || tvOnly || group != BackupGroup.PROFILE

    /**
     * A DataStore's file under `files/`: `ProfileDataStoreFactory.get`'s name rule for per-profile stores
     * ([profileId] required), the delegate's rule for standalone ones.
     */
    fun dataStorePath(profileId: Int? = null): String = when (kind) {
        StoreKind.PROFILE_DATASTORE -> profileDataStorePath(id, requireNotNull(profileId) { "per-profile store needs a profile" })
        StoreKind.DATASTORE -> if ('/' in id) "$id.preferences_pb" else "datastore/$id.preferences_pb"
        StoreKind.SHARED_PREFS, StoreKind.TEXT_FILE, StoreKind.PHOTOS -> throw IllegalStateException("$id is not a DataStore")
    }
}

/** `ProfileDataStoreFactory.get`'s file name rule: `<feature>` for profile 1, `<feature>_p<id>` otherwise. */
internal fun profileDataStorePath(feature: String, profileId: Int): String =
    "datastore/${if (profileId == 1) feature else "${feature}_p$profileId"}.preferences_pb"

/**
 * Every store and file of the app, assigned to a [BackupGroup] or excluded with a reason (Spec 101 R3).
 * A backup holds catalog stores only (R7). `BackupCatalogGuardTest` scans `src/main` and fails on a
 * store neither listed here nor in [excluded]: a new store must pick its side.
 */
object BackupCatalog {
    const val MY_PHOTOS = "my_photos"
    const val ADDON_STORE = "addon_preferences"
    const val LAYOUT_STORE = "layout_settings"

    private fun profile(id: String, tvOnly: Boolean = false) =
        CatalogStore(id, BackupGroup.PROFILE, StoreKind.PROFILE_DATASTORE, tvOnly)

    private fun device(id: String, kind: StoreKind = StoreKind.DATASTORE) =
        CatalogStore(id, BackupGroup.DEVICE, kind)

    val stores: List<CatalogStore> = listOf(
        // Settings, per profile (synced).
        profile("theme_settings"),
        profile(LAYOUT_STORE),
        profile("player_settings"),
        profile("stream_badge_settings"),
        profile("trailer_settings"),
        profile("tmdb_settings"),
        profile("trakt_settings"),
        profile("track_preference"),
        // Addons, library, history.
        profile(ADDON_STORE),
        profile("library_preferences"),
        profile("collections"),
        profile("watch_progress_preferences"),
        profile("watch_progress_recent_preferences"),
        profile("watch_progress_archive_preferences"),
        profile("watched_items_preferences"),
        profile("watch_count"),
        profile("search_history"),
        // This TV's own per-profile items (not synced; restore signed in too, D29).
        profile("saved_stream_filters", tvOnly = true),
        profile("preferred_source", tvOnly = true),
        profile("audio_delay_route_preference", tvOnly = true),
        profile("live_tv", tvOnly = true),
        profile("profile_look", tvOnly = true),
        profile("profile_rating_cap", tvOnly = true),
        profile("profile_content_filters", tvOnly = true),
        // Spec 100: Picked for you switch, picks cache and detail ratings (not synced).
        profile("scout", tvOnly = true),
        // Spec 105 S4: MDBList ratings switch and sources (the key is the tracker's, below).
        profile("mdblist_ratings", tvOnly = true),
        CatalogStore(MY_PHOTOS, BackupGroup.PROFILE, StoreKind.PHOTOS, tvOnly = true),
        // Per-profile secrets.
        CatalogStore("tracking_accounts", BackupGroup.PROFILE_SECRET, StoreKind.PROFILE_DATASTORE),
        CatalogStore("rating_posters", BackupGroup.PROFILE_SECRET, StoreKind.PROFILE_DATASTORE),
        // This TV.
        device("device_player_settings"),
        device("device_local_player_prefs"),
        device("ui_scale_prefs"),
        device("episode_options_prefs"),
        device("seek_thumbnails"),
        device("torrent_settings"),
        device("update_settings"),
        device("network_privacy/private_dns"),
        device("app_locale", StoreKind.SHARED_PREFS),
        device("livetv/hidden.json", StoreKind.TEXT_FILE),
        device("livetv/epg-overrides.json", StoreKind.TEXT_FILE),
        // TV-wide secrets.
        CatalogStore("tmdb_api_key", BackupGroup.TV_SECRET, StoreKind.DATASTORE),
        CatalogStore("livetv/sources.json", BackupGroup.TV_SECRET, StoreKind.TEXT_FILE),
    )

    private val byId: Map<String, CatalogStore> = stores.associateBy { it.id }

    fun store(id: String): CatalogStore? = byId[id]

    /**
     * Keys never exported, and kept from the target when its store is replaced (R4): sync
     * bookkeeping, device-learned values, update-check state. Key names are unique across stores.
     * `migration_*` flags are not here: they travel with their values (R2).
     */
    val excludedKeys: Set<String> = setOf(
        // Sync bookkeeping (library_preferences, watch progress metadata, watched_items_preferences).
        "library_delta_cursor",
        "library_delta_initialized",
        "library_pending_upserts",
        "library_pending_deletes",
        "library_mutation_revision",
        "last_successful_push_ms",
        "watch_progress_delta_cursor",
        "watch_progress_delta_initialized",
        "last_successful_watched_push_ms",
        "watched_items_delta_cursor",
        "watched_items_delta_initialized",
        // Device-learned (player_settings, device_player_settings).
        "audio_rejections_seen",
        "audio_rejections_confirmed",
        "service_network_tables",
        "device_setup_record",
        "assessment_revert_snapshot",
        "last_playback_diagnostics_json",
        // Spec 132 (733a0020 Q2): the subtitle font choice names a font file on this TV; restored
        // elsewhere it would only show the missing line.
        "subtitle_font_id",
        "subtitle_font_name",
        // Update-check state (update_settings keeps only auto_check and test_releases).
        "last_ok_check_ms",
        "last_attempt_ms",
        "known_release",
        "pulsed_version_code",
        "seen_version_code",
        "seen_version_name",
        "tip_links",
        "tip_attempt_ms",
    )

    /** Stores and files a backup never holds, by the name or `files/` path the guard finds, with why. */
    val excluded: Map<String, String> = mapOf(
        "backup_restore" to "the backup feature's own work files (temp exports, pre-restore copies)",
        "profile_settings" to "profile list, picker and active profile: a restore never writes the profile list (R1)",
        "profile_lock_state" to "profile locks and the D-pad combo: device-local (D2)",
        "server_configuration" to "which account server: account state",
        "startup_sync_state" to "sync bookkeeping",
        "nuvio_sync_client_identity" to "sync bookkeeping",
        "app_onboarding" to "onboarding state",
        "auth_session_notice_store" to "sign-in notice state",
        "debrid_settings" to "legacy store, migrated away",
        "external_playback_pending" to "transient playback hand-off",
        "nightly_self_trim" to "device runtime bookkeeping",
        "stream_link_cache" to "cache (tokenised URLs)",
        "binge_group_cache" to "cache",
        "watched_series_cache" to "derived cache, deleted for restored profiles (R4)",
        "addon_health" to "cache",
        "addon_manifest_cache" to "cache",
        "member_catalogs" to "cache",
        "failed_releases" to "cache",
        "cw_enrichment" to "derived cache, deleted for restored profiles (R4)",
        "catalog_rows_v1.json" to "cache (cacheDir)",
        "afr_fps_cache_v1.txt" to "cache",
        "content_filter_title_tags.json" to "cache (Spec 117 title tags, rebuilt from TMDB)",
        "cacert.pem" to "player TLS bundle, written by the app",
        "user-subtitle-fonts" to "the TV's own subtitle fonts (Spec 132 D3/733a0020: files stay on this TV)",
        "fonts" to "mpv's copy of the chosen subtitle font (Spec 132), rewritten at each mpv start",
        "torrserver" to "TorrServer runtime folder",
        "cs_extensions" to "legacy folder, deleted by LegacyFeatureDataCleanup",
        "report/playback-errors.json" to "diagnostics",
        "addon_tls/addon_cert_pins" to "device trust (certificate pins)",
        "network_privacy/access_headers.bin" to "Keystore-bound, not portable (D2)",
        "simkl_app_id" to "this TV's Simkl app id (Spec 106 D9: a new device sets it up again; any change signs every profile out)",
        "simkl_sync" to "Simkl snapshots (cache, pulled again) and pending marks (tied to this TV's Simkl grant, cleared on any change)",
        "mdblist_sync" to "MDBList snapshots (Spec 105 S2a: cache, pulled again; cleared on any key change) and the TVDB map cache " +
            "tvdb_maps.json (S6: app-wide, fetched again; kept across key changes) and the scores cache ratings.json (S4: app-wide, 12 h)",
        "livetv/channels.json" to "cache",
        "livetv/epg-*.json" to "cache",
        "livetv/health.json" to "cache",
        "livetv/iptv-meta.json" to "cache",
        "livetv/pack-channels.json" to "cache",
        "livetv/pack-scan.json" to "cache",
        "livetv/accounts.json" to "Xtream logins (Spec 109 Q3): kept off backups; a restored Xtream source asks for Edit login",
    )

    /**
     * Layout keys that belong to the addon set-up (`LayoutPreferenceDataStore`): skipped with the
     * addon store when a matched pair's `usesPrimaryAddons` differs (R1). Same set as the private
     * `catalogKeysExcludedFromProfileSettingsBlob` in ProfileSettingsSyncService; the guard test ties
     * each name to its key in LayoutPreferenceDataStore.
     */
    val addonOwnedLayoutKeys: Set<String> = setOf(
        "home_catalog_order_keys",
        "disabled_home_catalog_keys",
        "custom_catalog_titles",
    )

    /**
     * A My photos file name: `<kind prefix>-<millis>-<uuid>.jpg`, a copy of `MyPhotosStore`'s own
     * (private) pattern narrowed to the known kinds.
     */
    private val photoNamePattern: Regex = Regex(
        "(?:" + MyPhotoKind.entries.joinToString("|") { Regex.escape(it.prefix) } + ")" +
            """-(\d{1,19})-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jpg"""
    )

    /** True for a name the gallery lists: the pattern, and millis that fit a Long (`MyPhotosStore.parse`). */
    fun isPhotoName(name: String): Boolean =
        photoNamePattern.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull() != null
}
