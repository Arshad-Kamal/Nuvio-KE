package com.nuvio.tv.core.di

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

/**
 * Moshi retirement M1: pins the wire-Json contract that replaced Moshi codegen
 * on the kotlinx Retrofit services. [NetworkModule.provideWireJson] is the one
 * home — the test calls it directly so config drift fails here, not in prod.
 * Pins are Moshi-parity claims: unknown keys ignored, absent nullables read as
 * null, defaults encoded, quoted numbers decode; divergences from Moshi are
 * pinned as-built and recorded in the json-consolidation spec (numeric-literal
 * tightening, explicit-null-into-defaulted-non-null loosening).
 */
class WireJsonParityTest {

    private val json = NetworkModule.provideWireJson()

    @Serializable
    private enum class Level { A, B }

    @Serializable
    private data class ConfigMock(
        val required: String,
        val label: String? = null,
        val count: Int = 7,
        val level: Level = Level.A
    )

    // ── config parity with Moshi codegen ──────────────────────────────────────

    @Test
    fun `unknown keys are ignored`() {
        val decoded = json.decodeFromString<ConfigMock>(
            """{"required":"x","sentinel_unknown":{"nested":[1,2]},"count":3}"""
        )
        assertEquals(ConfigMock(required = "x", count = 3), decoded)
    }

    @Test
    fun `absent nullable field decodes as null and default fields fill in`() {
        val decoded = json.decodeFromString<ConfigMock>("""{"required":"x"}""")
        assertNull(decoded.label)
        assertEquals(7, decoded.count)
        assertEquals(Level.A, decoded.level)
    }

    @Test
    fun `explicit null into a nullable field decodes as null`() {
        val decoded = json.decodeFromString<ConfigMock>("""{"required":"x","label":null}""")
        assertNull(decoded.label)
    }

    @Test
    fun `explicit null into a non-null field is rejected - defaulted or not`() {
        // Pinned as-built (JsonDecodingException, a SerializationException → garbage):
        // explicitNulls=false only relaxes ABSENT nullables; a written null against a
        // non-null property stays an error, with or without a default. Moshi codegen
        // enforced the same non-null contract.
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ConfigMock>("""{"required":"x","count":null}""")
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ConfigMock>("""{"required":null}""")
        }
    }

    @Test
    fun `missing required field is a SerializationException`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ConfigMock>("""{"count":1}""")
        }
    }

    @Test
    fun `unknown enum value is a SerializationException not a coercion`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ConfigMock>("""{"required":"x","level":"Z"}""")
        }
    }

    @Test
    fun `unquoted scalar stays malformed - json is not lenient`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ConfigMock>("""{"required": x}""")
        }
    }

    @Test
    fun `numeric literal forms into Int - parity and one recorded tightening`() {
        // Empirically pinned on kotlinx 1.8.1 streaming decode:
        // - "3" (quoted) decodes — Moshi parity.
        // - 6e0 (integral exponent) coerces to 6 — Moshi parity.
        // - 7.5 (non-integral) rejects — Moshi's lossless-int check also rejected.
        // - 7.0 (integral with decimal point) REJECTS where Moshi coerced — the one
        //   recorded numeric tightening; lands in isDecodeGarbage (W2.3). Inert on
        //   today's wire payloads (proper integers).
        assertEquals(3, json.decodeFromString<ConfigMock>("""{"required":"x","count":"3"}""").count)
        assertEquals(6, json.decodeFromString<ConfigMock>("""{"required":"x","count":6e0}""").count)
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ConfigMock>("""{"required":"x","count":7.5}""")
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ConfigMock>("""{"required":"x","count":7.0}""")
        }
    }

    @Test
    fun `null element inside a non-null list is a SerializationException - tightening vs Moshi`() {
        // Moshi's collection adapters produced a poisoned list (null smuggled into a
        // non-null element type); kotlinx rejects at decode → garbage-classified.
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<List<ConfigMock>>("""[{"required":"a"},null]""")
        }
    }

    @Test
    fun `encode writes defaults and omits nulls`() {
        // encodeDefaults=true mirrors Moshi adapters (all fields written);
        // explicitNulls=false omits nulls where Moshi wrote explicit nulls —
        // the recorded M1 write delta, pinned here deliberately.
        val encoded = json.encodeToString(ConfigMock(required = "x"))
        assertEquals("""{"required":"x","count":7,"level":"A"}""", encoded)
    }

    // ── the production decode path: Retrofit + converter-kotlinx-serialization ─

    // Local fixture (the GitHub release DTO went with the in-app updater):
    // a snake_case @SerialName field exercises the same converter seam. Not
    // private: the converter resolves the serializer reflectively from a Type.
    @Serializable
    data class ReleaseMock(@SerialName("tag_name") val tagName: String? = null)

    private interface WireTestApi {
        @GET(".")
        suspend fun release(): ReleaseMock
    }

    @Test
    fun `retrofit converter decodes and surfaces malformed bodies unwrapped`() {
        // Pins the artifact seam every M2–M6 flip crosses: a malformed body must
        // reach safeApiCall as a raw SerializationException (isDecodeGarbage's
        // kotlinx arm), not wrapped by the converter or OkHttp.
        val server = MockWebServer()
        try {
            server.start()
            val retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(OkHttpClient())
                .addConverterFactory(
                    NetworkModule.provideWireJson().asConverterFactory("application/json".toMediaType())
                )
                .build()
            val api = retrofit.create(WireTestApi::class.java)

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"tag_name":"v9.9.9","extra_unknown":true}""")
            )
            assertEquals("v9.9.9", runBlocking { api.release() }.tagName)

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"tag_name": v9.9.9}""")
            )
            assertThrows(SerializationException::class.java) { runBlocking { api.release() } }
        } finally {
            server.shutdown()
        }
    }
}
