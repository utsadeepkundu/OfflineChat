package com.offlinechat.app.testutil

import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.ChatStore
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.domain.Member
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.domain.MessageStatus

/** Thread-safe in-memory [ChatStore] with the same semantics as the Room implementation. */
class InMemoryStore : ChatStore {
    private val lock = Any()
    private val conversations = LinkedHashMap<Long, Conversation>()
    private val messages = LinkedHashMap<Long, ChatMessage>()
    private val members = LinkedHashMap<Long, MutableList<Member>>()

    override suspend fun getConversation(id: Long) = synchronized(lock) { conversations[id] }
    override suspend fun conversations() = synchronized(lock) { conversations.values.toList() }
    override suspend fun upsertConversation(c: Conversation) = synchronized(lock) { conversations[c.id] = c; Unit }

    override suspend fun deleteConversation(id: Long) = synchronized(lock) {
        conversations.remove(id)
        members.remove(id)
        messages.values.removeAll { it.conversationId == id }
        Unit
    }

    override suspend fun insertMessage(m: ChatMessage): Boolean = synchronized(lock) {
        if (messages.containsKey(m.id)) false else { messages[m.id] = m; true }
    }

    override suspend fun getMessage(id: Long) = synchronized(lock) { messages[id] }

    override suspend fun updateStatus(id: Long, status: MessageStatus) = synchronized(lock) {
        val m = messages[id] ?: return@synchronized
        if (m.status != MessageStatus.DELIVERED) messages[id] = m.copy(status = status)
    }

    override suspend fun markSequenced(id: Long, seq: Long, status: MessageStatus) = synchronized(lock) {
        val m = messages[id] ?: return@synchronized
        messages[id] = m.copy(seq = seq, sortKey = seq * 1000, status = status)
    }

    override suspend fun completeFile(id: Long, path: String?, status: MessageStatus) = synchronized(lock) {
        val m = messages[id] ?: return@synchronized
        messages[id] = m.copy(filePath = path ?: m.filePath, status = status)
    }

    override suspend fun pendingOutgoing(conversationId: Long) = synchronized(lock) {
        messages.values.filter {
            it.conversationId == conversationId && it.outgoing && it.kind == MessageKind.TEXT &&
                it.status != MessageStatus.DELIVERED
        }.sortedBy { it.timestamp }
    }

    override suspend fun maxSeq(conversationId: Long) = synchronized(lock) {
        messages.values.filter { it.conversationId == conversationId }.maxOfOrNull { it.seq } ?: 0L
    }

    override suspend fun recentSequenced(conversationId: Long, limit: Int) = synchronized(lock) {
        messages.values.filter { it.conversationId == conversationId && it.seq > 0 }
            .sortedByDescending { it.seq }.take(limit).sortedBy { it.seq }
    }

    override suspend fun recordActivity(conversationId: Long, preview: String, at: Long, unreadDelta: Int) = synchronized(lock) {
        val c = conversations[conversationId] ?: return@synchronized
        conversations[conversationId] = c.copy(lastPreview = preview, lastMessageAt = maxOf(c.lastMessageAt, at), unread = c.unread + unreadDelta)
    }

    override suspend fun clearUnread(conversationId: Long) = synchronized(lock) {
        val c = conversations[conversationId] ?: return@synchronized
        conversations[conversationId] = c.copy(unread = 0)
    }

    override suspend fun members(groupId: Long) = synchronized(lock) { members[groupId].orEmpty().sortedBy { it.joinOrder } }

    override suspend fun replaceMembers(groupId: Long, members: List<Member>) = synchronized(lock) {
        this.members[groupId] = members.toMutableList(); Unit
    }

    override suspend fun upsertMember(m: Member) = synchronized(lock) {
        val list = members.getOrPut(m.groupId) { mutableListOf() }
        list.removeAll { it.peerId == m.peerId }
        list.add(m)
        Unit
    }

    override suspend fun removeMember(groupId: Long, peerId: Long) = synchronized(lock) {
        members[groupId]?.removeAll { it.peerId == peerId }; Unit
    }

    // Test conveniences
    fun memberList(groupId: Long): List<Member> = synchronized(lock) { members[groupId].orEmpty().sortedBy { it.joinOrder } }

    fun texts(conversationId: Long): List<String> = synchronized(lock) {
        messages.values.filter { it.conversationId == conversationId && it.kind == MessageKind.TEXT }
            .sortedWith(compareBy({ it.sortKey }, { it.timestamp }, { it.id })).map { it.text }
    }

    fun all(conversationId: Long): List<ChatMessage> = synchronized(lock) {
        messages.values.filter { it.conversationId == conversationId }
            .sortedWith(compareBy({ it.sortKey }, { it.timestamp }, { it.id }))
    }

    fun groupConversations(): List<Conversation> = synchronized(lock) { conversations.values.filter { it.type == ConversationType.GROUP } }
}
