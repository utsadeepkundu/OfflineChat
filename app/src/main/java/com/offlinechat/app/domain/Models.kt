package com.offlinechat.app.domain

import java.io.File

enum class ConversationType { DIRECT, GROUP }
enum class MessageKind { TEXT, FILE, SYSTEM }
enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED }

/**
 * A chat. For direct chats [id] is the peer's id; for groups it is the group id.
 * [peerAddress] is the Bluetooth address of the peer (direct) or of the host (group member).
 */
data class Conversation(
    val id: Long,
    val type: ConversationType,
    val title: String,
    val peerAddress: String = "",
    val hostId: Long = 0L,
    val color: Int = 0,
    val isHost: Boolean = false,
    val maxMembers: Int = 0,
    val ended: Boolean = false,
    val lastMessageAt: Long = 0L,
    val lastPreview: String = "",
    val unread: Int = 0,
)

/**
 * [sortKey] gives every device the same order: direct chats use the receive time, group messages
 * use `seq * 1000` once the host has sequenced them (local-only items sit just after the last one).
 */
data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val senderId: Long,
    val senderName: String,
    val kind: MessageKind,
    val text: String = "",
    val fileName: String? = null,
    val fileMime: String? = null,
    val fileSize: Long = 0L,
    val filePath: String? = null,
    val status: MessageStatus = MessageStatus.DELIVERED,
    val outgoing: Boolean = false,
    val seq: Long = 0L,
    val sortKey: Long = 0L,
    val timestamp: Long = 0L,
)

data class Member(
    val groupId: Long,
    val peerId: Long,
    val name: String,
    val address: String,
    val joinOrder: Int,
)

/** A file already copied into app storage, ready to be sent. */
class ImportedFile(
    val file: File,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: ByteArray,
)

data class TransferState(val messageId: Long, val done: Long, val total: Long, val incoming: Boolean) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

data class JoinRequestItem(
    val requestId: Long,
    val groupId: Long,
    val groupName: String,
    val peerId: Long,
    val name: String,
    val address: String,
)

sealed interface JoinOutcome {
    data class Joined(val groupId: Long) : JoinOutcome
    data class Rejected(val reason: Int) : JoinOutcome
    data class Failed(val message: String) : JoinOutcome
}

sealed interface EngineEvent {
    data class IncomingMessage(val conversation: Conversation, val message: ChatMessage) : EngineEvent
    data class JoinRequested(val request: JoinRequestItem) : EngineEvent
    data class Notice(val text: String) : EngineEvent
}
