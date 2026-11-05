package com.offlinechat.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinechat.app.core.Protocol
import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.domain.MessageStatus
import com.offlinechat.app.ui.components.Avatar
import com.offlinechat.app.ui.components.ConnectionBanner
import com.offlinechat.app.ui.components.FileActions
import com.offlinechat.app.ui.components.LinkState
import com.offlinechat.app.ui.components.TypingDots
import com.offlinechat.app.ui.theme.avatarColor
import com.offlinechat.app.ui.theme.colorFromArgb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenGroupInfo: (Long) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val settings by viewModel.preferences.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.onOpen()
        onDispose { viewModel.onClose() }
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }

    val listState = rememberLazyListState()
    // Follow new messages, but never yank the list while the reader is looking at older ones.
    LaunchedEffect(items.firstOrNull()?.key) {
        if (items.isNotEmpty() && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (items.isNotEmpty() && last >= items.size - 6) viewModel.loadOlder() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding(),
    ) {
        TopAppBar(
            title = {
                val h = header
                if (h != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            name = h.title,
                            color = if (h.isGroup) colorFromArgb(h.color) else avatarColor(viewModel.convId),
                            size = 40.dp,
                            isGroup = h.isGroup,
                            online = if (h.isGroup) null else h.link == LinkState.CONNECTED,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(h.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = h.typing ?: h.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (h.typing != null || h.link == LinkState.CONNECTED) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                val h = header
                if (h != null && h.isGroup) {
                    IconButton(onClick = { onOpenGroupInfo(h.id) }) {
                        Icon(Icons.Filled.Info, contentDescription = "Group info")
                    }
                } else if (h != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete chat") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.deleteChat(onDone = onBack)
                                },
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
        ConnectionBanner(header?.link ?: LinkState.CONNECTED)

        Box(Modifier.weight(1f)) {
            if (items.isEmpty()) {
                Text(
                    "No messages yet. Say hello!",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            ) {
                items(items, key = { it.key }, contentType = { it.contentType }) { item ->
                    when (item) {
                        is ChatItem.DayHeader -> DayChip(item.label)
                        is ChatItem.Notice -> NoticeChip(item.text)
                        is ChatItem.Message -> MessageRow(
                            item = item,
                            isGroup = header?.isGroup == true,
                            transfer = transfers[item.message.id],
                            onClick = { m -> handleTap(m, viewModel, context) },
                            onLongClick = { m ->
                                if (m.kind == MessageKind.TEXT) {
                                    clipboard.setText(AnnotatedString(m.text))
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onCancel = viewModel::cancelTransfer,
                        )
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter)) {
                SnackbarHost(snackbar)
            }
        }

        AnimatedVisibility(
            visible = header?.typing != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TypingDots(MaterialTheme.colorScheme.primary)
                Text(
                    header?.typing.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MessageInput(
            value = input,
            onValueChange = {
                input = it
                viewModel.onInputChanged(it)
            },
            enabled = header?.canSend != false,
            fileLimitMb = if (header?.isGroup == true) Protocol.MAX_GROUP_FILE_BYTES / (1024 * 1024) else Protocol.MAX_DIRECT_FILE_BYTES / (1024 * 1024),
            onPickPhoto = {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickFile = { pickFile.launch("*/*") },
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    viewModel.send(text)
                    input = ""
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

private fun handleTap(m: ChatMessage, viewModel: ChatViewModel, context: android.content.Context) {
    when {
        m.status == MessageStatus.FAILED && m.outgoing -> viewModel.retry(m)
        m.kind == MessageKind.FILE && m.filePath != null && m.status != MessageStatus.SENDING ->
            FileActions.open(context, m.filePath, m.fileMime)
    }
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    fileLimitMb: Long,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var attachOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(onClick = { attachOpen = true }, enabled = enabled) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file")
                }
                DropdownMenu(expanded = attachOpen, onDismissRequest = { attachOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Photo or video") },
                        leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                        onClick = { attachOpen = false; onPickPhoto() },
                    )
                    DropdownMenuItem(
                        text = { Text("File (up to $fileLimitMb MB)") },
                        leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                        onClick = { attachOpen = false; onPickFile() },
                    )
                }
            }
            TextField(
                value = value,
                onValueChange = { if (it.length <= Protocol.MAX_TEXT_CHARS) onValueChange(it) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(if (enabled) "Message" else "This group has ended") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions(),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
            Spacer(Modifier.width(8.dp))
            val canSend = enabled && value.isNotBlank()
            Surface(
                onClick = onSend,
                enabled = canSend,
                shape = CircleShape,
                color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
