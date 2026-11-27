package com.nuvio.tv.core.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException

/**
 * The counts the Back up panel shows (Spec 101 S7, mockup b2, b4). A count is null when one of its stores
 * could not be read; the export itself decides whether the backup can be made.
 */
data class BackupSummary(
    val profiles: List<BackupProfile>,
    /**
     * Distinct addon links, as stored, of the profiles that keep their own addons (the primary always
     * does); null when one of them has no stored list (the app's defaults).
     */
    val addons: Int?,
    /** Saved titles across every profile. */
    val library: Int?,
    /** Watched entries (titles and episodes) across every profile. */
    val history: Int?,
) {
    internal companion object {
        private const val ADDON_URLS_ORDERED = "installed_addon_urls_ordered"
        private const val ADDON_URLS_LEGACY = "installed_addon_urls"
        private const val LIBRARY_ITEMS = "library_items"
        private const val WATCHED_ITEMS = "watched_items"
        private val urlList = ListSerializer(String.serializer())

        suspend fun of(profiles: List<BackupProfile>, snapshot: BackupSnapshot): BackupSummary {
            /** [values] of [store] for each of [ids], gathered; null when one cannot be read or yields null. */
            suspend fun gather(store: String, ids: List<Int>, values: (Map<String, BackupValue>) -> Collection<String>?): List<String>? =
                try {
                    ids.flatMap { id -> snapshot.values(store, id)?.let(values) ?: return null }
                } catch (_: IOException) {
                    null
                } catch (_: BackupCheckException) {
                    null
                }
            val ids = profiles.map { it.id }
            val ownAddons = profiles.filter { it.id == 1 || !it.usesPrimaryAddons }.map { it.id }
            return BackupSummary(
                profiles = profiles,
                addons = gather(BackupCatalog.ADDON_STORE, ownAddons, ::addonUrls)?.toSet()?.size,
                library = gather("library_preferences", ids) { it.strings(LIBRARY_ITEMS) }?.size,
                history = gather("watched_items_preferences", ids) { it.strings(WATCHED_ITEMS) }?.size,
            )
        }

        private fun Map<String, BackupValue>.strings(key: String): Collection<String> =
            (this[key] as? BackupValue.StrSet)?.value.orEmpty()

        /**
         * AddonPreferences' stored list as stored (the ordered JSON list, else the legacy set); null when
         * neither is set, as the app then shows its default addons, which this count does not know.
         */
        private fun addonUrls(values: Map<String, BackupValue>): Collection<String>? {
            val ordered = (values[ADDON_URLS_ORDERED] as? BackupValue.Str)?.value
            if (ordered != null) {
                try {
                    return backupJson.decodeFromString(urlList, ordered)
                } catch (_: IllegalArgumentException) {
                    // SerializationException included: a list the app could not read either.
                }
            }
            return (values[ADDON_URLS_LEGACY] as? BackupValue.StrSet)?.value
        }
    }
}
