package com.mavis.m3u8dlna.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.mavis.m3u8dlna.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * SSDP control point: sends `M-SEARCH` on 239.255.255.250:1900 and keeps
 * listening for the renderers' own `NOTIFY` announcements, so a TV that wakes
 * up later still shows up without the user tapping refresh.
 */
class SsdpDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onHit: (SsdpHit) -> Unit,
) {

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors

    private val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Nudges the loop to send an M-SEARCH right now instead of at the next tick. */
    fun searchNow() {
        scope.launch { trigger.tryEmit(Unit) }
    }

    private suspend fun loop() {
        val lock = NetworkUtils.acquireMulticastLock(context, MULTICAST_LOCK_TAG)
        if (lock == null) {
            _errors.tryEmit("Не удалось захватить multicast lock — телевизор может не найтись")
        }

        val socket = try {
            MulticastSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                soTimeout = RECEIVE_TIMEOUT_MS
                preferredNetworkInterface()?.let { networkInterface = it }
                joinMulticastGroups()
            }
        } catch (e: IOException) {
            Log.w(TAG, "SSDP socket failed", e)
            _errors.tryEmit("Ошибка SSDP: ${e.message}")
            releaseQuietly(lock)
            return
        }

        var burst = 0
        try {
            while (currentCoroutineContext().isActive) {
                if (burst < INITIAL_SEARCHES) {
                    sendSearch(socket)
                    burst++
                    delay(Random.nextLong(150, 400))
                } else {
                    // Idle: either the user asked for a refresh, or sweep the LAN.
                    val requested = withTimeoutOrNull(SEARCH_INTERVAL_MS) { trigger.first() }
                    if (requested == null) sendSearch(socket) else burst = 0
                }
                drainSocket(socket)
            }
        } catch (e: IOException) {
            Log.w(TAG, "SSDP loop ended", e)
        } finally {
            runCatching { socket.close() }
            releaseQuietly(lock)
        }
    }

    private suspend fun drainSocket(socket: MulticastSocket) {
        val buffer = ByteArray(BUFFER_SIZE)
        // Bounded read window so a chatty LAN cannot starve the rest of the work.
        val deadline = System.currentTimeMillis() + DRAIN_WINDOW_MS
        while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                return
            } catch (e: IOException) {
                Log.w(TAG, "receive failed", e)
                return
            }
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val hit = SsdpParser.parse(text) ?: continue
            if (SsdpParser.isByebye(hit.searchTarget)) continue
            if (!SsdpParser.isInterestingTarget(hit.searchTarget)) continue
            onHit(hit)
        }
    }

    private fun sendSearch(socket: MulticastSocket) {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $MULTICAST_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: $MX_SECONDS\r\n")
            append("ST: $MEDIA_RENDERER_TARGET\r\n")
            append("USER-AGENT: $USER_AGENT\r\n")
            append("\r\n")
        }
        runCatching {
            val bytes = request.toByteArray(Charsets.UTF_8)
            socket.send(
                DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName(MULTICAST_ADDRESS),
                    SSDP_PORT,
                ),
            )
        }.onFailure { Log.w(TAG, "M-SEARCH failed", it) }
    }

    private fun MulticastSocket.joinMulticastGroups() {
        val group = InetAddress.getByName(MULTICAST_ADDRESS)
        val usable = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter {
                it.isUp && !it.isLoopback && it.supportsMulticast()
            }
        }.getOrDefault(emptyList())

        if (usable.isEmpty()) {
            runCatching { joinGroup(group) }
            return
        }
        // The two-argument overload on Android takes a SocketAddress.
        usable.forEach { nic ->
            runCatching { joinGroup(InetSocketAddress(group, SSDP_PORT), nic) }
        }
    }

    private fun MulticastSocket.preferredNetworkInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .firstOrNull()
    }.getOrNull()

    private fun releaseQuietly(lock: WifiManager.MulticastLock?) {
        runCatching { lock?.release() }
    }

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val MULTICAST_LOCK_TAG = "m3u8dlna-ssdp"
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MX_SECONDS = 2
        private const val MEDIA_RENDERER_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val USER_AGENT = "Android/UPnP/1.1 M3u8DLNA/1.0"
        private const val BUFFER_SIZE = 8 * 1024
        private const val RECEIVE_TIMEOUT_MS = 700
        private const val DRAIN_WINDOW_MS = 1_500
        private const val INITIAL_SEARCHES = 3
        private const val SEARCH_INTERVAL_MS = 30_000L
    }
}
