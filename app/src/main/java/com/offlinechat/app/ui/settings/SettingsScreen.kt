package com.offlinechat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinechat.app.data.AppPreferences
import com.offlinechat.app.data.ThemeMode
import com.offlinechat.app.data.UserSettings
import com.offlinechat.app.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SettingsViewModel @Inject constructor(private val preferences: AppPreferences) : ViewModel() {
    val settings: StateFlow<UserSettings> = preferences.settings
    fun update(change: (UserSettings) -> UserSettings) = preferences.update(change)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(s.displayName) }
    // Save the name shortly after typing stops instead of on every keystroke.
    LaunchedEffect(name) {
        if (name.isNotBlank() && name.trim() != s.displayName) {
            kotlinx.coroutines.delay(600)
            viewModel.update { it.copy(displayName = name) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader("Profile")
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                label = { Text("Your name") },
                supportingText = { Text("Shown to people you chat with") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            SectionHeader("Connection")
            SwitchRow(
                "Quick connect",
                "Connect without pairing. Faster, but messages are not encrypted over the air. Turn off to require pairing.",
                s.quickConnect,
            ) { v -> viewModel.update { it.copy(quickConnect = v) } }
            SwitchRow(
                "Auto-accept join requests",
                "Let anyone nearby join groups you host without asking you first.",
                s.autoAcceptJoins,
            ) { v -> viewModel.update { it.copy(autoAcceptJoins = v) } }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("History for new members: ${s.historyCount} messages", style = MaterialTheme.typography.titleMedium)
                Text(
                    "How many recent messages a person who joins your group receives.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = s.historyCount.toFloat(),
                    onValueChange = { v -> viewModel.update { it.copy(historyCount = (v / 10).toInt() * 10) } },
                    valueRange = 0f..200f,
                    steps = 19,
                )
            }

            SectionHeader("Appearance")
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.themeMode == mode,
                        onClick = { viewModel.update { it.copy(themeMode = mode) } },
                        label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
            SwitchRow("Dynamic colour", "Use colours from your wallpaper (Android 12 and up).", s.dynamicColor) { v ->
                viewModel.update { it.copy(dynamicColor = v) }
            }
            SwitchRow("Haptic feedback", "A light tap when you send a message.", s.haptics) { v -> viewModel.update { it.copy(haptics = v) } }

            SectionHeader("Messages and files")
            SwitchRow("Notifications", "Show a notification for new messages while the app is closed.", s.notifications) { v ->
                viewModel.update { it.copy(notifications = v) }
            }
            SwitchRow("Shrink photos before sending", "Large photos are resized so they send much faster over Bluetooth.", s.compressImages) { v ->
                viewModel.update { it.copy(compressImages = v) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Filled.WifiOff, contentDescription = null)
                    Text(
                        "OfflineChat has no internet permission. It cannot send anything over Wi-Fi or mobile data, only over Bluetooth to phones close to you.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                "Range is about 10 to 30 metres. A group supports up to 7 people.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
