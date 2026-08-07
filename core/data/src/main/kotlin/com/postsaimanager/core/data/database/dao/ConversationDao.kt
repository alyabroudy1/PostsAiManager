package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.postsaimanager.core.data.database.entity.ConversationEntity
import com.postsaimanager.core.data.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Conversations and their messages.
 *
 * `ConversationEntity` and `MessageEntity` shipped in `@Database` from day one with no DAO,
 * so these two tables have been created on every install and never held a row — AI chat had
 * no way to persist. This closes that gap (task 7.1).
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE documentId = :documentId ORDER BY lastMessageAt DESC")
    fun observeForDocument(documentId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Keeps the denormalised `messageCount` / `lastMessageAt` columns in step with the
     * messages table. Called inside [insertMessageAndTouchConversation] so the two writes
     * cannot drift apart.
     */
    @Query(
        """
        UPDATE conversations
        SET messageCount = (SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId),
            lastMessageAt = :timestamp
        WHERE id = :conversationId
        """,
    )
    suspend fun touch(conversationId: String, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    /**
     * Appends a message and refreshes the conversation summary atomically.
     *
     * Without the transaction a crash between the two writes leaves `messageCount` wrong
     * forever — there is no reconciliation pass anywhere in the app.
     */
    @Transaction
    suspend fun insertMessageAndTouchConversation(message: MessageEntity) {
        insertMessage(message)
        touch(message.conversationId, message.createdAt)
    }
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)
}
