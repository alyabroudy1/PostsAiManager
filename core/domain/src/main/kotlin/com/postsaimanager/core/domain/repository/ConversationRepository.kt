package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for AI conversations and messages.
 */
interface ConversationRepository {
    fun getConversationsForDocument(documentId: String): Flow<List<AiConversation>>
    fun getMessages(conversationId: String): Flow<List<AiMessage>>
    suspend fun getConversationById(id: String): PamResult<AiConversation>
    suspend fun createConversation(conversation: AiConversation): PamResult<AiConversation>
    suspend fun addMessage(message: AiMessage): PamResult<AiMessage>
    suspend fun updateMessage(message: AiMessage): PamResult<Unit>
    suspend fun deleteConversation(id: String): PamResult<Unit>
}
