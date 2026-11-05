package com.offlinechat.app.data

import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.ChatStore
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.Member
import com.offlinechat.app.domain.MessageStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [ChatStore] for the engine, plus the observable queries the UI needs. */
@Singleton
class RoomChatStore @Inject constructor(private val dao: ChatDao) : ChatStore {

    // ---- observation for the UI

    fun observeConversations(): Flow<List<Conversation>> = dao.observeConversations().map { list -> list.map { it.toDomain() } }

    fun observeConversation(id: Long): Flow<Conversation?> = dao.observeConversation(id).map { it?.toDomain() }

    /** Messages oldest-first, limited to the newest [limit]. */
    fun observeMessages(id: Long, limit: Int): Flow<List<ChatMessage>> =
        dao.observeMessagesDesc(id, limit).map { list -> list.asReversed().map { it.toDomain() } }

    fun observeMembers(groupId: Long): Flow<List<Member>> = dao.observeMembers(groupId).map { list -> list.map { it.toDomain() } }

    fun observeFiles(): Flow<List<ChatMessage>> = dao.observeFiles().map { list -> list.map { it.toDomain() } }

    fun observeTotalUnread(): Flow<Int> = dao.observeTotalUnread()

    // ---- ChatStore

    override suspend fun getConversation(id: Long) = dao.getConversation(id)?.toDomain()
    override suspend fun conversations() = dao.conversations().map { it.toDomain() }
    override suspend fun upsertConversation(c: Conversation) = dao.upsertConversation(c.toEntity())
    override suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    override suspend fun insertMessage(m: ChatMessage): Boolean = dao.insertMessage(m.toEntity()) != -1L
    override suspend fun getMessage(id: Long) = dao.getMessage(id)?.toDomain()
    override suspend fun updateStatus(id: Long, status: MessageStatus) = dao.updateStatus(id, status.name)
    override suspend fun markSequenced(id: Long, seq: Long, status: MessageStatus) = dao.markSequenced(id, seq, status.name)
    override suspend fun completeFile(id: Long, path: String?, status: MessageStatus) = dao.completeFile(id, path, status.name)
    override suspend fun pendingOutgoing(conversationId: Long) = dao.pendingOutgoing(conversationId).map { it.toDomain() }
    override suspend fun maxSeq(conversationId: Long) = dao.maxSeq(conversationId)
    override suspend fun recentSequenced(conversationId: Long, limit: Int) =
        dao.recentSequencedDesc(conversationId, limit).asReversed().map { it.toDomain() }

    override suspend fun recordActivity(conversationId: Long, preview: String, at: Long, unreadDelta: Int) =
        dao.recordActivity(conversationId, preview.take(120), at, unreadDelta)

    override suspend fun clearUnread(conversationId: Long) = dao.clearUnread(conversationId)

    override suspend fun members(groupId: Long) = dao.members(groupId).map { it.toDomain() }
    override suspend fun replaceMembers(groupId: Long, members: List<Member>) = dao.replaceMembers(groupId, members.map { it.toEntity() })
    override suspend fun upsertMember(m: Member) = dao.upsertMember(m.toEntity())
    override suspend fun removeMember(groupId: Long, peerId: Long) = dao.removeMember(groupId, peerId)
}
