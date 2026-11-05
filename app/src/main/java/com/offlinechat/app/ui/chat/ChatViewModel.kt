package com.offlinechat.app.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.app.core.Protocol
import com.offlinechat.app.data.AppPreferences
import com.offlinechat.app.data.FileImporter
import com.offlinechat.app.data.RoomChatStore
import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.domain.TransferState
import com.offlinechat.app.engine.ChatEngine
import com.offlinechat.app.service.NotificationHelper
import com.offlinechat.app.ui.components.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatHeader(
    val id: Long,
    val title: String,
    val subtitle: String,
    val color: Int,
    val isGroup: Boolean,
    val isHost: Boolean,
    val link: LinkState,
    val canSend: Boolean,
    val typing: String?,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: ChatEngine,
    private val store: RoomChatStore,
    private val importer: FileImporter,
    val preferences: AppPreferences,
    private val notifications: NotificationHelper,
) : ViewModel() {

    val convId: Long = savedStateHandle.get<Long>("id") ?: 0L

    private val limit = MutableStateFlow(PAGE_SIZE)
    private val loaded = MutableStateFlow(0)

    /** Newest first, ready for a reversed LazyColumn. Built off the main thread. */
    val items: StateFlow<List<ChatItem>> = combine(
        limit.flatMapLatest { store.observeMessages(convId, it) }.onEach { loaded.value = it.size },
        store.observeConversation(convId).map { it?.type == ConversationType.GROUP },
    ) { messages, isGroup -> ChatItems.build(messages, isGroup) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val header: StateFlow<ChatHeader?> = combine(
        store.observeConversation(convId),
        engine.connectedPeers,
        engine.onlineMembers,
        engine.reconnecting,
        engine.typing,
    ) { conv, connected, online, reconnecting, typing ->
        if (conv == null) return@combine null
        val isGroup = conv.type == ConversationType.GROUP
        val peer = if (isGroup) conv.hostId else conv.id
        val link = when {
            conv.ended -> LinkState.ENDED
            isGroup && conv.isHost -> LinkState.CONNECTED
            peer in connected -> LinkState.CONNECTED
            conv.id in reconnecting -> LinkState.CONNECTING
            else -> LinkState.OFFLINE
        }
        val typingNames = typing[conv.id].orEmpty()
        val typingText = when (typingNames.size) {
            0 -> null
            1 -> "${typingNames[0]} is typing"
            else -> "${typingNames.joinToString(", ")} are typing"
        }
        val subtitle = when {
            conv.ended -> "Group ended"
            isGroup -> {
                val onlineCount = (online[conv.id]?.size ?: 0).coerceAtLeast(if (conv.isHost) 1 else 0)
                val role = if (conv.isHost) " · you are the host" else ""
                "$onlineCount online$role"
            }
            link == LinkState.CONNECTED -> "Connected over Bluetooth"
            link == LinkState.CONNECTING -> "Reconnecting…"
            else -> "Out of range"
        }
        ChatHeader(
            id = conv.id,
            title = conv.title,
            subtitle = subtitle,
            color = conv.color,
            isGroup = isGroup,
            isHost = conv.isHost,
            link = link,
            canSend = !conv.ended,
            typing = typingText,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transfers: StateFlow<Map<Long, TransferState>> = engine.transfers

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off texts for a snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var typingOffJob: Job? = null
    private var lastTypingSentAt = 0L

    fun onOpen() {
        engine.openConversation(convId)
        notifications.cancelConversation(convId)
    }

    fun onClose() {
        engine.closeConversation(convId)
        typingOffJob?.cancel()
        viewModelScope.launch { engine.setTyping(convId, false) }
    }

    /** Loads another page of history when the user scrolls near the top. */
    fun loadOlder() {
        if (loaded.value >= limit.value) limit.update { it + PAGE_SIZE }
    }

    fun send(text: String) {
        typingOffJob?.cancel()
        lastTypingSentAt = 0L
        viewModelScope.launch {
            engine.setTyping(convId, false)
            engine.sendText(convId, text)
        }
    }

    /** Sends a "typing" signal at most every few seconds and a "stopped" signal after a pause. */
    fun onInputChanged(text: String) {
        if (text.isBlank()) {
            typingOffJob?.cancel()
            if (lastTypingSentAt != 0L) {
                lastTypingSentAt = 0L
                viewModelScope.launch { engine.setTyping(convId, false) }
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt > TYPING_REFRESH_MS) {
            lastTypingSentAt = now
            viewModelScope.launch { engine.setTyping(convId, true) }
        }
        typingOffJob?.cancel()
        typingOffJob = viewModelScope.launch {
            delay(TYPING_IDLE_MS)
            lastTypingSentAt = 0L
            engine.setTyping(convId, false)
        }
    }

    fun attach(uri: Uri) {
        viewModelScope.launch {
            val conv = store.getConversation(convId) ?: return@launch
            val limitBytes = if (conv.type == ConversationType.GROUP) Protocol.MAX_GROUP_FILE_BYTES else Protocol.MAX_DIRECT_FILE_BYTES
            when (val result = importer.import(uri, limitBytes)) {
                is FileImporter.Result.Ok -> engine.sendFile(convId, result.file)
                is FileImporter.Result.TooLarge ->
                    _messages.emit("That file is bigger than ${limitBytes / (1024 * 1024)} MB, the limit in this chat.")
                is FileImporter.Result.Failed -> _messages.emit(result.message)
            }
        }
    }

    fun retry(message: ChatMessage) {
        viewModelScope.launch { engine.retryMessage(message.id) }
    }

    fun cancelTransfer(message: ChatMessage) = engine.cancelTransfer(message.id)

    fun deleteChat(onDone: () -> Unit) {
        viewModelScope.launch {
            engine.leaveConversation(convId)
            onDone()
        }
    }

    private companion object {
        const val PAGE_SIZE = 120
        const val TYPING_REFRESH_MS = 2_500L
        const val TYPING_IDLE_MS = 3_000L
    }
}
