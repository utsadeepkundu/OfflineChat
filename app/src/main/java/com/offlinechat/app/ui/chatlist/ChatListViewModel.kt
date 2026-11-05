package com.offlinechat.app.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.app.data.RoomChatStore
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.engine.ChatEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationRow(
    val conversation: Conversation,
    val connected: Boolean,
    val onlineCount: Int,
    val typing: String?,
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val store: RoomChatStore,
    private val engine: ChatEngine,
) : ViewModel() {

    /** Null while the first database read is still running. */
    val rows: StateFlow<List<ConversationRow>?> = combine(
        store.observeConversations(),
        engine.connectedPeers,
        engine.onlineMembers,
        engine.typing,
    ) { conversations, connected, online, typing ->
        conversations.map { c ->
            val isGroup = c.type == ConversationType.GROUP
            ConversationRow(
                conversation = c,
                connected = when {
                    c.ended -> false
                    isGroup && c.isHost -> true
                    isGroup -> c.hostId in connected
                    else -> c.id in connected
                },
                onlineCount = if (isGroup) (online[c.id]?.size ?: 0) else 0,
                typing = typing[c.id]?.firstOrNull()?.let { "$it is typing" },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalUnread: StateFlow<Int> = store.observeTotalUnread()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun delete(conversation: Conversation) {
        viewModelScope.launch { engine.leaveConversation(conversation.id) }
    }
}
