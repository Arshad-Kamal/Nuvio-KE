package com.nuvio.tv.domain.model

import com.nuvio.tv.core.stream.TrashReleaseGroups

/**
 * Release-fact ladders and exclusions used to parse and rank addon streams
 * (source badges, auto-play QUALITY_RANK). Not user-editable: ranking always
 * uses these shipped defaults.
 */
data class ReleaseRankPreferences(
    val preferredResolutions: List<ReleaseResolution> = ReleaseResolution.defaultOrder,
    val excludedResolutions: List<ReleaseResolution> = emptyList(),
    val preferredQualities: List<ReleaseQuality> = ReleaseQuality.defaultOrder,
    val excludedQualities: List<ReleaseQuality> = listOf(
        ReleaseQuality.CAM,
        ReleaseQuality.TS,
        ReleaseQuality.TC,
        ReleaseQuality.SCR
    ),
    val preferredVisualTags: List<ReleaseVisualTag> = ReleaseVisualTag.defaultOrder,
    val excludedVisualTags: List<ReleaseVisualTag> = listOf(
        ReleaseVisualTag.THREE_D,
        ReleaseVisualTag.H_OU,
        ReleaseVisualTag.H_SBS,
        ReleaseVisualTag.AI
    ),
    val preferredAudioTags: List<ReleaseAudioTag> = ReleaseAudioTag.defaultOrder,
    val excludedAudioTags: List<ReleaseAudioTag> = emptyList(),
    val preferredAudioChannels: List<ReleaseAudioChannel> = ReleaseAudioChannel.defaultOrder,
    val excludedAudioChannels: List<ReleaseAudioChannel> = emptyList(),
    val preferredEncodes: List<ReleaseEncode> = ReleaseEncode.defaultOrder,
    val excludedEncodes: List<ReleaseEncode> = listOf(ReleaseEncode.AV1),
    val preferredReleaseGroups: List<String> = TrashReleaseGroups.PREFERRED_LADDER,
    val excludedReleaseGroups: List<String> = TrashReleaseGroups.EXCLUDED_GROUPS
) {
    companion object {
        /** The one ranking configuration; preferences are no longer user-editable. */
        val DEFAULT = ReleaseRankPreferences()
    }
}

enum class ReleaseResolution(val label: String) {
    P2160("2160p"),
    P1440("1440p"),
    P1080("1080p"),
    P720("720p"),
    P576("576p"),
    P480("480p"),
    P360("360p"),
    UNKNOWN("Unknown");

    companion object {
        val defaultOrder = listOf(P2160, P1440, P1080, P720, P576, P480, P360, UNKNOWN)
    }
}

enum class ReleaseQuality(val label: String) {
    BLURAY_REMUX("BluRay REMUX"),
    BLURAY("BluRay"),
    WEB_DL("WEB-DL"),
    WEBRIP("WEBRip"),
    HDRIP("HDRip"),
    HD_RIP("HC HD-Rip"),
    DVDRIP("DVDRip"),
    HDTV("HDTV"),
    CAM("CAM"),
    TS("TS"),
    TC("TC"),
    SCR("SCR"),
    UNKNOWN("Unknown");

    companion object {
        val defaultOrder = listOf(BLURAY_REMUX, BLURAY, WEB_DL, WEBRIP, HDRIP, HD_RIP, DVDRIP, HDTV, CAM, TS, TC, SCR, UNKNOWN)
    }
}

enum class ReleaseVisualTag(val label: String) {
    HDR_DV("HDR+DV"),
    DV_ONLY("DV Only"),
    HDR_ONLY("HDR Only"),
    HDR10_PLUS("HDR10+"),
    HDR10("HDR10"),
    DV("DV"),
    HDR("HDR"),
    HLG("HLG"),
    TEN_BIT("10bit"),
    THREE_D("3D"),
    IMAX("IMAX"),
    AI("AI"),
    SDR("SDR"),
    H_OU("H-OU"),
    H_SBS("H-SBS"),
    UNKNOWN("Unknown");

    companion object {
        val defaultOrder = listOf(HDR_DV, DV_ONLY, HDR_ONLY, HDR10_PLUS, HDR10, DV, HDR, HLG, TEN_BIT, IMAX, SDR, THREE_D, AI, H_OU, H_SBS, UNKNOWN)
    }
}

enum class ReleaseAudioTag(val label: String) {
    ATMOS("Atmos"),
    DD_PLUS("DD+"),
    DD("DD"),
    DTS_X("DTS:X"),
    DTS_HD_MA("DTS-HD MA"),
    DTS_HD("DTS-HD"),
    DTS_ES("DTS-ES"),
    DTS("DTS"),
    TRUEHD("TrueHD"),
    OPUS("OPUS"),
    FLAC("FLAC"),
    AAC("AAC"),
    UNKNOWN("Unknown");

    companion object {
        val defaultOrder = listOf(TRUEHD, DTS_HD_MA, ATMOS, DTS_X, FLAC, DTS_HD, DTS_ES, DD_PLUS, DTS, DD, OPUS, AAC, UNKNOWN)
    }
}

enum class ReleaseAudioChannel(val label: String) {
    CH_2_0("2.0"),
    CH_5_1("5.1"),
    CH_6_1("6.1"),
    CH_7_1("7.1"),
    UNKNOWN("Unknown");

    companion object {
        val defaultOrder = listOf(CH_7_1, CH_6_1, CH_5_1, CH_2_0, UNKNOWN)
    }
}

enum class ReleaseEncode(val label: String) {
    AV1("AV1"),
    HEVC("HEVC"),
    AVC("AVC"),
    XVID("XviD"),
    DIVX("DivX"),
    UNKNOWN("Unknown");

    companion object {
        val defaultOrder = listOf(HEVC, AVC, AV1, XVID, DIVX, UNKNOWN)
    }
}
