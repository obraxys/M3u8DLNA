package com.mavis.m3u8dlna

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mavis.m3u8dlna.data.HistoryItem
import com.mavis.m3u8dlna.data.HistoryStore
import com.mavis.m3u8dlna.data.StreamHeaders
import com.mavis.m3u8dlna.dlna.DlnaDevice
import com.mavis.m3u8dlna.dlna.DlnaHub
import com.mavis.m3u8dlna.dlna.DiscoveryService
import com.mavis.m3u8dlna.player.LocalPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val url: String = "",
    val title: String = "",
    val headers: StreamHeaders = StreamHeaders(),
    val headersExpanded: Boolean = false,
    val localPlayback: Boolean = false,
    val localError: String? = null,
    val devices: List<DlnaDevice> = emptyList(),
    val discovering: Boolean = false,
    val cast: DlnaHub.CastSession? = null,
    val volume: Int? = null,
    val busyDevice: String? = null,
    val message: String? = null,
    val history: List<HistoryItem> = emptyList(),
) {
    val canCast: Boolean get() = url.isNotBlank()
    val customHeaders: Boolean get() = !headers.isEmpty
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val history = HistoryStore(application)
    private val _state = MutableStateFlow(UiState(history = history.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var localPlayer: LocalPlayer? = null

    init {
        DlnaHub.initialize(application)

        viewModelScope.launch {
            DlnaHub.devices.collect { devices -> _state.update { it.copy(devices = devices) } }
        }
        viewModelScope.launch {
            DlnaHub.discovering.collect { value -> _state.update { it.copy(discovering = value) } }
        }
        viewModelScope.launch {
            DlnaHub.cast.collect { session -> _state.update { it.copy(cast = session, busyDevice = null) } }
        }
        viewModelScope.launch {
            DlnaHub.volume.collect { value -> _state.update { it.copy(volume = value) } }
        }
        viewModelScope.launch {
            DlnaHub.errors.collect { message ->
                if (message != null) _state.update { it.copy(message = message) }
            }
        }
    }

    // ------------------------------------------------------------------ input

    fun setUrl(url: String) = _state.update { it.copy(url = url.trim(), localError = null) }

    fun setTitle(title: String) = _state.update { it.copy(title = title) }

    fun toggleHeaders() = _state.update { it.copy(headersExpanded = !it.headersExpanded) }

    fun updateHeaders(transform: (StreamHeaders) -> StreamHeaders) =
        _state.update { it.copy(headers = transform(it.headers)) }

    /** Accepts a link from a browser share sheet or the clipboard. */
    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val incoming = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.data?.toString()
            else -> null
        } ?: return

        val url = extractUrl(incoming) ?: return
        _state.update {
            it.copy(
                url = url,
                title = it.title.ifBlank { titleFromUrl(url) },
                message = if (url == it.url) "Ссылка уже открыта" else null,
            )
        }
    }

    private fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
        // A share from a chat app often wraps the link in a sentence.
        return Regex("https?://\\S+").find(trimmed)?.value?.trimEnd('.', ',', ')')
    }

    private fun titleFromUrl(url: String): String =
        runCatching { Uri.parse(url).lastPathSegment?.substringBefore('?').orEmpty() }
            .getOrDefault("")
            .ifBlank { url.substringAfterLast('/').ifBlank { "HLS" } }

    // ---------------------------------------------------------------- playback

    fun player(): ExoPlayer = localPlayer().player

    private fun localPlayer(): LocalPlayer = localPlayer ?: LocalPlayer(getApplication())
        .also { created ->
            created.player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(localError = error.errorCodeName) }
                }
            })
            localPlayer = created
        }

    fun playLocally() {
        val current = _state.value
        if (current.url.isBlank()) return
        _state.update { it.copy(localError = null) }
        runCatching {
            localPlayer().play(current.url, current.title, current.headers)
            _state.update { it.copy(localPlayback = true) }
            remember(current)
        }.onFailure { error ->
            _state.update { it.copy(localError = error.message ?: "Не удалось запустить плеер") }
        }
    }

    fun stopLocal() {
        localPlayer?.stop()
        _state.update { it.copy(localPlayback = false) }
    }

    // -------------------------------------------------------------------- cast

    fun startDiscovery() {
        DiscoveryService.start(getApplication<Application>())
        DlnaHub.start()
    }

    fun stopDiscovery() {
        DlnaHub.stopDiscovery()
        DiscoveryService.stop(getApplication<Application>())
    }

    fun refreshDevices() {
        startDiscovery()
        DlnaHub.searchNow()
    }

    fun castTo(device: DlnaDevice, mode: DlnaHub.CastMode) {
        val current = _state.value
        if (current.url.isBlank()) {
            _state.update { it.copy(message = "Сначала вставьте ссылку на поток") }
            return
        }

        _state.update { it.copy(busyDevice = device.udn, message = null) }
        startDiscovery()

        viewModelScope.launch {
            val result = DlnaHub.cast(
                device = device,
                url = current.url,
                title = current.title.ifBlank { titleFromUrl(current.url) },
                headers = current.headers,
                mode = mode,
            )
            result
                .onSuccess { remember(current) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            busyDevice = null,
                            message = "${device.friendlyName}: ${error.message ?: "ошибка DLNA"}",
                        )
                    }
                }
        }
    }

    fun toggleCastPlayback() {
        viewModelScope.launch { DlnaHub.playPause() }
    }

    fun stopCast() {
        viewModelScope.launch { DlnaHub.stopCast() }
    }

    fun setVolume(value: Int) {
        _state.update { it.copy(volume = value) }
        viewModelScope.launch { DlnaHub.setVolume(value) }
    }

    // ----------------------------------------------------------------- history

    fun loadFromHistory(item: HistoryItem) {
        _state.update {
            it.copy(url = item.url, title = item.title, headers = item.headers, localError = null)
        }
    }

    fun clearHistory() {
        _state.update { it.copy(history = history.clear()) }
    }

    private fun remember(current: UiState) {
        val item = HistoryItem(
            url = current.url,
            title = current.title,
            timestamp = System.currentTimeMillis(),
            headers = current.headers,
        )
        val updated = history.remember(item)
        _state.update { it.copy(history = updated) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        localPlayer?.release()
        localPlayer = null
        super.onCleared()
    }
}
