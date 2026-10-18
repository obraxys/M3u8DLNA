package com.mavis.m3u8dlna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mavis.m3u8dlna.MainViewModel
import com.mavis.m3u8dlna.UiState
import com.mavis.m3u8dlna.data.HistoryItem
import com.mavis.m3u8dlna.dlna.DlnaDevice
import com.mavis.m3u8dlna.dlna.DlnaHub
import com.mavis.m3u8dlna.dlna.TransportInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: UiState, viewModel: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    var devicePendingCast by remember { mutableStateOf<DlnaDevice?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("M3U8 → TV", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = viewModel::refreshDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить список")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StreamSection(state, viewModel)
            if (state.localPlayback) PlayerSection(viewModel)
            if (state.cast != null) NowPlayingSection(state, viewModel)
            DevicesSection(state, viewModel, onCast = { devicePendingCast = it })
            if (state.history.isNotEmpty()) HistorySection(state, viewModel)
            Spacer(Modifier.height(16.dp))
        }
    }

    devicePendingCast?.let { device ->
        CastModeDialog(
            device = device,
            onDismiss = { devicePendingCast = null },
            onPick = { mode ->
                devicePendingCast = null
                viewModel.castTo(device, mode)
            },
        )
    }
}

@Composable
private fun StreamSection(state: UiState, viewModel: MainViewModel) {
    val clipboard = LocalClipboardManager.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ссылка на поток", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("https://…/playlist.m3u8") },
                singleLine = true,
                trailingIcon = {
                    if (state.url.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setUrl("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить")
                        }
                    }
                },
            )

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Название (видно на телевизоре)") },
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::playLocally,
                    enabled = state.url.isNotBlank(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("На телефоне")
                }

                FilledTonalButton(
                    onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        val first = pasted.split(Regex("\\s+")).firstOrNull { it.startsWith("http") }
                        if (first != null) viewModel.setUrl(first)
                    },
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Вставить")
                }
            }

            state.localError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            HeaderEditor(state, viewModel)
        }
    }
}

@Composable
private fun HeaderEditor(state: UiState, viewModel: MainViewModel) {
    Column {
        TextButton(onClick = viewModel::toggleHeaders) {
            Icon(
                if (state.headersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.customHeaders) "Заголовки запроса — свои" else "Заголовки запроса",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (!state.headersExpanded) return@Column

        Text(
            "Некоторые ссылки не играют без Referer или Cookie. Телевизор их не пришлёт — " +
                "приложение подставит само.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        HeaderField("User-Agent", state.headers.userAgent) { value ->
            viewModel.updateHeaders { it.copy(userAgent = value) }
        }
        HeaderField("Referer", state.headers.referer, placeholder = "https://site.com/") { value ->
            viewModel.updateHeaders { it.copy(referer = value) }
        }
        HeaderField("Cookie", state.headers.cookie, placeholder = "PHPSESSID=…") { value ->
            viewModel.updateHeaders { it.copy(cookie = value) }
        }
        HeaderField("Origin", state.headers.origin, placeholder = "https://site.com") { value ->
            viewModel.updateHeaders { it.copy(origin = value) }
        }
    }
}

@Composable
private fun HeaderField(label: String, value: String, placeholder: String = "", onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
    )
}

@Composable
private fun PlayerSection(viewModel: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(8.dp),
            factory = { context ->
                PlayerView(context).apply {
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = viewModel.player()
                }
            },
            update = { it.player = viewModel.player() },
        )
    }
}

@Composable
private fun NowPlayingSection(state: UiState, viewModel: MainViewModel) {
    val session = state.cast ?: return
    val info = session.info
    val position = session.position

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tv, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        session.device.friendlyName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildString {
                            append(stateLabel(info, session))
                            if (session.mode == DlnaHub.CastMode.PROXY) {
                                append(" · через прокси телефона")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (position != null && position.durationSeconds > 0) {
                val progress = (position.positionSeconds.toFloat() / position.durationSeconds).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatDuration(position.positionSeconds)} / ${formatDuration(position.durationSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            session.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = viewModel::toggleCastPlayback) {
                    Icon(
                        if (info?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (info?.isPlaying == true) "Пауза" else "Играть")
                }
                FilledTonalButton(onClick = viewModel::stopCast) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Стоп")
                }
            }

            if (state.volume != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Громкость", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = state.volume.toFloat(),
                        onValueChange = { viewModel.setVolume(it.toInt()) },
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        valueRange = 0f..100f,
                        steps = 19,
                    )
                    Text("${state.volume}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DevicesSection(
    state: UiState,
    viewModel: MainViewModel,
    onCast: (DlnaDevice) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Устройства DLNA",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.discovering) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                FilledTonalButton(onClick = viewModel::refreshDevices) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Найти")
                }
            }

            if (state.devices.isEmpty()) {
                Text(
                    if (state.discovering) "Ищем телевизоры и приставки в сети…"
                    else "Нажмите «Найти». Телефон и телевизор должны быть в одной Wi-Fi сети.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.devices.forEach { device ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.friendlyName, style = MaterialTheme.typography.bodyLarge)
                        val subtitle = listOfNotNull(
                            device.subtitle.takeIf { it.isNotBlank() },
                            device.host,
                        ).joinToString(" · ")
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (state.busyDevice == device.udn) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { onCast(device) }, enabled = state.canCast) {
                            Icon(Icons.Default.Cast, contentDescription = "Передать на устройство")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySection(state: UiState, viewModel: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("История", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::clearHistory) {
                    Icon(Icons.Default.Delete, contentDescription = "Очистить историю")
                }
            }
            state.history.take(8).forEach { item ->
                HistoryRow(item) { viewModel.loadFromHistory(item) }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            item.title.ifBlank { item.url },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CastModeDialog(
    device: DlnaDevice,
    onDismiss: () -> Unit,
    onPick: (DlnaHub.CastMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.friendlyName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Как передать поток?")
                AssistChip(
                    onClick = { onPick(DlnaHub.CastMode.PROXY) },
                    label = { Text("Через телефон — работает всегда") },
                    leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { onPick(DlnaHub.CastMode.DIRECT) },
                    label = { Text("Напрямую — быстрее, но нужна открытая ссылка") },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun stateLabel(info: TransportInfo?, session: DlnaHub.CastSession): String =
    when {
        session.error != null -> "Ошибка: ${session.error}"
        info == null -> "Подключение…"
        info.isPlaying -> "Играет"
        info.isPaused -> "Пауза"
        info.isTransitioning -> "Переключение…"
        info.isStopped -> "Остановлено"
        else -> info.state
    }

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0:00"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}
