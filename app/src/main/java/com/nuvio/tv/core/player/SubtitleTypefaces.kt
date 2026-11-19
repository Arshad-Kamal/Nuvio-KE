package com.nuvio.tv.core.player

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File

private const val TAG = "SubtitleTypeface"

/**
 * Spec 135 (issue #2): letters outside Latin-1 that a Vietnamese subtitle needs (Latin Extended-A,
 * -B and Extended Additional). One string per character: [Paint.hasGlyph] asks a multi-character
 * string for a single ligature glyph.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal val SUBTITLE_COVERAGE_PROBES: List<String> = listOf("đ", "ơ", "ư", "ộ", "ế", "ả")

/**
 * The typeface ExoPlayer text subtitles use: [system] when it (with the device's fallback chain)
 * covers every probe, otherwise the bundled face from [loadBundled], or [system] if that fails.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun <T : Any> chooseSubtitleTypeface(
    system: T,
    covers: (T, String) -> Boolean,
    loadBundled: () -> T?
): T = if (SUBTITLE_COVERAGE_PROBES.all { covers(system, it) }) system else loadBundled() ?: system

/**
 * Spec 135 F1: ExoPlayer draws text subtitles with Typeface.DEFAULT[_BOLD] and relies on the
 * device's font fallback; a device whose fonts cover only Latin-1 draws Vietnamese letters as
 * boxes. There the Spec 98 libass fallback fonts (Open Sans subset: Latin incl. Vietnamese, Greek,
 * Cyrillic) are used instead; a typeface from a file keeps the system fallback for other scripts.
 * Healthy devices keep the system typeface. Decided once per process per weight.
 */
internal object SubtitleTypefaces {
    @Volatile private var regular: Typeface? = null
    @Volatile private var bold: Typeface? = null

    fun forWeight(context: Context, isBold: Boolean): Typeface {
        (if (isBold) bold else regular)?.let { return it }
        val system = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val paint = Paint()
        val chosen = chooseSubtitleTypeface(
            system = system,
            covers = { typeface, text -> paint.apply { this.typeface = typeface }.hasGlyph(text) },
            loadBundled = { loadBundledSubtitleTypeface(context.applicationContext, isBold) }
        )
        if (chosen !== system) {
            Log.i(TAG, "System font lacks Vietnamese; subtitles use the bundled face (bold=$isBold)")
        }
        if (isBold) bold = chosen else regular = chosen
        return chosen
    }
}

/**
 * Copies the bundled font to `noBackupFilesDir/subtitle-fonts/` ([writeIfChanged]) and loads it.
 * Null on any failure, including createFromFile's silent Typeface.DEFAULT for an unreadable file.
 */
private fun loadBundledSubtitleTypeface(context: Context, isBold: Boolean): Typeface? = try {
    val (name, resId) = if (isBold) LIBASS_FALLBACK_BOLD else LIBASS_FALLBACK_REGULAR
    val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
    val file = File(File(context.noBackupFilesDir, "subtitle-fonts"), name)
    writeIfChanged(file, bytes)
    Typeface.createFromFile(file).takeUnless { it == Typeface.DEFAULT }
} catch (e: Exception) {
    Log.w(TAG, "Bundled subtitle font unavailable; keeping the system typeface", e)
    null
}

/**
 * Makes [file] hold exactly [bytes]: left alone when it already does, otherwise written to a
 * sibling temp file and renamed over it, so a reader never sees a partial font. Callers run on the
 * main thread (applySubtitleLook), so the shared temp name is not raced.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun writeIfChanged(file: File, bytes: ByteArray) {
    if (file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) return
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeBytes(bytes)
    check(tmp.renameTo(file)) { "rename to $file failed" }
}
