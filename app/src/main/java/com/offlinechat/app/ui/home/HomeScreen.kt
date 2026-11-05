package com.offlinechat.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinechat.app.ui.BluetoothActions
import com.offlinechat.app.ui.BluetoothViewModel
import com.offlinechat.app.ui.MainViewModel
import com.offlinechat.app.ui.SetupBanner
import com.offlinechat.app.ui.chatlist.ChatListScreen
import com.offlinechat.app.ui.chatlist.ChatListViewModel
import com.offlinechat.app.ui.files.ReceivedFilesScreen
import com.offlinechat.app.ui.nearby.NearbyScreen
import com.offlinechat.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    Chats("Chats", Icons.AutoMirrored.Filled.Chat),
    Nearby("Nearby", Icons.Filled.BluetoothSearching),
    Files("Files", Icons.Filled.FolderOpen),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun HomeScreen(
    permissionsGranted: Boolean,
    actions: BluetoothActions,
    onOpenChat: (Long) -> Unit,
    onCreateGroup: () -> Unit,
    mainViewModel: MainViewModel = hiltViewModel(),
    bluetoothViewModel: BluetoothViewModel = hiltViewModel(),
    chatListViewModel: ChatListViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val bluetoothOn by bluetoothViewModel.enabled.collectAsStateWithLifecycle()
    val unread by chatListViewModel.totalUnread.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { text -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }

    LaunchedEffect(mainViewModel) { mainViewModel.notices.collect { showMessage(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            if (t == Tab.Chats && unread > 0) {
                                BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                                    Icon(t.icon, contentDescription = t.label)
                                }
                            } else {
                                Icon(t.icon, contentDescription = t.label)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        // While the setup banner is showing it sits at the very top, so it takes the status bar inset itself;
        // otherwise each screen's top app bar does.
        val bannerVisible = !(permissionsGranted && bluetoothOn)
        Column(Modifier.fillMaxSize().padding(padding).then(if (bannerVisible) Modifier.statusBarsPadding() else Modifier)) {
            SetupBanner(permissionsGranted, bluetoothOn, actions)
            when (Tab.entries[tab]) {
                Tab.Chats -> ChatListScreen(onOpenChat = onOpenChat, onCreateGroup = onCreateGroup, onFindPeople = { tab = Tab.Nearby.ordinal }, viewModel = chatListViewModel)
                Tab.Nearby -> NearbyScreen(
                    ready = permissionsGranted && bluetoothOn,
                    actions = actions,
                    onOpenChat = onOpenChat,
                    showMessage = showMessage,
                )
                Tab.Files -> ReceivedFilesScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}
