package com.nuvio.tv.core.player

import android.os.Build

/**
 * The one home for device-identity facts that capability APIs misreport or that decide
 * a code path by brand (Spec 33 D2). Pure: built from Build strings, so tests pass any
 * identity. Each table entry names its source; add a row only with one.
 *
 * Jellyfin = jellyfin/jellyfin-androidtv
 * app/src/main/java/org/jellyfin/androidtv/util/profile/KnownDefects.kt (master, read 2026-10-18).
 */
data class DeviceQuirks(val manufacturer: String?, val model: String?) {

    /**
     * NVIDIA reports manufacturer "NVIDIA" and model "SHIELD Android TV"; the 2015 / 2017 /
     * 2019 boxes share the model string, so a SHIELD model from NVIDIA counts.
     */
    val isShield: Boolean
        get() = manufacturer.equals("NVIDIA", ignoreCase = true) &&
            model?.contains("SHIELD", ignoreCase = true) == true

    // Brand flags the DV base-layer policy branches on (DolbyVisionBaseLayerPolicy).
    val isAmazon: Boolean get() = manufacturer.equals("Amazon", ignoreCase = true)
    val isSamsung: Boolean get() = manufacturer.equals("Samsung", ignoreCase = true)
    val isXiaomi: Boolean get() = manufacturer.equals("Xiaomi", ignoreCase = true)

    /** HEVC Dolby Vision / HDR10+ playback bug (Jellyfin modelsWithDoViHdr10PlusBug). */
    val dvHdr10PlusPlaybackDefect: Boolean
        get() = model in DV_HDR10PLUS_DEFECT_MODELS

    /**
     * DV Profile 7 decodes but the decoder list does not advertise it (Jellyfin
     * modelsWithUnreportedDoviProfile7Support, exact model match as there). Not yet consumed:
     * the Diagnostics "DV7 Decoder" row on our Shield settles it before the DV policy relies on it.
     */
    val unreportedDv7Decoder: Boolean
        get() = model == "SHIELD Android TV"

    companion object {
        /** Fire TV 4K Max 2nd / 1st gen, Fire TV 4K 2nd / 1st gen (Jellyfin). */
        val DV_HDR10PLUS_DEFECT_MODELS: Set<String> = setOf("AFTKRT", "AFTKA", "AFTKM", "AFTMM")

        fun current(): DeviceQuirks = DeviceQuirks(Build.MANUFACTURER, Build.MODEL)
    }
}
