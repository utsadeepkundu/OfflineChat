package com.offlinechat.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.domain.Member
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.domain.MessageStatus

// Enums are stored as plain strings: readable in a database browser and immune to reordering.

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: Long,
    val type: String,
    val title: String,
    val peerAddress: String,
    val hostId: Long,
    val color: Int,
    val isHost: Boolean,
    val maxMembers: Int,
    val ended: Boolean,
    val lastMessageAt: Long,
    val lastPreview: String,
    val unread: Int,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "sortKey"]), Index(value = ["kind"])],
)
data class MessageEntity(
    @PrimaryKey val id: Long,
    val conversationId: Long,
    val senderId: Long,
    val senderName: String,
    val kind: String,
    val text: String,
    val fileName: String?,
    val fileMime: String?,
    val fileSize: Long,
    val filePath: String?,
    val status: String,
    val outgoing: Boolean,
    val seq: Long,
    val sortKey: Long,
    val timestamp: Long,
)

@Entity(tableName = "members", primaryKeys = ["groupId", "peerId"])
data class MemberEntity(
    val groupId: Long,
    val peerId: Long,
    val name: String,
    val address: String,
    val joinOrder: Int,
)

internal fun ConversationEntity.toDomain() = Conversation(
    id = id,
    type = ConversationType.valueOf(type),
    title = title,
    peerAddress = peerAddress,
    hostId = hostId,
    color = color,
    isHost = isHost,
    maxMembers = maxMembers,
    ended = ended,
    lastMessageAt = lastMessageAt,
    lastPreview = lastPreview,
    unread = unread,
)

internal fun Conversation.toEntity() = ConversationEntity(
    id = id,
    type = type.name,
    title = title,
    peerAddress = peerAddress,
    hostId = hostId,
    color = color,
    isHost = isHost,
    maxMembers = maxMembers,
    ended = ended,
    lastMessageAt = lastMessageAt,
    lastPreview = lastPreview,
    unread = unread,
)

internal fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    senderName = senderName,
    kind = MessageKind.valueOf(kind),
    text = text,
    fileName = fileName,
    fileMime = fileMime,
    fileSize = fileSize,
    filePath = filePath,
    status = MessageStatus.valueOf(status),
    outgoing = outgoing,
    seq = seq,
    sortKey = sortKey,
    timestamp = timestamp,
)

internal fun ChatMessage.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    senderName = senderName,
    kind = kind.name,
    text = text,
    fileName = fileName,
    fileMime = fileMime,
    fileSize = fileSize,
    filePath = filePath,
    status = status.name,
    outgoing = outgoing,
    seq = seq,
    sortKey = sortKey,
    timestamp = timestamp,
)

internal fun MemberEntity.toDomain() = Member(groupId, peerId, name, address, joinOrder)

internal fun Member.toEntity() = MemberEntity(groupId, peerId, name, address, joinOrder)
