# Add project specific ProGuard rules here.

# ── Retrofit ───────────────────────────────────────────────────────────────────
# Keep generic signatures for Retrofit service methods
-keepattributes Signature
# Keep Retrofit service interfaces (must preserve generic return types)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Keep all project API interfaces
-keep class com.nuvio.tv.data.remote.api.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# ktor-client-okhttp's okhttp-sse resolves 4.12.0 and references okhttp3.internal.Util, which the
# pinned 5.3.2 runtime removed. Same suppression the app shipped with before DRDYK A2.1 (it
# lived in the plugin block); the SSE path is unused (Supabase installs only Auth/Postgrest/
# Storage over plain HTTP; no SSE or realtime plugin).
-dontwarn okhttp3.internal.sse.**
# Keep exception class names readable in diagnostics (speed-test failureReason and similar
# build strings from t.javaClass.simpleName); the broad okhttp3/okio keeps went with the
# plugin runtime (DRDYK A2.1).
-keepnames class okhttp3.** extends java.lang.Throwable
-keepnames class okio.** extends java.lang.Throwable

# ── Data classes (DTOs) ────────────────────────────────────────────────────────
# Keep all DTO classes (Retrofit return types; reflection-free under kotlinx, kept for R8 stability)
-keep class com.nuvio.tv.data.remote.dto.** { *; }
-keep class com.nuvio.tv.domain.model.** { *; }
# Health records (kotlinx-serialised since the json-consolidation campaign;
# rule retained for the nested custom class + enum shapes)
-keep class com.nuvio.tv.core.health.** { *; }

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Runtime-visible annotations (Retrofit reads its HTTP annotations reflectively).
# Note: kotlin.Metadata classes themselves are no longer force-kept (the blanket
# kotlin.** keep went with the plugin runtime, DRDYK A2.1).
-keepattributes RuntimeVisibleAnnotations

# ── NanoHTTPD (used by local server) ───────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
# Keep server classes and their inner data classes (kotlinx-serialised since
# the json-consolidation campaign)
-keep class com.nuvio.tv.core.server.** { *; }

# ── Torrent streaming (TorrServer) ─────────────────────────────────────────────
-keep class com.nuvio.tv.core.torrent.** { *; }

# ── ExoPlayer / Media3 ────────────────────────────────────────────────────────
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

# NuvioAssMatroskaExtractor (libass path) extends the vendored dvmkv
# MatroskaExtractor. This keep was added for its former reflective access to
# extractorOutput / subtitleSample; those fields are now protected and read
# directly, so the rule is no longer required for correctness. Kept until a
# minified release build confirms dropping it changes nothing.
-keep class com.nuvio.tv.core.player.dvmkv.MatroskaExtractor { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-keep class com.google.android.exoplayer2.ext.** { *; }

# Keep native interfaces and handles for Nuvio Engine JNI
-keep class androidx.media3.exoplayer.upstream.DefaultAllocatorNative {
    native <methods>;
}
-keep class androidx.media3.exoplayer.source.SampleDataQueueNative {
    native <methods>;
}
-keep class androidx.media3.exoplayer.upstream.Allocation {
    <init>(java.nio.ByteBuffer, int, long);
    public long nativeHandle;
}

# ── Supabase / Ktor / Kotlinx Serialization ───────────────────────────────────
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class com.nuvio.tv.data.remote.supabase.** { *; }
# Keep @Serializable classes and their generated serializers
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── General ────────────────────────────────────────────────────────────────────
# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MPV (native JNI callbacks)
# Native code reflects into multiple classes/methods under is.xyz.mpv,
# so keep the whole package to avoid JNI lookup crashes after R8.
-keep class is.xyz.mpv.** { *; }
