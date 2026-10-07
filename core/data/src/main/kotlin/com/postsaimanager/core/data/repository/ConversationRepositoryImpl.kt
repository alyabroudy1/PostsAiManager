package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.ConversationDao
import com.postsaimanager.core.data.database.dao.MessageDao
import com.postsaimanager.core.data.database.dao.MessageWithSources
import com.postsaimanager.core.data.database.entity.ConversationEntity
import com.postsaimanager.core.data.database.entity.MessageEntity
import com.postsaimanager.core.data.database.entity.MessageSourceEntity
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.MediaType
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.MessageSource
import com.postsaimanager.core.model.ToolTrace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [ConversationRepository].
 *
 * Enum columns are stored as strings and decoded defensively: an unknown value from a
 * future schema must not crash the chat screen, so it degrades to a sensible default
 * rather than throwing out of `valueOf`.
 */
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ConversationRepository {

    override fun getConversationsForDocument(documentId: String): Flow<List<AiConversation>> =
        conversationDao.observeForDocument(documentId)
            .map { entities -> entities.map(::toDomain) }
            .flowOn(ioDispatcher)

    override fun getMessages(conversationId: String): Flow<List<AiMessage>> =
        messageDao.observeForConversation(conversationId)
            .map { rows -> rows.map(::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun getConversationById(id: String): PamResult<AiConversation> =
        withContext(ioDispatcher) {
            runCatching { conversationDao.getById(id) }
                .fold(
                    onSuccess = { entity ->
                        entity?.let { PamResult.Success(toDomain(it)) }
                            ?: PamResult.Error(PamError.FileNotFound("No conversation $id"))
                    },
                    onFailure = { PamResult.Error(PamError.DatabaseError(it)) },
                )
        }

    override suspend fun createConversation(
        conversation: AiConversation,
    ): PamResult<AiConversation> = withContext(ioDispatcher) {
        runCatching { conversationDao.insert(toEntity(conversation)) }
            .fold(
                onSuccess = { PamResult.Success(conversation) },
                onFailure = { PamResult.Error(PamError.DatabaseError(it)) },
            )
    }

    override suspend fun addMessage(message: AiMessage): PamResult<AiMessage> =
        withContext(ioDispatcher) {
            runCatching {
                // Atomic: the message, its sources (4.3, if any), and the conversation's
                // messageCount/lastMessageAt.
                conversationDao.insertMessageAndTouchConversation(
                    toEntity(message),
                    message.sources.map { toEntity(message.id, it) },
                )
            }.fold(
                onSuccess = { PamResult.Success(message) },
                onFailure = { PamResult.Error(PamError.DatabaseError(it)) },
            )
        }

    override suspend fun updateMessage(message: AiMessage): PamResult<Unit> =
        withContext(ioDispatcher) {
            runCatching { messageDao.update(toEntity(message)) }
                .fold(
                    onSuccess = { PamResult.Success(Unit) },
                    onFailure = { PamResult.Error(PamError.DatabaseError(it)) },
                )
        }

    override suspend fun deleteMessage(id: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            // Sources cascade via the ForeignKey on MessageSourceEntity.
            runCatching { messageDao.deleteById(id) }
                .fold(
                    onSuccess = { PamResult.Success(Unit) },
                    onFailure = { PamResult.Error(PamError.DatabaseError(it)) },
                )
        }

    override suspend fun deleteConversation(id: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            // Messages cascade via the ForeignKey on MessageEntity.
            runCatching { conversationDao.deleteById(id) }
                .fold(
                    onSuccess = { PamResult.Success(Unit) },
                    onFailure = { PamResult.Error(PamError.DatabaseError(it)) },
                )
        }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private fun toDomain(entity: ConversationEntity) = AiConversation(
        id = entity.id,
        documentId = entity.documentId,
        aiModelId = entity.aiModelId,
        modelType = entity.modelType.toEnumOr(AiModelType.LOCAL),
        title = entity.title,
        lastMessageAt = entity.lastMessageAt,
        messageCount = entity.messageCount,
        isActive = entity.isActive,
        createdAt = entity.createdAt,
    )

    private fun toEntity(model: AiConversation) = ConversationEntity(
        id = model.id,
        documentId = model.documentId,
        aiModelId = model.aiModelId,
        modelType = model.modelType.name,
        title = model.title,
        lastMessageAt = model.lastMessageAt,
        messageCount = model.messageCount,
        isActive = model.isActive,
        createdAt = model.createdAt,
    )

    private fun toDomain(row: MessageWithSources) = AiMessage(
        id = row.message.id,
        conversationId = row.message.conversationId,
        role = row.message.role.toEnumOr(MessageRole.ASSISTANT),
        content = row.message.content,
        mediaType = row.message.mediaType.toEnumOr(MediaType.TEXT),
        mediaPath = row.message.mediaPath,
        toolCallId = row.message.toolCallId,
        toolName = row.message.toolName,
        toolArgs = row.message.toolArgs,
        toolResult = row.message.toolResult,
        isStreaming = row.message.isStreaming,
        createdAt = row.message.createdAt,
        thinking = row.message.thinking,
        thinkingDurationMs = row.message.thinkingDurationMs,
        incomplete = row.message.incomplete,
        cutOff = row.message.cutOff,
        sources = row.sources.map(::toDomain),
        // An assistant message keeps its tool trace in the `toolArgs` column (see AiMessage.toolTrace); other roles' toolArgs is theirs.
        toolTrace = if (row.message.role == MessageRole.ASSISTANT.name) ToolTrace.decode(row.message.toolArgs) else emptyList(),
    )

    private fun toEntity(model: AiMessage) = MessageEntity(
        id = model.id,
        conversationId = model.conversationId,
        role = model.role.name,
        content = model.content,
        mediaType = model.mediaType.name,
        mediaPath = model.mediaPath,
        toolCallId = model.toolCallId,
        toolName = model.toolName,
        toolArgs = model.toolTrace.takeIf { it.isNotEmpty() }?.let(ToolTrace::encode) ?: model.toolArgs,
        toolResult = model.toolResult,
        isStreaming = model.isStreaming,
        createdAt = model.createdAt,
        thinking = model.thinking,
        thinkingDurationMs = model.thinkingDurationMs,
        incomplete = model.incomplete,
        cutOff = model.cutOff,
    )

    private fun toDomain(entity: MessageSourceEntity) = MessageSource(
        documentId = entity.documentId,
        pageNumber = entity.pageNumber,
        chunkId = entity.chunkId,
    )

    private fun toEntity(messageId: String, source: MessageSource) = MessageSourceEntity(
        messageId = messageId,
        documentId = source.documentId,
        pageNumber = source.pageNumber,
        chunkId = source.chunkId,
    )
}

/** Decodes an enum name, falling back instead of throwing on an unrecognised value. */
private inline fun <reified T : Enum<T>> String.toEnumOr(fallback: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: fallback
