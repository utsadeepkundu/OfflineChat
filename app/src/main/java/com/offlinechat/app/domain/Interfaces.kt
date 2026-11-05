package com.offlinechat.app.domain

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope

/** Persistence port. Implemented with Room on Android and in memory in tests. */
interface ChatStore {
    suspend fun getConversation(id: Long): Conversation?
    suspend fun conversations(): List<Conversation>
    suspend fun upsertConversation(c: Conversation)
    suspend fun deleteConversation(id: Long)

    /** Returns false when a message with this id already exists (duplicate). */
    suspend fun insertMessage(m: ChatMessage): Boolean
    suspend fun getMessage(id: Long): ChatMessage?
    suspend fun updateStatus(id: Long, status: MessageStatus)
    suspend fun markSequenced(id: Long, seq: Long, status: MessageStatus)
    suspend fun completeFile(id: Long, path: String?, status: MessageStatus)
    /** Own text messages that have not been acknowledged yet (sending, sent but un-acked, or failed), oldest first. */
    suspend fun pendingOutgoing(conversationId: Long): List<ChatMessage>
    suspend fun maxSeq(conversationId: Long): Long
    suspend fun recentSequenced(conversationId: Long, limit: Int): List<ChatMessage>

    suspend fun recordActivity(conversationId: Long, preview: String, at: Long, unreadDelta: Int)
    suspend fun clearUnread(conversationId: Long)

    suspend fun members(groupId: Long): List<Member>
    suspend fun replaceMembers(groupId: Long, members: List<Member>)
    suspend fun upsertMember(m: Member)
    suspend fun removeMember(groupId: Long, peerId: Long)
}

interface RawConnection : Closeable {
    val remoteAddress: String
    val secure: Boolean
    val input: InputStream
    val output: OutputStream
}

/** Byte-pipe port. The Android implementation is Bluetooth RFCOMM. */
interface Transport {
    fun startListening(scope: CoroutineScope, onIncoming: (RawConnection) -> Unit)
    fun stopListening()

    /** Throws java.io.IOException when the peer cannot be reached. */
    suspend fun connect(address: String, secure: Boolean): RawConnection
}

interface IdentityProvider {
    val selfId: Long
    val selfName: String
}

interface EngineSettings {
    val quickConnect: Boolean
    val historyCount: Int
    val autoAcceptJoins: Boolean
}
