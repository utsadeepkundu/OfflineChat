package com.offlinechat.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinechat.app.data.AppPreferences
import com.offlinechat.app.ui.BluetoothActions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val preferences: AppPreferences) : ViewModel() {
    val initialName: String get() = preferences.settings.value.displayName
    fun finish(name: String) = preferences.update { it.copy(displayName = name.ifBlank { it.displayName }, onboardingDone = true) }
}

@Composable
fun OnboardingScreen(
    permissionsGranted: Boolean,
    actions: BluetoothActions,
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var name by remember { mutableStateOf(viewModel.initialName) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("OfflineChat", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Chat with people nearby using only Bluetooth. No internet, no mobile network, no account.",
                style = MaterialTheme.typography.titleMedium,
            )
            Feature(Icons.Filled.WifiOff, "Works anywhere", "Mountains, ships, power cuts. If phones can see each other, they can talk.")
            Feature(Icons.Filled.Group, "Groups of up to 7", "Everyone can write at the same time. One phone hosts the group.")
            Feature(Icons.Filled.Forum, "Photos and small files", "Send pictures and documents right inside the chat.")
            Feature(Icons.Filled.Lock, "Stays on your phone", "There are no servers. Your chats are stored only on the phones in them.")
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                label = { Text("Your name") },
                supportingText = { Text("People nearby will see this") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (!permissionsGranted) {
            Text(
                "To find and talk to nearby phones, Android asks for Bluetooth (\"Nearby devices\") permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                if (!permissionsGranted) actions.requestPermissions()
                else {
                    viewModel.finish(name)
                    onDone()
                }
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (permissionsGranted) "Get started" else "Allow Bluetooth access")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
