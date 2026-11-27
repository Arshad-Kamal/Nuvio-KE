package com.nuvio.tv.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingProviderRegistryTest {

    @Test
    fun `a build with no tracker key has no providers (the hub stays hidden)`() {
        val registry = TrackingProviderRegistry(emptySet())
        assertFalse(registry.hasProviders)
        assertTrue(registry.connectedProviderIds().isEmpty())
        assertTrue(registry.connectedScrobblers().isEmpty())
    }

    @Test
    fun `providers come in display order whatever the binding order`() {
        val registry = TrackingProviderRegistry(
            setOf(FakeTrackingProvider(TrackingProviderId.SIMKL, true), FakeTrackingProvider(TrackingProviderId.MDBLIST, true))
        )
        assertTrue(registry.hasProviders)
        assertEquals(listOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL), registry.providers().map { it.descriptor.id })
    }

    @Test
    fun `connected state follows each provider's sign-in`() {
        val simkl = FakeTrackingProvider(TrackingProviderId.SIMKL, connected = false)
        val registry = TrackingProviderRegistry(setOf(simkl, FakeTrackingProvider(TrackingProviderId.MDBLIST, true)))
        assertEquals(setOf(TrackingProviderId.MDBLIST), registry.connectedProviderIds())
        assertFalse(registry.isAuthenticated(TrackingProviderId.SIMKL))

        simkl.authenticated.value = true
        assertEquals(setOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL), registry.connectedProviderIds())
    }

    @Test
    fun `only connected providers that can scrobble get playback reports`() {
        val registry = TrackingProviderRegistry(
            setOf(
                FakeTrackingProvider(TrackingProviderId.MDBLIST, connected = true, capabilities = setOf(TrackingCapability.LIBRARY_READ)),
                FakeTrackingProvider(TrackingProviderId.SIMKL, connected = true)
            )
        )
        assertEquals(listOf(TrackingProviderId.SIMKL), registry.connectedScrobblers().map { it.providerId })

        val offline = TrackingProviderRegistry(setOf(FakeTrackingProvider(TrackingProviderId.SIMKL, connected = false)))
        assertTrue(offline.connectedScrobblers().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two providers with one id are refused`() {
        TrackingProviderRegistry(
            setOf(FakeTrackingProvider(TrackingProviderId.SIMKL, true), FakeTrackingProvider(TrackingProviderId.SIMKL, false))
        )
    }
}
