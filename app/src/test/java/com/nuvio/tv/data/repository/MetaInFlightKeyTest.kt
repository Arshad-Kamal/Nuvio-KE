package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pins MetaRepositoryImpl's multi-addon in-flight lookup key (L8-03/20/71). */
class MetaInFlightKeyTest {

    @Test
    fun `lookup key separates callers with different source addons`() {
        val lookupKey = "series:tt1"

        assertNotEquals(metaInFlightKey(lookupKey, null), metaInFlightKey(lookupKey, "https://a.example"))
        assertNotEquals(
            metaInFlightKey(lookupKey, "https://a.example"),
            metaInFlightKey(lookupKey, "https://b.example")
        )
        assertEquals(
            metaInFlightKey(lookupKey, "https://a.example"),
            metaInFlightKey(lookupKey, "https://a.example")
        )
    }
}
