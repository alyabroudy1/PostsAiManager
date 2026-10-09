package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.postsaimanager.core.data.database.entity.ConversationEntity
import com.postsaimanager.core.data.database.entity.MessageEntity
import com.postsaimanager.core.data.database.entity.MessageSourceEntity
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
     * A document's own conversation (id `conv-<documentId>`) plus, in principle, any other
     * conversation ever pointed at it — `documentId` carries no foreign key (see
     * [MessageSourceEntity]), so this is a defensive sweep, not just the one row. Messages
     * and their sources cascade off `conversations.id`.
     */
    @Query("DELETE FROM conversations WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: String)

    /**
     * `message_sources` rows citing [documentId] from *other* conversations — chiefly the
     * all-documents chat (`conv-standalone`). There is no FK from `message_sources.documentId`
     * to `documents.id` (a citation is allowed to outlive its source document, see
     * [MessageSourceEntity]), so a permanent delete has to clean these up itself.
     */
    @Query("DELETE FROM message_sources WHERE documentId = :documentId")
    suspend fun deleteMessageSourcesForDocument(documentId: String)

    /** The pages the chat cited [documentId] on (id and page), for moving them when the document's pages change. */
    @Query("SELECT id, pageNumber FROM message_sources WHERE documentId = :documentId AND pageNumber IS NOT NULL")
    suspend fun getCitedPages(documentId: String): List<CitedPageRow>

    @Query("UPDATE message_sources SET pageNumber = :pageNumber WHERE id = :id")
    suspend fun setCitedPage(id: Long, pageNumber: Int?)

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

    /** See [MessageSourceEntity]. Called only from [insertMessageAndTouchConversation]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessageSources(sources: List<MessageSourceEntity>)

    /**
     * Appends a message — and, if it was grounded on retrieved passages (4.3), the sources
     * that were shown to the model — and refreshes the conversation summary, atomically.
     *
     * Without the transaction a crash between the writes leaves `messageCount` wrong forever
     * — there is no reconciliation pass anywhere in the app — or a message with no sources
     * even though the use case computed some.
     */
    @Transaction
    suspend fun insertMessageAndTouchConversation(
        message: MessageEntity,
        sources: List<MessageSourceEntity> = emptyList(),
    ) {
        insertMessage(message)
        if (sources.isNotEmpty()) insertMessageSources(sources)
        touch(message.conversationId, message.createdAt)
    }
}

@Dao
interface MessageDao {

    /**
     * `@Relation` rather than a manual join: a message's sources are a small (0–4), rarely
     * queried side list — see [MessageSourceEntity]'s doc comment — and Room's generated
     * follow-up query is exactly as cheap as hand-writing one, without hand-maintaining the
     * join.
     */
    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageWithSources>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)
}

/** A chat citation's row id and the page it cites; see [ConversationDao.getCitedPages]. */
data class CitedPageRow(val id: Long, val pageNumber: Int)

/** A [MessageEntity] with its [MessageSourceEntity] rows — see [MessageDao.observeForConversation]. */
data class MessageWithSources(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val sources: List<MessageSourceEntity>,
)
