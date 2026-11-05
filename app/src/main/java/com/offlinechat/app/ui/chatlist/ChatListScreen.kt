package com.offlinechat.app.ui.chatlist

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.ui.components.Avatar
import com.offlinechat.app.ui.components.EmptyState
import com.offlinechat.app.ui.theme.avatarColor
import com.offlinechat.app.ui.theme.colorFromArgb
import com.offlinechat.app.ui.util.listTimeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpenChat: (Long) -> Unit,
    onCreateGroup: () -> Unit,
    onFindPeople: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Conversation?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Chats", style = MaterialTheme.typography.titleLarge) },
            actions = {
                IconButton(onClick = onCreateGroup) {
                    Icon(Icons.Filled.GroupAdd, contentDescription = "Create a group")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Box(Modifier.weight(1f)) {
            val list = rows
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "No chats yet",
                    body = "Find a phone nearby to start chatting, or create a group and let others join. No internet needed.",
                    action = {
                        ExtendedFloatingActionButton(
                            onClick = onFindPeople,
                            icon = { Icon(Icons.Filled.BluetoothSearching, contentDescription = null) },
                            text = { Text("Find people nearby") },
                        )
                    },
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.conversation.id }) { row ->
                        ConversationItem(row, onClick = { onOpenChat(row.conversation.id) }, onLongClick = { toDelete = row.conversation })
                    }
                }
            }
            if (!list.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onFindPeople,
                    icon = { Icon(Icons.Filled.BluetoothSearching, contentDescription = null) },
                    text = { Text("Find people") },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
        }
    }

    toDelete?.let { conversation ->
        val isGroup = conversation.type == ConversationType.GROUP
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(if (isGroup) "Leave \"${conversation.title}\"?" else "Delete this chat?") },
            text = {
                Text(
                    if (isGroup) "You will stop receiving messages from this group and its history is removed from this phone."
                    else "The conversation is removed from this phone. The other person keeps their copy.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(conversation); toDelete = null }) { Text(if (isGroup) "Leave" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(row: ConversationRow, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = row.conversation
    val isGroup = c.type == ConversationType.GROUP
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = c.title,
            color = if (isGroup) colorFromArgb(c.color) else avatarColor(c.id),
            size = 52.dp,
            isGroup = isGroup,
            online = if (isGroup) null else row.connected,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listTimeLabel(c.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val typing = row.typing
                Text(
                    text = typing ?: when {
                        c.ended -> "Group ended"
                        c.lastPreview.isNotBlank() -> c.lastPreview
                        isGroup -> "${row.onlineCount.coerceAtLeast(1)} online"
                        else -> "Say hello"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (typing != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (c.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (c.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            if (c.unread > 99) "99+" else c.unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}
