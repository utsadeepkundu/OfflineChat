package com.offlinechat.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChatDao {

    // ---- conversations

    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    abstract fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract fun observeConversation(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations")
    abstract suspend fun conversations(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertConversation(c: ConversationEntity)

    @Query(
        "UPDATE conversations SET " +
            "lastPreview = CASE WHEN :at >= lastMessageAt THEN :preview ELSE lastPreview END, " +
            "lastMessageAt = MAX(lastMessageAt, :at), " +
            "unread = unread + :delta WHERE id = :id",
    )
    abstract suspend fun recordActivity(id: Long, preview: String, at: Long, delta: Int)

    @Query("UPDATE conversations SET unread = 0 WHERE id = :id")
    abstract suspend fun clearUnread(id: Long)

    @Query("SELECT COALESCE(SUM(unread), 0) FROM conversations")
    abstract fun observeTotalUnread(): Flow<Int>

    @Query("DELETE FROM messages WHERE conversationId = :id")
    abstract suspend fun deleteMessages(id: Long)

    @Query("DELETE FROM members WHERE groupId = :id")
    abstract suspend fun deleteMembers(id: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun deleteConversationRow(id: Long)

    @Transaction
    open suspend fun deleteConversation(id: Long) {
        deleteMessages(id)
        deleteMembers(id)
        deleteConversationRow(id)
    }

    // ---- messages

    /** Newest first; the UI list is reversed, which lets a `reverseLayout` LazyColumn stick to the bottom for free. */
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY sortKey DESC, timestamp DESC, id DESC LIMIT :limit")
    abstract fun observeMessagesDesc(id: Long, limit: Int): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMessage(m: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun getMessage(id: Long): MessageEntity?

    /** A delivered message never goes backwards, even if a late "sent" update arrives after the acknowledgement. */
    @Query("UPDATE messages SET status = :status WHERE id = :id AND status != 'DELIVERED'")
    abstract suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE messages SET seq = :seq, sortKey = :seq * 1000, status = :status WHERE id = :id")
    abstract suspend fun markSequenced(id: Long, seq: Long, status: String)

    @Query("UPDATE messages SET filePath = COALESCE(:path, filePath), status = :status WHERE id = :id")
    abstract suspend fun completeFile(id: Long, path: String?, status: String)

    @Query(
        "SELECT * FROM messages WHERE conversationId = :c AND outgoing = 1 AND kind = 'TEXT' AND status != 'DELIVERED' " +
            "ORDER BY timestamp ASC, rowid ASC",
    )
    abstract suspend fun pendingOutgoing(c: Long): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversationId = :c")
    abstract suspend fun maxSeq(c: Long): Long

    @Query("SELECT * FROM messages WHERE conversationId = :c AND seq > 0 ORDER BY seq DESC LIMIT :limit")
    abstract suspend fun recentSequencedDesc(c: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE kind = 'FILE' AND filePath IS NOT NULL ORDER BY timestamp DESC")
    abstract fun observeFiles(): Flow<List<MessageEntity>>

    // ---- members

    @Query("SELECT * FROM members WHERE groupId = :g ORDER BY joinOrder ASC")
    abstract suspend fun members(g: Long): List<MemberEntity>

    @Query("SELECT * FROM members WHERE groupId = :g ORDER BY joinOrder ASC")
    abstract fun observeMembers(g: Long): Flow<List<MemberEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMember(m: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMembers(list: List<MemberEntity>)

    @Query("DELETE FROM members WHERE groupId = :g AND peerId = :p")
    abstract suspend fun removeMember(g: Long, p: Long)

    @Transaction
    open suspend fun replaceMembers(g: Long, list: List<MemberEntity>) {
        deleteMembers(g)
        insertMembers(list)
    }
}
