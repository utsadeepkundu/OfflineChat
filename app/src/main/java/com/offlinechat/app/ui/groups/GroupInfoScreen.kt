package com.offlinechat.app.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offlinechat.app.data.AppPreferences
import com.offlinechat.app.data.RoomChatStore
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.Member
import com.offlinechat.app.engine.ChatEngine
import com.offlinechat.app.ui.BluetoothActions
import com.offlinechat.app.ui.components.Avatar
import com.offlinechat.app.ui.components.SectionHeader
import com.offlinechat.app.ui.theme.avatarColor
import com.offlinechat.app.ui.theme.colorFromArgb
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GroupInfoState(
    val group: Conversation,
    val members: List<MemberRow>,
    val selfId: Long,
)

data class MemberRow(val member: Member, val online: Boolean, val isHost: Boolean, val isSelf: Boolean)

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: RoomChatStore,
    private val engine: ChatEngine,
    preferences: AppPreferences,
) : ViewModel() {
    private val id: Long = savedStateHandle.get<Long>("id") ?: 0L
    private val selfId = preferences.selfId

    /** Null until loaded or once the group no longer exists. */
    val state: StateFlow<GroupInfoState?> = combine(
        store.observeConversation(id),
        store.observeMembers(id),
        engine.onlineMembers,
        engine.connectedPeers,
    ) { group, members, online, connected ->
        if (group == null) null
        else {
            val onlineSet = online[id].orEmpty()
            GroupInfoState(
                group = group,
                selfId = selfId,
                members = members.map {
                    MemberRow(
                        member = it,
                        online = it.peerId == selfId || it.peerId in onlineSet || (!group.isHost && it.peerId == group.hostId && it.peerId in connected),
                        isHost = it.peerId == group.hostId,
                        isSelf = it.peerId == selfId,
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun rename(name: String) {
        viewModelScope.launch { engine.renameGroup(id, name) }
    }

    fun leave(onDone: () -> Unit) {
        viewModelScope.launch {
            engine.leaveConversation(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    actions: BluetoothActions,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    viewModel: GroupInfoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Group info") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        val s = state ?: return@Column
        val group = s.group
        LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(name = group.title, color = colorFromArgb(group.color), size = 88.dp, isGroup = true)
                    Spacer(Modifier.padding(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (group.isHost && !group.ended) {
                            IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename group") }
                        }
                    }
                    Text(
                        if (group.ended) "This group has ended" else "${s.members.size} of ${group.maxMembers} people",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (group.isHost && !group.ended) {
                        Spacer(Modifier.padding(8.dp))
                        FilledTonalButton(onClick = actions.requestVisible) {
                            Icon(Icons.Filled.Visibility, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Make me visible so people can join")
                        }
                    }
                }
            }
            item(key = "members-header") { SectionHeader("Members") }
            items(s.members, key = { it.member.peerId }) { row -> MemberItem(row) }
            item(key = "leave") {
                OutlinedButton(
                    onClick = { confirmLeave = true },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (group.isHost) "Hand over and leave group" else "Leave group")
                }
            }
        }

        if (renaming) {
            var text by remember { mutableStateOf(group.title) }
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text("Rename group") },
                text = {
                    OutlinedTextField(value = text, onValueChange = { if (it.length <= 40) text = it }, singleLine = true)
                },
                confirmButton = {
                    TextButton(enabled = text.isNotBlank(), onClick = { viewModel.rename(text); renaming = false }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            )
        }
        if (confirmLeave) {
            AlertDialog(
                onDismissRequest = { confirmLeave = false },
                title = { Text("Leave \"${group.title}\"?") },
                text = {
                    Text(
                        if (group.isHost && s.members.size > 1) "Another member becomes the host so the group keeps working. Your copy of the chat is removed from this phone."
                        else "Your copy of the chat is removed from this phone.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmLeave = false; viewModel.leave(onLeft) }) { Text("Leave") }
                },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
            )
        }
    }
}

@Composable
private fun MemberItem(row: MemberRow) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = row.member.name, color = avatarColor(row.member.peerId), size = 44.dp, online = row.online)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (row.isSelf) "${row.member.name} (you)" else row.member.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (row.online) "Online" else "Out of range",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.isHost) AssistChip(onClick = {}, label = { Text("Host") })
    }
}
