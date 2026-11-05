package com.offlinechat.app.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offlinechat.app.data.RoomChatStore
import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.ui.components.EmptyState
import com.offlinechat.app.ui.components.FileActions
import com.offlinechat.app.ui.components.fileIcon
import com.offlinechat.app.ui.util.formatSize
import com.offlinechat.app.ui.util.listTimeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class FilesViewModel @Inject constructor(store: RoomChatStore) : ViewModel() {
    /** Null while loading. */
    val files: StateFlow<List<ChatMessage>?> = store.observeFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedFilesScreen(viewModel: FilesViewModel = hiltViewModel()) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Files", style = MaterialTheme.typography.titleLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        val list = files
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(
                icon = Icons.Filled.FolderOpen,
                title = "No files yet",
                body = "Photos and documents sent in your chats show up here, ready to open or share.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { m ->
                    val path = m.filePath ?: return@items
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { FileActions.open(context, path, m.fileMime) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(fileIcon(m.fileMime, m.fileName), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.fileName ?: File(path).name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${formatSize(m.fileSize)} · ${if (m.outgoing) "Sent" else "From ${m.senderName}"} · ${listTimeLabel(m.timestamp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { FileActions.share(context, path, m.fileMime) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share ${m.fileName.orEmpty()}")
                        }
                    }
                }
            }
        }
    }
}
