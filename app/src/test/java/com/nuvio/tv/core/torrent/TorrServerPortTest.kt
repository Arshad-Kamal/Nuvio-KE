package com.nuvio.tv.core.torrent

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Spec 148: TorrServer listens on 127.0.0.1 (D2) and falls back to a free port when its preferred
 * port is taken there (D1). Linux bind semantics (the build host and Android).
 */
class TorrServerPortTest {

    private val loopback = InetAddress.getByName(TorrServerBinary.HOST)

    @Test
    fun freePreferredPortIsKept() {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(free, TorrServerBinary.pickPort(free))
    }

    @Test
    fun portHeldOnLoopbackFallsBackToAFreeLoopbackPort() {
        ServerSocket().use { held ->
            held.bind(InetSocketAddress(loopback, 0))
            val picked = TorrServerBinary.pickPort(held.localPort)
            assertNotEquals(held.localPort, picked)
            ServerSocket().use { it.bind(InetSocketAddress(loopback, picked)) } // listenable
        }
    }

    @Test
    fun portHeldOnAllInterfacesFallsBack() {
        ServerSocket(0).use { held ->
            assertNotEquals(held.localPort, TorrServerBinary.pickPort(held.localPort))
        }
    }

    @Test
    fun portHeldOnlyOnAnotherAddressIsKept() {
        // F26: another app held 192.0.2.119:8091 only; 127.0.0.2 stands in for that address.
        // The port is taken free on 127.0.0.1 first: an ephemeral 127.0.0.2:0 may reuse one in use there.
        val port = ServerSocket().use { it.bind(InetSocketAddress(loopback, 0)); it.localPort }
        ServerSocket().use { held ->
            held.bind(InetSocketAddress(InetAddress.getByName("127.0.0.2"), port))
            assertEquals(held.localPort, TorrServerBinary.pickPort(held.localPort))
        }
    }
}
