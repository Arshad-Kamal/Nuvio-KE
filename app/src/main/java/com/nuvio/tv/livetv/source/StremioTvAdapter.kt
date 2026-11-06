package com.nuvio.tv.livetv.source

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.hasUnsatisfiableRequiredExtra
import com.nuvio.tv.domain.model.requiredExtraDefaults
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A Stremio addon's usable live catalogs, read from its manifest. */
data class StremioTvManifest(val name: String, val catalogs: List<CatalogDescriptor>)

/**
 * Stremio `tv` addons as a Live TV source (Spec 34 D3/D6, O2, L5d). Talks to the addon itself,
 * never through `MetaRepository`, which folds `tv` into `series`: manifest check, `tv` catalogs
 * (paged with `skip` when declared, capped at [MAX_CHANNELS] / [MAX_PAGES]) listed as channels
 * keyed by `ChannelIdentity.stremioKey`, and streams resolved at play time from
 * `/stream/tv/{id}.json` with `behaviorHints.proxyHeaders.request` as request headers. Every
 * response is untrusted: fetched through [SourceFetcher]'s caps, decoded strictly, and a decode
 * failure surfaces as a [SourceFetchException] with a fixed message (KT-JSON-04).
 */
@Singleton
class StremioTvAdapter @Inject constructor(private val fetcher: SourceFetcher) {

    /** Reads a fetched manifest; refused (with the user-facing reason) unless it lists a usable `tv` catalog. */
    fun readManifest(body: InputStream): StremioTvManifest {
        val dto = decode("Not a Stremio addon manifest") {
            json.decodeFromString(AddonManifestDto.serializer(), body.reader(Charsets.UTF_8).readText())
        }
        val addon = dto.toDomain(baseUrl = "")
        val catalogs = addon.catalogs.filter { it.rawType.equals(TV, ignoreCase = true) && !it.hasUnsatisfiableRequiredExtra() }
        if (addon.rawTypes.none { it.equals(TV, ignoreCase = true) } || catalogs.isEmpty()) {
            throw SourceFetchException(NO_TV_CATALOG)
        }
        return StremioTvManifest(addon.name.ifBlank { addon.id }, catalogs)
    }

    /** Every channel of the addon at [manifestUrl]: catalogs in manifest order, first of any repeated key. */
    suspend fun channels(sourceId: String, manifestUrl: String, manifest: StremioTvManifest): List<Channel> {
        val base = baseOf(manifestUrl)
        val out = LinkedHashMap<String, Channel>()
        for (catalog in manifest.catalogs) {
            val paged = catalog.supportsExtra("skip")
            // A required extra with options must be sent (Stremio spec); its default, else its first option.
            val required = catalog.requiredExtraDefaults()
            var skip = 0
            for (page in 0 until MAX_PAGES) {
                if (out.size >= MAX_CHANNELS) break
                val extras = required.map { (k, v) -> "$k=$v" } + listOfNotNull(if (skip > 0) "skip=$skip" else null)
                val segments = listOf("catalog", catalog.rawType, catalog.id) + listOfNotNull(extras.joinToString("&").ifEmpty { null })
                // A first page that fails fails the refresh (old channels kept); a later one ends the
                // paging, since some addons answer past their last page with an error.
                val metas = try {
                    fetchJson(base, segments, CatalogResponseDto.serializer()).metas.filterNotNull()
                } catch (e: IOException) {
                    if (page == 0) throw e
                    break
                }
                var added = 0
                for (meta in metas) {
                    val id = meta.id?.trim()?.ifEmpty { null } ?: continue
                    val name = meta.name?.trim()?.ifEmpty { null } ?: id
                    val key = ChannelIdentity.stremioKey(sourceId, id, name)
                    if (key in out || out.size >= MAX_CHANNELS) continue
                    out[key] = Channel(
                        key = key,
                        sourceId = sourceId,
                        name = name,
                        providerId = id,
                        logo = meta.logo ?: meta.poster,
                        groups = meta.genres.orEmpty(),
                        // Resolved at play time ([streams]): addon streams expire and vary.
                        streams = emptyList()
                    )
                    added++
                }
                // An addon that ignores skip returns page one again: no new key ends the paging.
                if (!paged || metas.isEmpty() || added == 0) break
                skip += metas.size
            }
        }
        return out.values.toList()
    }

    /** Playable streams of [providerId] (direct URLs only; external links, YouTube and torrents are skipped). */
    suspend fun streams(manifestUrl: String, providerId: String): List<LiveStream> {
        val response = fetchJson(baseOf(manifestUrl), listOf("stream", TV, providerId), StreamResponseDto.serializer())
        return response.streams.orEmpty().mapNotNull { s ->
            val url = s.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
            LiveStream(url, s.behaviorHints?.proxyHeaders?.request.orEmpty())
        }.distinctBy { it.url }
    }

    private suspend fun <T> fetchJson(
        base: HttpUrl,
        segments: List<String>,
        serializer: kotlinx.serialization.KSerializer<T>
    ): T {
        val builder = base.newBuilder()
        // addPathSegment encodes: ids carry spaces, parentheses and accents (D9). The last one gets `.json`.
        segments.forEachIndexed { i, s -> builder.addPathSegment(if (i == segments.lastIndex) "$s.json" else s) }
        return fetcher.fetch(builder.build().toString()) { _, body ->
            decode("Unreadable addon response") {
                json.decodeFromString(serializer, body.reader(Charsets.UTF_8).readText())
            }
        }
    }

    // IllegalArgumentException covers SerializationException (its subclass) and init-block checks (KT-JSON-04).
    private inline fun <T> decode(reason: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw SourceFetchException(reason)
    }

    companion object {
        const val TV = "tv"
        const val MAX_CHANNELS = 5_000
        const val MAX_PAGES = 50
        const val NO_TV_CATALOG = "This addon has no TV channel catalog"
        private val json = NetworkModule.provideWireJson()

        /** `stremio://host/...` (the install-link form) is the same addon over https. */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim()
            return if (t.startsWith("stremio://", ignoreCase = true)) "https://" + t.substring("stremio://".length) else t
        }

        /**
         * The addon root: the manifest URL without its last path segment (`manifest.json`). The
         * query stays: configurable addons may carry their config there, and every resource URL
         * built from this root keeps it (as the app's own addon client does).
         */
        internal fun baseOf(manifestUrl: String): HttpUrl {
            val url = manifestUrl.toHttpUrlOrNull() ?: throw SourceFetchException("Not a web address")
            val segments = url.pathSegments.dropLast(1).filter { it.isNotEmpty() }
            val b = url.newBuilder().fragment(null).encodedPath("/")
            segments.forEach { b.addPathSegment(it) }
            return b.build()
        }
    }
}
