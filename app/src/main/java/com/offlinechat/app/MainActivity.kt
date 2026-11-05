package com.offlinechat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinechat.app.service.AppVisibility
import com.offlinechat.app.service.ChatService
import com.offlinechat.app.ui.MainViewModel
import com.offlinechat.app.ui.navigation.AppNav
import com.offlinechat.app.ui.rememberBluetoothActions
import com.offlinechat.app.ui.rememberPermissionsGranted
import com.offlinechat.app.ui.theme.OfflineChatTheme
import com.offlinechat.app.bluetooth.BluetoothController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var bluetooth: BluetoothController

    private val mainViewModel: MainViewModel by viewModels()
    private var requestedConversation by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readRequest(intent)
        setContent {
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            OfflineChatTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(onboardingDone = settings.onboardingDone)
                }
            }
        }
    }

    @Composable
    private fun Root(onboardingDone: Boolean) {
        val granted by rememberPermissionsGranted(bluetooth)
        val actions = rememberBluetoothActions(bluetooth) { startChatService() }

        // The service keeps links alive in the background; start it as soon as Bluetooth access is granted.
        LaunchedEffect(granted, onboardingDone) { if (granted && onboardingDone) startChatService() }

        Box(Modifier.fillMaxSize()) {
            AppNav(
                onboardingDone = onboardingDone,
                permissionsGranted = granted,
                actions = actions,
                requestedConversation = requestedConversation,
                onOpenedRequested = { requestedConversation = null },
            )
            JoinRequestDialog()
        }
    }

    /** Asks the host about the oldest pending join request, wherever in the app they are. */
    @Composable
    private fun JoinRequestDialog(viewModel: MainViewModel = hiltViewModel()) {
        val joins by viewModel.pendingJoins.collectAsStateWithLifecycle()
        val request = joins.firstOrNull() ?: return
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${request.name} wants to join") },
            text = { Text("\"${request.groupName}\" · they are close to you and asked to be added to the group.") },
            confirmButton = { TextButton(onClick = { viewModel.respond(request, true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { viewModel.respond(request, false) }) { Text("Decline") } },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRequest(intent)
    }

    private fun readRequest(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_CONVERSATION_ID, 0L) ?: 0L
        if (id != 0L) requestedConversation = id
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.inForeground = true
        if (mainViewModel.settings.value.onboardingDone) startChatService()
    }

    override fun onStop() {
        AppVisibility.inForeground = false
        super.onStop()
    }

    /** Only starts once Bluetooth access is granted: the foreground service needs it on Android 14+. */
    private fun startChatService() {
        if (!bluetooth.hasBluetoothPermissions() || !mainViewModel.settings.value.onboardingDone) return
        runCatching { ContextCompat.startForegroundService(this, Intent(this, ChatService::class.java)) }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
