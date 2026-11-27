package com.nuvio.tv.core.backup

import java.util.Locale

/** A backup profile matched to a TV profile (Spec 101 R1); its data restores into [tvId]. */
data class ProfileMatch(
    val backupId: Int,
    val tvId: Int,
    /**
     * The two sides differ in `usesPrimaryAddons`: the addon store and the addon-owned layout keys are
     * skipped and listed, so the TV keeps its addon set-up (R1).
     */
    val addonsDiffer: Boolean,
)

/** A backup profile that restores nowhere, listed before the user confirms (R1, D27). */
data class SkippedProfile(val backupId: Int, val name: String, val reason: Reason) {
    enum class Reason {
        /** No profile on this TV has its name. */
        NOT_ON_TV,

        /** Its name is empty, or occurs twice in the backup or on this TV. */
        NAME_NOT_UNIQUE,
    }
}

data class ProfileMatching(val matches: List<ProfileMatch>, val skipped: List<SkippedProfile>) {

    companion object {
        /**
         * R1, D28: the backup's primary (id 1) takes the TV's primary; every other backup profile takes the
         * remaining TV profile whose [normalized] name equals its own. A name that is empty, or occurs twice
         * among either side's remaining profiles, matches nothing. One-to-one by construction: a name
         * matches only when it is unique on both sides.
         */
        fun of(backup: List<BackupProfile>, tv: List<BackupProfile>): ProfileMatching {
            val matches = ArrayList<ProfileMatch>()
            val skipped = ArrayList<SkippedProfile>()
            // The primary always uses its own addons (AddonPreferences ignores the flag for profile 1).
            matches += ProfileMatch(1, 1, addonsDiffer = false)
            val backupRest = backup.filter { it.id != 1 }
            val tvByName = tv.filter { it.id != 1 }.groupBy { normalized(it.name) }
            val backupNameCounts = backupRest.groupingBy { normalized(it.name) }.eachCount()
            for (profile in backupRest) {
                val name = normalized(profile.name)
                val candidates = tvByName[name].orEmpty()
                when {
                    name.isEmpty() || backupNameCounts.getValue(name) > 1 || candidates.size > 1 ->
                        skipped += SkippedProfile(profile.id, profile.name, SkippedProfile.Reason.NAME_NOT_UNIQUE)
                    candidates.isEmpty() -> skipped += SkippedProfile(profile.id, profile.name, SkippedProfile.Reason.NOT_ON_TV)
                    else -> {
                        val target = candidates.single()
                        matches += ProfileMatch(profile.id, target.id, profile.usesPrimaryAddons != target.usesPrimaryAddons)
                    }
                }
            }
            return ProfileMatching(matches, skipped)
        }

        /** D28: "ignoring capitals and extra spaces". */
        fun normalized(name: String): String =
            name.trim().split(WHITESPACE).joinToString(" ").lowercase(Locale.ROOT)

        private val WHITESPACE = Regex("\\s+")
    }
}
