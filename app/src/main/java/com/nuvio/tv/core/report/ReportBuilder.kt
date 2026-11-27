package com.nuvio.tv.core.report

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.diagnostics.AbnormalExitKind
import com.nuvio.tv.core.diagnostics.ExitRecord
import com.nuvio.tv.core.diagnostics.LastExitReason
import com.nuvio.tv.core.player.AudioCapabilityReport
import com.nuvio.tv.data.local.DevicePlayerSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Spec 116 D3: gathers the six parts on this TV, then [ReportText] scrubs and trims them. Built
 * once when the panel opens (D2); nothing leaves the TV here. Android reads only: verified on the
 * Shield, not in JVM tests.
 */
class ReportBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretSources: ReportSecretSources,
    private val deviceSettings: DevicePlayerSettingsStore,
) {

    suspend fun build(): ReportFile = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val since = now - ReportStorage.MAX_AGE_MS
        val tv = thisTv()
        val app = app(now)
        val exits = LastExitReason.recentExits(context, since)
        // Each crash file read once: its text for the part, its header line for the summary.
        val crashes = CrashRecorder.records(ReportStorage.dir(context), now).mapNotNull { file ->
            runCatching { file.lastModified() to file.readText().trimEnd('\n') }.getOrNull()
        }
        val errors = PlaybackErrorLog.records(context, now)
        val settings = settingLines(zone)
        val parts = ReportParts(
            thisTv = tv.text,
            app = app.text,
            lastCloses = lastCloses(exits, crashes.map { it.second }, zone),
            playbackErrors = playbackErrors(errors, zone),
            settings = ReportSettingsPart.text(settings),
            appLog = LogcatParser.lastDay(LogcatReader.read(), Instant.ofEpochMilli(now), zone),
        )
        val summary = ReportSummary(
            tv = tv.summary,
            app = app.summary,
            closes = ReportSummary.closes(
                exits.map { CloseSummary(it.kind, it.timestampMs, exitHeader(it, zone)) },
                crashes.map { (time, text) ->
                    // `# crash time=<ms>`: the thread name after it may be scrubbed (an OkHttp thread names its URL).
                    CloseSummary(AbnormalExitKind.CRASH.label, time, text.substringBefore('\n').substringBefore(" thread="))
                },
            ),
            errors = errors.map { ErrorSummary(it.cause, it.timeMs) },
            settings = settings,
        )
        ReportText.build(parts, secretSources.read(), summary)
    }

    private class Part<T>(val text: String, val summary: T)

    /** D3.1: never ANDROID_ID, serial, device name, Wi-Fi name or account. */
    private fun thisTv(): Part<TvSummary> {
        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
        }
        @Suppress("DEPRECATION")
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        val mode = display?.mode
        @Suppress("DEPRECATION")
        val hdr = display?.hdrCapabilities?.supportedHdrTypes?.map(::hdrName).orEmpty()
        AudioCapabilityReport.capture(context)
        val ramMb = memory.totalMem / (1024 * 1024)
        val dolbyVision = "DolbyVision" in hdr
        val text = listOf(
            "manufacturer = ${Build.MANUFACTURER}",
            "model = ${Build.MODEL}",
            "device = ${Build.DEVICE}",
            "android = ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "ram_mb = $ramMb",
            "abis = ${Build.SUPPORTED_ABIS.joinToString(",")}",
            "display = ${mode?.let { "${it.physicalWidth}x${it.physicalHeight} @ ${"%.3f".format(it.refreshRate)}" } ?: "unknown"}",
            "hdr = ${hdr.joinToString(",").ifEmpty { "none" }}",
            "dolby_vision = $dolbyVision",
            "audio = ${AudioCapabilityReport.latest?.text?.replace('\n', ' ') ?: "unavailable"}",
        ).joinToString("\n")
        return Part(text, TvSummary(Build.MODEL, Build.VERSION.RELEASE, ramMb, dolbyVision))
    }

    /** D3.2. Build kind: public (no built-in TMDB key), household, or debug. */
    private fun app(now: Long): Part<AppSummary> {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val kind = when {
            BuildConfig.IS_DEBUG_BUILD -> "debug"
            BuildConfig.TMDB_API_KEY.isBlank() -> "public"
            else -> "household"
        }
        val installDays = info?.let { (now - it.firstInstallTime) / (24L * 60 * 60 * 1000) }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val text = listOf(
            "version = ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "build = $kind",
            "abi = $abi",
            "installed_days = ${installDays ?: "unknown"}",
        ).joinToString("\n")
        return Part(text, AppSummary(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, kind, abi))
    }

    /** D3.3: Android's exit records (API 30+, newest first) and this app's own crash files. */
    private fun lastCloses(exits: List<ExitRecord>, crashTexts: List<String>, zone: ZoneId): String = buildString {
        for (exit in exits) {
            append(exitHeader(exit, zone))
            if (exit.description.isNotEmpty()) append(' ').append(exit.description)
            append('\n')
            exit.anrTrace?.let { append(it.trimEnd('\n')).append('\n') }
        }
        for (text in crashTexts) append(text).append('\n')
    }.trimEnd('\n').ifEmpty { ReportSummary.NONE }

    /** An exit record's first line in Last closes; its description, when any, follows after a space. */
    private fun exitHeader(exit: ExitRecord, zone: ZoneId): String = "# exit ${exit.kind} ${time(exit.timestampMs, zone)}"

    /** D3.4. */
    private fun playbackErrors(records: List<PlaybackErrorRecord>, zone: ZoneId): String =
        records.joinToString("\n") { r ->
            listOfNotNull(
                time(r.timeMs, zone),
                "cause=${r.cause}",
                r.httpCode?.let { "http=$it" },
                r.playerError?.let { "player_error=$it" },
                r.engine?.let { "engine=$it" },
                r.host?.let { "host=$it" },
                r.addonName?.let { "addon=$it" },
                r.fileName?.let { "file=$it" },
                r.sizeBytes?.let { "size_bytes=$it" },
                r.measuredMbps?.let { "measured_mbps=$it" },
                r.neededMbps?.let { "needed_mbps=$it" },
            ).joinToString(" ")
        }.ifEmpty { ReportSummary.NONE }

    /** D3.5: all of B3's keys are this TV's (Spec 33 D1), read raw from the device store. */
    private suspend fun settingLines(zone: ZoneId): List<SettingLine> {
        val stored = deviceSettings.store.data.first().asMap().mapKeys { it.key.name }
        return ReportSettingsPart.lines(stored, zone)
    }

    private fun time(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().withNano(0).toString()

    private fun hdrName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DolbyVision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "type$type"
    }
}
