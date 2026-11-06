package com.nuvio.tv.core.server

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceIpAddress {

    fun get(context: Context): String? {
        // Try WifiManager first
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        // why: still works on API 31+; the LinkProperties replacement first needs the Wi-Fi Network
        // handle (allNetworks is itself deprecated, API 31+ wants a NetworkCallback): no gain here.
        @Suppress("DEPRECATION")
        val wifiIp = wifiManager.connectionInfo.ipAddress
        if (wifiIp != 0) {
            return formatIp(wifiIp)
        }

        // Fallback: Ethernet / other connections
        return localIpv4Addresses().firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }

    private fun formatIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}

/**
 * Every IPv4 address on this device's interfaces, loopback included; empty when
 * enumeration fails. One home for the QR address fallback above and the config
 * servers' own-address checks (ConfigServerGuard).
 */
internal fun localIpv4Addresses(): List<Inet4Address> = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
} catch (_: Exception) {
    emptyList()
}
