package com.offlinechat.app.ui.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinechat.app.bluetooth.NearbyDevice
import com.offlinechat.app.bluetooth.ProbeState
import com.offlinechat.app.core.GroupAd
import com.offlinechat.app.ui.BluetoothActions
import com.offlinechat.app.ui.components.Avatar
import com.offlinechat.app.ui.components.EmptyState
import com.offlinechat.app.ui.components.SectionHeader
import com.offlinechat.app.ui.components.SignalBars
import com.offlinechat.app.ui.theme.avatarColor
import com.offlinechat.app.ui.theme.colorFromArgb
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    ready: Boolean,
    actions: BluetoothActions,
    onOpenChat: (Long) -> Unit,
    showMessage: (String) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val visible by viewModel.visible.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is NearbyEvent.OpenChat -> onOpenChat(event.conversationId)
                is NearbyEvent.Message -> showMessage(event.text)
            }
        }
    }
    LaunchedEffect(error) { error?.let { showMessage(it); viewModel.clearError() } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Nearby", style = MaterialTheme.typography.titleLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        else Spacer(Modifier.height(4.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { if (scanning) viewModel.stop() else viewModel.scan() },
                enabled = ready,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Stop" else "Search")
            }
            OutlinedButton(onClick = actions.requestVisible, enabled = ready, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (visible) "Visible" else "Make me visible")
            }
        }
        Text(
            "Both phones need OfflineChat open. One taps Search, the other taps Make me visible (not needed for phones that are already paired).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (devices.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.BluetoothSearching,
                title = if (scanning) "Looking around…" else "No phones found yet",
                body = if (scanning) "Keep both phones within a few metres of each other." else "Tap Search to look for phones nearby. Nothing is sent over the internet.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                val chatters = devices.filter { it.state == ProbeState.CHAT }
                val others = devices.filter { it.state != ProbeState.CHAT }
                if (chatters.isNotEmpty()) item(key = "h-chat", contentType = "header") { SectionHeader("People using OfflineChat") }
                items(chatters, key = { it.address }, contentType = { "device" }) { device ->
                    DeviceRow(device, busy, onChat = { viewModel.chat(device) }, onJoin = { viewModel.join(device, it) }, onRecheck = {})
                }
                if (others.isNotEmpty()) item(key = "h-other", contentType = "header") { SectionHeader("Other Bluetooth devices") }
                items(others, key = { it.address }, contentType = { "device" }) { device ->
                    DeviceRow(device, busy, onChat = { viewModel.chat(device) }, onJoin = { viewModel.join(device, it) }, onRecheck = { viewModel.recheck(device.address) })
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: NearbyDevice,
    busy: Set<String>,
    onChat: () -> Unit,
    onJoin: (GroupAd) -> Unit,
    onRecheck: () -> Unit,
) {
    val isChat = device.state == ProbeState.CHAT
    val title = if (isChat) device.chatName ?: device.name else device.name.ifBlank { device.address }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = isChat) { onChat() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name = title, color = avatarColor(device.address.hashCode().toLong()), size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (device.state) {
                        ProbeState.CHAT -> if (device.paired) "OfflineChat · paired" else "OfflineChat"
                        ProbeState.PROBING -> "Checking…"
                        ProbeState.NO_CHAT -> if (device.paired) "Paired · OfflineChat not open" else "OfflineChat not found"
                        ProbeState.UNKNOWN -> if (device.paired) "Paired" else "Bluetooth device"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (device.rssi != null) SignalBars(device.signalBars, Modifier.padding(horizontal = 8.dp).height(16.dp))
            when {
                device.state == ProbeState.PROBING || device.address in busy ->
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                isChat -> FilledTonalButton(onClick = onChat) { Text("Chat") }
                device.state == ProbeState.NO_CHAT && device.paired -> OutlinedButton(onClick = onRecheck) { Text("Retry") }
            }
        }
        for (group in device.groups) {
            val key = "${device.address}/${group.groupId}"
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 76.dp, end = 16.dp, bottom = 8.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name = group.name, color = colorFromArgb(group.color), size = 36.dp, isGroup = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${group.members} of ${group.maxMembers} members", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (key in busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else FilledTonalButton(onClick = { onJoin(group) }, enabled = group.members < group.maxMembers) {
                        Text(if (group.members < group.maxMembers) "Join" else "Full")
                    }
                }
            }
        }
    }
}
