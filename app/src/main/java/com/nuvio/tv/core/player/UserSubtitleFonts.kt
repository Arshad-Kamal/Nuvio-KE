package com.nuvio.tv.core.player

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.nuvio.tv.data.local.UserSubtitleFontStore
import com.nuvio.tv.data.local.loadUserSubtitleTypeface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "UserSubtitleFonts"

/**
 * Spec 132 S5, ExoPlayer text subtitles and the Settings preview: the chosen user font
 * ([fontId], per profile) over Spec 135's pick. Loaded once per file (cached by path, size and
 * modified time, a failed load too); bold is Android's synthetic bold of it. Letters the font lacks
 * come from the system fallback, as for any typeface from a file. Null (Spec 135's pick applies)
 * for no choice, a file that is gone, or one Android can't load. Main thread.
 */
internal object UserSubtitleTypefaces {
    private val cache = UserFontCache(::loadUserSubtitleTypeface)

    fun forStyle(context: Context, fontId: String?, isBold: Boolean): Typeface? {
        val base = cache.get(fontId?.let { UserSubtitleFontStore.fileOf(context.filesDir, it) }) ?: return null
        return if (isBold) Typeface.create(base, Typeface.BOLD) else base
    }
}

/**
 * One loaded font: [get] loads [file] once per (path, size, modified time) and remembers a failed
 * load too; null for no file. Single-entry (one choice per profile is drawn at a time). Main thread.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal class UserFontCache<T : Any>(private val load: (File) -> T?) {
    private data class Key(val path: String, val length: Long, val modified: Long)

    private var cached: Pair<Key, T?>? = null

    fun get(file: File?): T? {
        if (file == null) return null
        val key = Key(file.path, file.length(), file.lastModified())
        cached?.takeIf { it.first == key }?.let { return it.second }
        return load(file).also {
            if (it == null) Log.w(TAG, "A chosen subtitle font did not load; using the default")
            cached = key to it
        }
    }
}

/**
 * Spec 132 S5, mpv: libmpv reads `~~/fonts` ([fontsDir]) once, when a core starts (Spec 98), so the
 * chosen font applies from the next video (D2). Leaves exactly [chosen] there among this feature's
 * copies (named as in the store, `font-...`; other files are left alone), copying it (temp file +
 * rename) unless a copy of the same size is already in place. Returns the family for `sub-font`,
 * or null (Roboto) with no choice or a failed copy.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun syncMpvUserSubtitleFont(fontsDir: File, chosen: File?, family: String?): String? {
    val keep = chosen?.takeIf { !family.isNullOrBlank() }?.let { File(fontsDir, it.name) }
    fontsDir.listFiles { file -> file.name.startsWith(COPY_PREFIX) && file != keep }?.forEach { it.delete() }
    if (chosen == null || keep == null) return null
    if (keep.isFile && keep.length() == chosen.length()) return family
    // libass would load a leftover temp file as a font too: it never outlives a failed copy.
    val tmp = File(fontsDir, "${keep.name}.tmp")
    return try {
        fontsDir.mkdirs()
        chosen.inputStream().use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!tmp.renameTo(keep)) throw IOException("rename failed")
        family
    } catch (e: IOException) {
        tmp.delete()
        Log.w(TAG, "Chosen subtitle font not copied for mpv; using Roboto", e)
        null
    }
}

/** The store's file-name prefix; only such files in `~~/fonts` are this feature's. */
private const val COPY_PREFIX = "${UserSubtitleFontStore.PREFIX}-"
