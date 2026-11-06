package com.nuvio.tv.data.remote.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Disk cache for downloaded supporter assets (avatars, profile backgrounds): one directory,
 * files named "<id>-v<version>.<extension>". Remote ids become file names, so they are reduced
 * to safe path tokens (CWE-22 hygiene: the backend can be a self-hosted server).
 */
internal class MemberAssetCache(private val directory: File) {

    fun fileFor(id: String, version: Int, extension: String): File =
        directory.resolve("${id.toSafePathToken()}-v$version.$extension")

    /** The cached file for this exact version, or null when absent or empty. */
    fun cachedFile(id: String, version: Int, extension: String): File? =
        fileFor(id, version, extension).takeIf(::isUsable)

    /** The cached file, downloading it first (tmp write + rename) when missing. */
    suspend fun getOrDownload(
        id: String,
        version: Int,
        extension: String,
        download: suspend () -> ByteArray
    ): File = withContext(Dispatchers.IO) {
        val file = fileFor(id, version, extension)
        if (isUsable(file)) return@withContext file
        directory.mkdirs()
        val bytes = download()
        val temporaryFile = directory.resolve(".${file.name}.tmp")
        temporaryFile.writeBytes(bytes)
        if (!temporaryFile.renameTo(file)) {
            temporaryFile.copyTo(file, overwrite = true)
            temporaryFile.delete()
        }
        file
    }

    /** Highest-version cached file for [id] with its version; [extension] null = any. */
    fun latest(id: String, extension: String? = null): Pair<File, Int>? {
        val prefix = "${id.toSafePathToken()}-v"
        return directory.listFiles()
            ?.filter { file ->
                isUsable(file) && file.name.startsWith(prefix) &&
                    (extension == null || file.name.endsWith(".$extension"))
            }
            ?.mapNotNull { file ->
                file.name.removePrefix(prefix).substringBefore('.').toIntOrNull()?.let { file to it }
            }
            ?.maxByOrNull { (_, version) -> version }
    }

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0L
}

/** Keeps only [A-Za-z0-9_.-] (no separators), capped at 64 chars; never empty. */
internal fun String.toSafePathToken(): String =
    filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        .take(64)
        .ifEmpty { "x" }
