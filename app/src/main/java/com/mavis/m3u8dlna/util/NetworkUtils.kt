package com.mavis.m3u8dlna.util

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket

object NetworkUtils {

    /**
     * Best guess at the phone's LAN IPv4 address — that is the URL a renderer
     * has to be able to reach, so it must be the address of the Wi-Fi
     * interface, not the VPN or cellular one.
     */
    fun localIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull()
            .orEmpty()

        val candidates = interfaces
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .sortedBy { priority(it) }

        for (nic in candidates) {
            val address = nic.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?: nic.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()
            if (address != null) return address.hostAddress
        }
        return null
    }

    private fun priority(nic: NetworkInterface): Int = when (nic.name) {
        "wlan0", "wlan1" -> 0
        "eth0", "eth1" -> 1
        else -> 2
    }

    /**
     * MulticastLock: without it, Android drops SSDP M-SEARCH packets as soon as
     * the screen goes off. Returns the lock so the caller can release it.
     */
    fun acquireMulticastLock(context: Context, tag: String): WifiManager.MulticastLock? =
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock(tag)?.apply {
                setReferenceCounted(true)
                acquire()
            }
        }.getOrNull()

    fun urlEncode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun urlDecode(value: String): String? =
        runCatching {
            String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }.getOrNull()

    /** Finds a free TCP port starting at [preferred]; 0 if the machine is out of ports. */
    fun findFreePort(preferred: Int, attempts: Int = 12): Int {
        for (offset in 0 until attempts) {
            val port = preferred + offset
            if (port > 65535) break
            val taken = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
            if (!taken) return port
        }
        return 0
    }

    fun isIpv4(host: String): Boolean =
        runCatching { InetAddress.getByName(host) as? Inet4Address != null }.getOrDefault(false)
}
