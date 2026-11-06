package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.player.AfrMediaProbe.detectFrameRateFromLocalFile
import com.nuvio.tv.core.player.AfrMediaProbe.extractEmbeddedResolveUrl
import com.nuvio.tv.core.player.AfrMediaProbe.isLiveStreamUrl
import com.nuvio.tv.core.player.AfrMediaProbe.isMkvSource
import com.nuvio.tv.core.player.AfrMediaProbe.isMp4Source
import com.nuvio.tv.core.player.AfrMediaProbe.isResolveProxyUrl
import com.nuvio.tv.core.player.AfrMediaProbe.parseUriScheme
import com.nuvio.tv.core.player.FrameRateUtils.FrameRateDetection
import com.nuvio.tv.core.util.safeHost

/**
 * AFR OkHttp range probing: CDN warmup and MP4/MKV head/tail sparse downloads, handed to
 * [AfrMediaProbe]'s local-file probe. Split out of [FrameRateUtils] (DRDYK L3b-57, move-only).
 */
object AfrHttpProbe {

    // Same tag as FrameRateUtils: `logcat -s FrameRateUtils` keeps every AFR line.
    private const val TAG = "FrameRateUtils"

    private val probeHttpClient by lazy {
        com.nuvio.tv.core.player.PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(14, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Tiny Range used only to pay cold debrid/CDN TTFB before the real head/tail probe. */
    internal const val AFR_CDN_WARMUP_MAX_BYTES = 262_144L // 256 KiB

    // Coroutine cancellation cannot interrupt blocking OkHttp calls; the probe chain polls this
    // between stages and inside download loops so a cancelled preflight stops downloading.
    internal val NEVER_CANCELLED: () -> Boolean = { false }

    /**
     * Issues a small `bytes=0-(N-1)` GET so TLS + debrid origin spin-up happen on a cheap
     * request. The following 4 MB head / moov tail then reuse the warmed connection pool.
     * Failures are ignored — warmup is best-effort.
     */
    internal fun warmHttpOriginForAfrProbe(
        url: String,
        headers: Map<String, String>,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): Boolean {
        val scheme = parseUriScheme(url)
        if (scheme != "http" && scheme != "https") return false
        if (isCancelled()) return false

        val requestBuilder = probeRequestBuilder(url, headers)
            .header("Range", "bytes=0-${AFR_CDN_WARMUP_MAX_BYTES - 1}")

        val call = probeHttpClient.newCall(requestBuilder.build())
        return try {
            call.execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    Log.d(TAG, "AFR CDN warmup skipped (HTTP ${response.code})")
                    return false
                }
                val body = response.body
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (total < AFR_CDN_WARMUP_MAX_BYTES) {
                        if (isCancelled()) {
                            call.cancel()
                            return false
                        }
                        val read = input.read(
                            buffer,
                            0,
                            minOf(buffer.size.toLong(), AFR_CDN_WARMUP_MAX_BYTES - total).toInt()
                        )
                        if (read <= 0) break
                        total += read
                    }
                    val warmed = total > 0L
                    if (warmed) {
                        Log.d(TAG, "AFR CDN warmup drained ${total}B (rangeSatisfied=${response.code == 206})")
                    }
                    warmed
                }
            }
        } catch (e: Exception) {
            call.cancel()
            Log.d(TAG, "AFR CDN warmup failed: ${e.message}")
            false
        }
    }

    internal fun parseContentRangeTotalLength(contentRangeHeader: String?): Long? {
        if (contentRangeHeader.isNullOrBlank()) return null
        val totalStr = contentRangeHeader.substringAfter('/', missingDelimiterValue = "").trim()
        if (totalStr == "*") return null
        return totalStr.toLongOrNull()?.takeIf { it > 0L }
    }


    internal data class HttpRangeFetchResult(
        val success: Boolean,
        val totalContentLength: Long? = null,
        /** True only when the server honored Range with HTTP 206. */
        val rangeSatisfied: Boolean = false
    )

    internal fun patchMdatHeaderForCompactMp4(targetFile: java.io.File, moovOffset: Long) {
        if (!targetFile.exists() || targetFile.length() < 32) return
        runCatching {
            java.io.RandomAccessFile(targetFile, "rw").use { raf ->
                var pos = 0L
                val fileLen = raf.length()
                while (pos + 8 <= fileLen && pos < moovOffset) {
                    raf.seek(pos)
                    val boxSize32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = ByteArray(4)
                    raf.readFully(type)
                    val typeStr = String(type, Charsets.US_ASCII)

                    val is64Bit = boxSize32 == 1L
                    val actualBoxSize = if (is64Bit) {
                        raf.readLong()
                    } else if (boxSize32 == 0L) {
                        fileLen - pos
                    } else {
                        boxSize32
                    }

                    if (typeStr == "mdat") {
                        val newMdatSize = (moovOffset - pos).coerceAtLeast(8L)
                        raf.seek(pos)
                        if (is64Bit) {
                            raf.writeInt(1)
                            raf.write("mdat".toByteArray(Charsets.US_ASCII))
                            raf.writeLong(newMdatSize)
                        } else {
                            raf.writeInt(newMdatSize.toInt())
                        }
                        Log.d(TAG, "Patched mdat box size to $newMdatSize for compact MP4 probe at pos $pos")
                        break
                    }

                    if (actualBoxSize <= 0) break
                    pos += actualBoxSize
                }
            }
        }
    }

    // Box types a valid MP4/MOV/fMP4 stream can start with: ftyp/styp (ISO BMFF / DASH),
    // moov/moof/sidx (header or segment first), free/skip/wide/pnot (QuickTime padding),
    // mdat (raw QuickTime with moov at the end), uuid (camera vendor boxes).
    private val MP4_LEADING_BOX_TYPES = setOf(
        "ftyp", "styp", "moov", "moof", "sidx", "free", "skip", "wide", "mdat", "pnot", "uuid"
    )

    internal fun hasFtypAtom(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 8) return false
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(8)
                raf.readFully(bytes)
                val size32 = ((bytes[0].toLong() and 0xFF) shl 24) or
                    ((bytes[1].toLong() and 0xFF) shl 16) or
                    ((bytes[2].toLong() and 0xFF) shl 8) or
                    (bytes[3].toLong() and 0xFF)
                // 0 = box extends to EOF, 1 = 64-bit size follows, otherwise header-inclusive size.
                val sizePlausible = size32 == 0L || size32 == 1L || size32 >= 8L
                val type = String(bytes, 4, 4, java.nio.charset.StandardCharsets.US_ASCII)
                sizePlausible && type in MP4_LEADING_BOX_TYPES
            }
        }.getOrDefault(false)
    }

    /** EBML / Matroska magic at file start (`1A 45 DF A3`) — used for extensionless MKV detection. */
    internal fun hasEbmlHeader(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(4)
                raf.readFully(bytes)
                bytes[0] == 0x1A.toByte() &&
                    bytes[1] == 0x45.toByte() &&
                    bytes[2] == 0xDF.toByte() &&
                    bytes[3] == 0xA3.toByte()
            }
        }.getOrDefault(false)
    }

    internal fun hasMoovAtom(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 8) return false
        val fileLength = file.length()
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                // Check head (first 4 MB)
                val headLen = fileLength.coerceAtMost(4_194_304L).toInt()
                val headBytes = ByteArray(headLen)
                raf.seek(0L)
                raf.readFully(headBytes)
                for (i in 0 until headBytes.size - 3) {
                    if (headBytes[i] == 0x6d.toByte() &&
                        headBytes[i + 1] == 0x6f.toByte() &&
                        headBytes[i + 2] == 0x6f.toByte() &&
                        headBytes[i + 3] == 0x76.toByte()
                    ) {
                        return@use true
                    }
                }

                // Check tail (last 32 MB) if file is larger than 4 MB
                if (fileLength > 4_194_304L) {
                    val tailSize = (fileLength - 4_194_304L).coerceAtMost(33_554_432L).toInt()
                    val tailBytes = ByteArray(tailSize)
                    raf.seek(fileLength - tailSize)
                    raf.readFully(tailBytes)
                    for (i in 0 until tailBytes.size - 3) {
                        if (tailBytes[i] == 0x6d.toByte() &&
                            tailBytes[i + 1] == 0x6f.toByte() &&
                            tailBytes[i + 2] == 0x6f.toByte() &&
                            tailBytes[i + 3] == 0x76.toByte()
                        ) {
                            return@use true
                        }
                    }
                }
                false
            }
        }.getOrDefault(false)
    }

    fun detectFrameRateWithOkHttpProbe(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        filename: String? = null,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): FrameRateDetection? {
        val scheme = parseUriScheme(sourceUrl)
        if (scheme != "http" && scheme != "https") return null
        if (isLiveStreamUrl(sourceUrl)) return null

        val targetUrl = if (isResolveProxyUrl(sourceUrl)) {
            extractEmbeddedResolveUrl(sourceUrl)?.takeIf { parseUriScheme(it) == "http" || parseUriScheme(it) == "https" } ?: sourceUrl
        } else {
            sourceUrl
        }

        val tempFile = try {
            java.io.File.createTempFile("afr_probe_", ".tmp", context.cacheDir)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create temp file for AFR probe: ${e.message}")
            return null
        }

        try {
            // Pay cold debrid/CDN TTFB on a tiny Range so the real 4 MB head + moov/Tracks tail
            // run on a warmed origin/connection instead of racing the preflight budget.
            warmHttpOriginForAfrProbe(targetUrl, headers, isCancelled)

            // Pass 1: Head Range Probe (first 4 MB - bytes=0-4194303)
            val headMaxBytes = 4_194_304L + 65_536L
            val headResult = fetchHttpRangeToFile(
                targetUrl, headers, "bytes=0-4194303", headMaxBytes, tempFile, fileOffset = 0L,
                isCancelled = isCancelled
            )
            if (isCancelled()) return null
            val isMp4Detected = isMp4Source(targetUrl, mimeType, filename) || hasFtypAtom(tempFile)
            val isMkvDetected = isMkvSource(targetUrl, mimeType, filename) || hasEbmlHeader(tempFile)

            if (headResult.success && tempFile.length() > 0) {
                // For MP4/MKV files where moov/header is in the first 4MB, probe immediately.
                // If moov is not in the first 4MB (Legacy MP4), skip local probing to save ~4.5s wasted parsing stall.
                //
                // Pass 1 uses a truncated head range only — never run MediaExtractor timestamp
                // sampling here. Sampling ~350 frames on an incomplete MKV/MP4 can hang for the
                // full OkHttp preflight budget and starve NextLib/extractor fallbacks (MPV total
                // AFR await is only ~18s). Declared KEY_FRAME_RATE / NextLib on the head chunk is enough.
                if (isMkvDetected) {
                    val mkvDetection = probeMkvFromHeadAndSparseTracks(
                        context = context,
                        targetUrl = targetUrl,
                        headers = headers,
                        tempFile = tempFile,
                        rangeSatisfied = headResult.rangeSatisfied,
                        isCancelled = isCancelled
                    )
                    if (mkvDetection != null) return mkvDetection
                    Log.d(TAG, "MKV OkHttp probe exhausted; aborting for full-URL NextLib/extractor fallback")
                    return null
                }

                if (!isMp4Detected || hasMoovAtom(tempFile)) {
                    val detection = detectFrameRateFromLocalFile(
                        context = context,
                        file = tempFile,
                        allowTimestampSampling = false
                    )
                    if (detection != null) {
                        Log.d(TAG, "OkHttp AFR probe Pass 1 (head 4MB) succeeded: FPS=${detection.snapped}")
                        return detection
                    }
                } else {
                    Log.d(TAG, "Pass 1 head 4MB does not contain 'moov' atom; proceeding directly to Pass 2 tail probe")
                }
            }

            // Pass 2: Sparse File Head (ftyp) + Tail (moov) for Legacy MP4/MOV
            // Writing tail at fileOffset=tailStart via RandomAccessFile preserves exact MP4 atom offsets (stco/co64) without breaking FFmpeg container parser.
            if (isMp4Detected) {
                if (!headResult.rangeSatisfied) {
                    Log.w(TAG, "CDN did not satisfy Range (no HTTP 206); skipping MP4 tail probe")
                    return null
                }
                if (isCancelled()) return null
                val contentLength = headResult.totalContentLength
                    ?: fetchContentLength(targetUrl, headers, isCancelled)
                if (contentLength > 4_194_304L) {
                    val nextBoxOffset = resolveMp4TailStartAfterMdat(
                        targetUrl = targetUrl,
                        headers = headers,
                        tempFile = tempFile,
                        contentLength = contentLength,
                        isCancelled = isCancelled
                    )
                    if (nextBoxOffset == null || nextBoxOffset >= contentLength) {
                        Log.w(TAG, "MP4 box parsing could not resolve mdat end, aborting probe")
                        return null
                    }
                    val tailStart = nextBoxOffset

                    val tailSizeBytes = contentLength - tailStart
                    // Apply a safety limit (e.g. max 128 MB) to prevent excessive network usage
                    val safeTailSizeBytes = tailSizeBytes.coerceAtMost(134_217_728L)
                    val tailRange = "bytes=$tailStart-${tailStart + safeTailSizeBytes - 1}"
                    val tailMaxBytes = safeTailSizeBytes + 65_536L

                    Log.d(TAG, "OkHttp AFR probe Pass 2 fetching range $tailRange (size: $safeTailSizeBytes bytes)")

                    // Strategy A: Sparse offset file (Head at 0, Tail at original tailStart)
                    // Preserves exact co64/stco sample chunk offsets for Android Stagefright native MPEG4Extractor.
                    // On ext4/f2fs, sparse file takes only 14 MB physical disk space despite 23GB+ reported file length.
                    val usableSpace = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L)
                    if (usableSpace > 50_000_000L) { // Requires only 50 MB usable disk space for 14 MB sparse blocks
                        val tailResultSparse = fetchHttpRangeToFile(
                            targetUrl, headers, tailRange, tailMaxBytes, tempFile, fileOffset = tailStart,
                            isCancelled = isCancelled
                        )
                        if (tailResultSparse.success && tailResultSparse.rangeSatisfied && tempFile.length() > 0) {
                            val detection = detectFrameRateFromLocalFile(context, tempFile)
                            if (detection != null) {
                                Log.d(TAG, "OkHttp AFR probe Pass 2 (sparse head+tail file) succeeded for MP4: FPS=${detection.snapped}")
                                return detection
                            }
                        }
                    }

                    // Strategy B: Compact file fallback (Head 4MB + Tail concatenated)
                    if (isCancelled()) return null
                    tempFile.delete()
                    val headResultCompact = fetchHttpRangeToFile(
                        targetUrl, headers, "bytes=0-4194303", headMaxBytes, tempFile, fileOffset = 0L,
                        isCancelled = isCancelled
                    )
                    if (headResultCompact.success && headResultCompact.rangeSatisfied) {
                        val compactOffset = tempFile.length()
                        val tailResultCompact = fetchHttpRangeToFile(
                            targetUrl, headers, tailRange, tailMaxBytes, tempFile, fileOffset = compactOffset,
                            isCancelled = isCancelled
                        )
                        if (tailResultCompact.success && tailResultCompact.rangeSatisfied && tempFile.length() > 0) {
                            patchMdatHeaderForCompactMp4(tempFile, compactOffset)
                            val detection = detectFrameRateFromLocalFile(context, tempFile)
                            if (detection != null) {
                                Log.d(TAG, "OkHttp AFR probe Pass 2 (compact head+tail file) succeeded for MP4: FPS=${detection.snapped}")
                                return detection
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "OkHttp AFR probe failed: ${e.message}")
        } finally {
            runCatching { tempFile.delete() }
        }
        return null
    }

    /**
     * MKV OkHttp path:
     * 1) Truncate torn EBML at the last complete Segment child, probe locally (no sample loop)
     * 2) If Tracks is missing/incomplete and CDN supports Range, SeekHead-sparse-fetch Tracks
     */
    internal fun probeMkvFromHeadAndSparseTracks(
        context: Context,
        targetUrl: String,
        headers: Map<String, String>,
        tempFile: java.io.File,
        rangeSatisfied: Boolean,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): FrameRateDetection? {
        val layout = MatroskaAfrProbe.analyzeHead(tempFile)
        if (layout != null) {
            val before = tempFile.length()
            val truncated = MatroskaAfrProbe.truncateToSafePrefix(tempFile, layout)
            if (truncated != null && truncated < before) {
                Log.d(TAG, "MKV safe EBML truncate: $before -> $truncated bytes")
            }
            // Attachment-heavy releases push the first Cluster tens of MB in, far past the head
            // window, and a Cluster-less head makes demuxers discard the Tracks they just parsed.
            if (truncated != null && layout.tracksCompleteInPrefix && !layout.clusterInPrefix) {
                val patched = MatroskaAfrProbe.appendStubClusterForHeadProbe(
                    file = tempFile,
                    layout = layout,
                    contentEnd = truncated
                )
                if (patched != null) {
                    Log.d(TAG, "MKV head has no Cluster; appended stub Cluster (length=$patched)")
                } else {
                    Log.w(TAG, "MKV head has no Cluster and stub Cluster patch failed")
                }
            }
        }

        detectFrameRateFromLocalFile(
            context = context,
            file = tempFile,
            allowTimestampSampling = allowMkvLocalTimestampSampling(tempFile)
        )?.let { detection ->
            Log.d(TAG, "OkHttp AFR MKV Pass 1 (safe head) succeeded: FPS=${detection.snapped}")
            return detection
        }

        if (!rangeSatisfied) {
            Log.w(TAG, "CDN did not satisfy Range (no HTTP 206); skipping MKV Tracks sparse probe")
            return null
        }

        val tracksOffset = layout?.tracksAbsoluteOffset
        if (tracksOffset == null || tracksOffset < 0L) {
            Log.d(TAG, "MKV SeekHead did not resolve Tracks offset; skipping sparse probe")
            return null
        }

        // Tracks already fully in the safe prefix but NextLib still failed — sparse won't help
        // (likely missing DefaultDuration). Fall through to full-URL fallbacks.
        if (layout.tracksCompleteInPrefix) {
            Log.d(TAG, "MKV Tracks already complete in head but FPS not found; skipping sparse probe")
            return null
        }

        val usableSpace = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L)
        if (usableSpace < 50_000_000L) {
            Log.w(TAG, "Insufficient cache space for MKV sparse Tracks probe")
            return null
        }

        // Peek Tracks header to learn exact element size, then fetch the full element (capped).
        if (isCancelled()) return null
        val peekBytes = 64L
        val peekFile = java.io.File(tempFile.parentFile, "afr_mkv_tracks_peek_${tempFile.name}")
        try {
            val peekResult = fetchHttpRangeToFile(
                url = targetUrl,
                headers = headers,
                rangeHeader = "bytes=$tracksOffset-${tracksOffset + peekBytes - 1}",
                maxBytes = peekBytes,
                targetFile = peekFile,
                fileOffset = 0L,
                isCancelled = isCancelled
            )
            if (!peekResult.success || !peekResult.rangeSatisfied || peekFile.length() < 4L) {
                Log.w(TAG, "MKV Tracks header peek failed")
                return null
            }
            val peekBuf = peekFile.readBytes()
            val tracksHeader = MatroskaAfrProbe.readElementFromBufferStart(peekBuf, tracksOffset)
            if (tracksHeader == null || tracksHeader.id != MatroskaAfrProbe.ID_TRACKS || tracksHeader.unknownSize) {
                Log.w(TAG, "MKV Tracks peek did not yield a sized Tracks element")
                return null
            }
            val tracksTotalSize = tracksHeader.headerSize + tracksHeader.dataSize
            if (tracksTotalSize <= 0L || tracksTotalSize > MatroskaAfrProbe.MAX_TRACKS_FETCH_BYTES) {
                Log.w(TAG, "MKV Tracks element size $tracksTotalSize outside probe limits")
                return null
            }

            // Keep the safe prefix at 0, then append Tracks contiguously at EOF. Writing at the
            // remote absolute offset creates a sparse hole that breaks Matroska demuxers.
            if (layout.safePrefixLength > 0L && tempFile.length() > layout.safePrefixLength) {
                MatroskaAfrProbe.truncateToSafePrefix(tempFile, layout)
            }
            val tracksWriteOffset = tempFile.length()

            val tracksRange = "bytes=$tracksOffset-${tracksOffset + tracksTotalSize - 1}"
            Log.d(TAG, "OkHttp AFR MKV Pass 2 fetching Tracks range $tracksRange (append at $tracksWriteOffset)")
            val tracksResult = fetchHttpRangeToFile(
                url = targetUrl,
                headers = headers,
                rangeHeader = tracksRange,
                maxBytes = tracksTotalSize + 4_096L,
                targetFile = tempFile,
                fileOffset = tracksWriteOffset,
                isCancelled = isCancelled
            )
            if (!tracksResult.success || !tracksResult.rangeSatisfied) {
                Log.w(TAG, "MKV Tracks sparse fetch failed")
                return null
            }

            // Same rule as Pass 1: a Cluster-less file makes demuxers discard the Tracks they
            // just parsed, so terminate the sparse file with a stub Cluster + patched Segment size.
            if (!layout.clusterInPrefix) {
                val patched = MatroskaAfrProbe.appendStubClusterForHeadProbe(
                    file = tempFile,
                    layout = layout,
                    contentEnd = tempFile.length()
                )
                if (patched != null) {
                    Log.d(TAG, "MKV sparse Tracks file has no Cluster; appended stub Cluster (length=$patched)")
                } else {
                    Log.w(TAG, "MKV sparse Tracks stub Cluster patch failed")
                }
            }

            detectFrameRateFromLocalFile(
                context = context,
                file = tempFile,
                allowTimestampSampling = allowMkvLocalTimestampSampling(tempFile)
            )?.let { detection ->
                Log.d(TAG, "OkHttp AFR MKV Pass 2 (sparse Tracks) succeeded: FPS=${detection.snapped}")
                return detection
            }
        } finally {
            runCatching { peekFile.delete() }
        }
        return null
    }

    /**
     * Result of walking top-level MP4 boxes looking for the end of 'mdat'.
     * [NeedHeaderAt] means the next box header lies past the bytes currently present in the
     * local probe file (common when a large 'free'/'uuid' precedes 'mdat' and exceeds the
     * 4 MB head window) — caller should Range-peek ~64 bytes at [offset] and resume.
     */
    internal sealed class Mp4MdatWalkResult {
        data class Found(val mdatEnd: Long) : Mp4MdatWalkResult()
        data class NeedHeaderAt(val offset: Long) : Mp4MdatWalkResult()
        data object Failed : Mp4MdatWalkResult()
    }

    /**
     * Resolves the byte offset where the box after 'mdat' begins (typically 'moov').
     * Continues past non-mdat boxes whose payload is not in the local head by header-only skip,
     * peeking remote headers when needed.
     */
    private fun resolveMp4TailStartAfterMdat(
        targetUrl: String,
        headers: Map<String, String>,
        tempFile: java.io.File,
        contentLength: Long,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): Long? {
        var startPos = 0L
        var lastPeekAt = -1L
        repeat(12) {
            if (isCancelled()) return null
            when (val walk = walkMp4BoxesForMdatEnd(tempFile, contentLength, startPos)) {
                is Mp4MdatWalkResult.Found -> {
                    Log.d(TAG, "MP4 mdat end resolved at ${walk.mdatEnd}")
                    return walk.mdatEnd
                }
                is Mp4MdatWalkResult.Failed -> {
                    Log.w(TAG, "MP4 box walk failed to resolve mdat end")
                    return null
                }
                is Mp4MdatWalkResult.NeedHeaderAt -> {
                    val peekAt = walk.offset
                    if (peekAt < 0L || peekAt >= contentLength) return null
                    // Same offset after a successful peek means the server returned fewer bytes
                    // than the box header needs; re-requesting would repeat identically.
                    if (peekAt == lastPeekAt) {
                        Log.w(TAG, "MP4 header peek at $peekAt made no progress; aborting")
                        return null
                    }
                    lastPeekAt = peekAt
                    val peekLen = 64L
                    val peekEnd = minOf(peekAt + peekLen - 1L, contentLength - 1L)
                    Log.d(TAG, "MP4 box walk needs header peek at $peekAt")
                    val peekResult = fetchHttpRangeToFile(
                        url = targetUrl,
                        headers = headers,
                        rangeHeader = "bytes=$peekAt-$peekEnd",
                        maxBytes = peekLen,
                        targetFile = tempFile,
                        fileOffset = peekAt,
                        isCancelled = isCancelled
                    )
                    if (!peekResult.success || !peekResult.rangeSatisfied) {
                        Log.w(TAG, "MP4 header peek failed at $peekAt")
                        return null
                    }
                    startPos = peekAt
                }
            }
        }
        Log.w(TAG, "MP4 box walk exceeded peek budget without resolving mdat end")
        return null
    }

    /**
     * Parses top-level MP4 boxes to find the end offset of 'mdat'.
     * Non-mdat boxes may be skipped using only their header size even when the payload is not
     * present locally (e.g. multi-MB 'free' padding larger than the 4 MB head window).
     */
    internal fun walkMp4BoxesForMdatEnd(
        file: java.io.File,
        contentLength: Long,
        startPos: Long = 0L
    ): Mp4MdatWalkResult {
        if (!file.exists() || contentLength <= 0L) return Mp4MdatWalkResult.Failed
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                var pos = startPos.coerceAtLeast(0L)
                repeat(64) {
                    if (pos >= contentLength) return@use Mp4MdatWalkResult.Failed
                    if (pos + 8L > fileLen) return@use Mp4MdatWalkResult.NeedHeaderAt(pos)

                    raf.seek(pos)
                    val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val typeBytes = ByteArray(4)
                    raf.readFully(typeBytes)
                    val type = String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII)

                    val headerSize: Int
                    val boxSize: Long
                    when {
                        size32 == 1L -> {
                            if (pos + 16L > fileLen) return@use Mp4MdatWalkResult.NeedHeaderAt(pos)
                            val size64 = raf.readLong()
                            headerSize = 16
                            boxSize = size64
                        }
                        size32 == 0L -> {
                            headerSize = 8
                            boxSize = contentLength - pos
                        }
                        else -> {
                            headerSize = 8
                            boxSize = size32
                        }
                    }

                    if (boxSize < headerSize.toLong()) return@use Mp4MdatWalkResult.Failed

                    if (type == "mdat") {
                        val mdatEnd = pos + boxSize
                        return@use if (mdatEnd in (pos + headerSize.toLong())..contentLength) {
                            Mp4MdatWalkResult.Found(mdatEnd)
                        } else {
                            Mp4MdatWalkResult.Failed
                        }
                    }

                    // Header-only skip: payload need not be present in the local probe file.
                    pos += boxSize
                    if (pos < contentLength && pos + 8L > fileLen) {
                        return@use Mp4MdatWalkResult.NeedHeaderAt(pos)
                    }
                }
                Mp4MdatWalkResult.Failed
            }
        }.getOrDefault(Mp4MdatWalkResult.Failed)
    }

    internal fun fetchHttpRangeToFile(
        url: String,
        headers: Map<String, String>,
        rangeHeader: String,
        maxBytes: Long,
        targetFile: java.io.File,
        fileOffset: Long = 0L,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): HttpRangeFetchResult {
        if (isCancelled()) return HttpRangeFetchResult(success = false)
        val requestBuilder = probeRequestBuilder(url, headers).header("Range", rangeHeader)

        val call = probeHttpClient.newCall(requestBuilder.build())
        return try {
            call.execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    Log.w(TAG, "fetchHttpRangeToFile server did not return 200/206 Content (code=${response.code})")
                    return HttpRangeFetchResult(success = false)
                }
                val totalLength = parseContentRangeTotalLength(response.header("Content-Range"))
                    ?: response.header("Content-Length")?.toLongOrNull()

                val body = response.body
                body.byteStream().use { input ->
                    java.io.RandomAccessFile(targetFile, "rw").use { raf ->
                        if (fileOffset > 0L) {
                            raf.seek(fileOffset)
                        }
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        while (totalRead < maxBytes) {
                            if (isCancelled()) {
                                call.cancel()
                                return HttpRangeFetchResult(success = false)
                            }
                            val toRead = minOf(buffer.size.toLong(), maxBytes - totalRead).toInt()
                            val bytesRead = input.read(buffer, 0, toRead)
                            if (bytesRead <= 0) break
                            raf.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                        }
                        HttpRangeFetchResult(
                            success = totalRead > 0,
                            totalContentLength = totalLength,
                            rangeSatisfied = response.code == 206
                        )
                    }
                }
            }
        } catch (e: Exception) {
            call.cancel()
            Log.w(TAG, "fetchHttpRangeToFile failed for host=${url.safeHost()} range=$rangeHeader: ${e.message}")
            HttpRangeFetchResult(success = false)
        }
    }

    internal fun fetchContentLength(
        url: String,
        headers: Map<String, String>,
        isCancelled: () -> Boolean = NEVER_CANCELLED
    ): Long {
        if (isCancelled()) return -1L
        val requestBuilder = probeRequestBuilder(url, headers).head()

        val call = probeHttpClient.newCall(requestBuilder.build())
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return -1L
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (_: Exception) {
            call.cancel()
            -1L
        }
    }

    private fun allowMkvLocalTimestampSampling(file: java.io.File): Boolean {
        val layout = MatroskaAfrProbe.analyzeHead(file) ?: return false
        return layout.clusterInPrefix
    }
}
