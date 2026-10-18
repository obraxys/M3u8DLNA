package com.mavis.m3u8dlna.dlna

import android.content.Context
import android.util.Log
import com.mavis.m3u8dlna.data.StreamHeaders
import com.mavis.m3u8dlna.proxy.M3u8ProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Application-scoped owner of everything network-facing: SSDP discovery, the
 * device cache, the local proxy and the transport polling loop.
 *
 * Lives in a plain object on purpose — there is exactly one LAN session per
 * process, and threading a DI graph through this would not buy anything.
 */
object DlnaHub {

    private const val TAG = "DlnaHub"
    private const val POLL_INTERVAL_MS = 2_500L
    private const val STOPPED_POLL_LIMIT = 2

    enum class CastMode { DIRECT, PROXY }

    data class CastSession(
        val device: DlnaDevice,
        val mode: CastMode,
        val uri: String,
        val title: String,
        val info: TransportInfo? = null,
        val position: PositionInfo? = null,
        val error: String? = null,
    )

    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope

    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _cast = MutableStateFlow<CastSession?>(null)
    val cast: StateFlow<CastSession?> = _cast.asStateFlow()

    private val _volume = MutableStateFlow<Int?>(null)
    val volume: StateFlow<Int?> = _volume.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    private var discovery: SsdpDiscovery? = null
    private var pollJob: Job? = null
    private val descriptions = ConcurrentHashMap<String, DlnaDevice>()
    private val deviceLock = Any()

    private val proxy by lazy { M3u8ProxyServer { upstreamClient } }
    private var proxyToken: String? = null

    private val upstreamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        discovery = SsdpDiscovery(appContext, scope) { hit -> resolveHit(hit) }.also { created ->
            scope.launch { created.errors.collect { message -> _errors.value = message } }
        }
    }

    fun start() {
        ensureInitialized()
        discovery?.start()
        _discovering.value = true
    }

    fun stopDiscovery() {
        discovery?.stop()
        _discovering.value = false
    }

    fun searchNow() {
        ensureInitialized()
        discovery?.searchNow()
    }

    fun clearError() {
        _errors.value = null
    }

    fun release() {
        pollJob?.cancel()
        pollJob = null
        discovery?.stop()
        proxy.shutdown()
        proxyToken = null
    }

    private fun ensureInitialized() {
        if (!::appContext.isInitialized) {
            throw IllegalStateException("DlnaHub.initialize(context) must be called first")
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun resolveHit(hit: SsdpHit) {
        scope.launch {
            val known = descriptions[hit.udn]
            if (known != null) {
                mergeDevice(known.copy(lastSeen = System.currentTimeMillis()))
                return@launch
            }
            val xml = fetchDescription(hit.location) ?: return@launch
            val device = DeviceDescriptionParser.parse(
                xml = xml,
                descriptionUrl = hit.location,
                udnHint = hit.udn,
                lastSeen = System.currentTimeMillis(),
            ) ?: return@launch

            descriptions[hit.udn] = device
            mergeDevice(device)
        }
    }

    private suspend fun fetchDescription(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            upstreamClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.onFailure { Log.w(TAG, "description fetch failed: ${it.message}") }.getOrNull()
    }

    /** Cheap device-list upsert; called from discovery coroutines. */
    private fun mergeDevice(device: DlnaDevice) {
        synchronized(deviceLock) {
            val current = _devices.value.toMutableList()
            val index = current.indexOfFirst { it.udn == device.udn }
            if (index >= 0) current[index] = device else current.add(device)
            current.sortBy { it.friendlyName.lowercase() }
            _devices.value = current
        }
    }

    // --------------------------------------------------------------------- cast

    /**
     * Hands [url] to [device]. In [CastMode.PROXY] the stream is re-served by
     * this phone, which is what makes header-protected links work on a TV.
     */
    suspend fun cast(
        device: DlnaDevice,
        url: String,
        title: String,
        headers: StreamHeaders,
        mode: CastMode,
    ): Result<String> = withContext(Dispatchers.IO) {
        val effectiveUri = when (mode) {
            CastMode.DIRECT -> url
            CastMode.PROXY -> {
                val token = proxyToken ?: M3u8ProxyServer.newToken().also { proxyToken = it }
                val base = proxy.register(token, url, headers)
                if (base.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("Не удалось определить IP телефона в локальной сети"),
                    )
                }
                proxy.urlFor(token)
            }
        }

        val client = AvTransportClient(controlUrl = device.avTransportControlUrl, renderingControlUrl = device.renderingControlUrl)

        val setResult = client.setUri(effectiveUri, title)
        (setResult as? SoapResult.Failure)?.let { failure ->
            return@withContext Result.failure(IllegalStateException(failure.message))
        }

        val playResult = client.play()
        (playResult as? SoapResult.Failure)?.let {
            return@withContext Result.failure(IllegalStateException(it.message))
        }

        _cast.value = CastSession(device, mode, effectiveUri, title)
        _volume.value = client.getVolume()
        startPolling(client)
        Result.success(effectiveUri)
    }

    suspend fun playPause() {
        val session = _cast.value ?: return
        val client = clientFor(session.device)
        val playing = session.info?.isPlaying == true
        val result = if (playing) client.pause() else client.play()
        if (result is SoapResult.Failure) {
            updateError(session, result.message)
        }
    }

    suspend fun stopCast() {
        val session = _cast.value ?: return
        val client = clientFor(session.device)
        client.stop()
        pollJob?.cancel()
        pollJob = null
        _cast.value = null
        _volume.value = null
        proxyToken?.let { proxy.unregister(it) }
        proxyToken = null
    }

    suspend fun setVolume(value: Int) {
        val session = _cast.value ?: return
        val client = clientFor(session.device)
        val result = client.setVolume(value)
        if (result is SoapResult.Success) _volume.value = value.coerceIn(0, 100)
    }

    private fun clientFor(device: DlnaDevice) = AvTransportClient(
        controlUrl = device.avTransportControlUrl,
        renderingControlUrl = device.renderingControlUrl,
    )

    private fun startPolling(client: AvTransportClient) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var stoppedCount = 0
            while (isActive) {
                val info = client.transportInfo()
                val position = client.positionInfo()
                val session = _cast.value
                if (session == null) break

                if (info == null) {
                    updateError(session, "Телевизор не отвечает")
                } else {
                    _cast.value = session.copy(info = info, position = position, error = null)
                    stoppedCount = if (info.isStopped) stoppedCount + 1 else 0
                    if (stoppedCount >= STOPPED_POLL_LIMIT) {
                        _cast.value = null
                        break
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun updateError(session: CastSession, message: String) {
        _cast.value = session.copy(error = message)
    }

    /** The URL the TV is currently playing, handy for a "copy link" action. */
    fun currentUrl(): String? = _cast.value?.uri
}
