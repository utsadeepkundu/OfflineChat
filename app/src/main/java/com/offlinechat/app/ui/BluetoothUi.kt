package com.offlinechat.app.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import com.offlinechat.app.bluetooth.BluetoothController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Exposes the Bluetooth controller to composables. */
@HiltViewModel
class BluetoothViewModel @Inject constructor(val controller: BluetoothController) : ViewModel() {
    val enabled: StateFlow<Boolean> = controller.enabled
    val visible: StateFlow<Boolean> = controller.visible
}

class BluetoothActions(
    val requestPermissions: () -> Unit,
    val requestEnable: () -> Unit,
    val requestVisible: () -> Unit,
)

/** True while every permission needed to chat is granted. Re-checked whenever the app comes back to the foreground. */
@Composable
fun rememberPermissionsGranted(controller: BluetoothController): State<Boolean> {
    val state = remember { mutableStateOf(controller.hasBluetoothPermissions()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.value = controller.hasBluetoothPermissions() }
    return state
}

@Composable
fun rememberBluetoothActions(controller: BluetoothController, onPermissionsChanged: () -> Unit = {}): BluetoothActions {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsChanged()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val visibleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    return remember(controller) {
        BluetoothActions(
            requestPermissions = { permissionLauncher.launch(controller.requiredPermissions()) },
            requestEnable = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            requestVisible = {
                visibleLauncher.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
                )
            },
        )
    }
}

/** Shown at the top of the app whenever chatting is not possible yet. */
@Composable
fun SetupBanner(
    permissionsGranted: Boolean,
    bluetoothOn: Boolean,
    actions: BluetoothActions,
    modifier: Modifier = Modifier,
) {
    if (permissionsGranted && bluetoothOn) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (permissionsGranted) Icons.Filled.BluetoothDisabled else Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (!permissionsGranted) "Bluetooth permission needed" else "Bluetooth is off",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (!permissionsGranted) "OfflineChat needs it to find and talk to nearby phones." else "Turn it on to send and receive messages.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = { if (!permissionsGranted) actions.requestPermissions() else actions.requestEnable() }) {
                Text(if (!permissionsGranted) "Allow" else "Turn on")
            }
        }
    }
}
