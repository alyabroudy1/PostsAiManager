package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [ConversationRepository] for tests — no persistence, no coroutines dispatcher juggling. */
class FakeConversationRepository : ConversationRepository {

    private val conversations = MutableStateFlow<List<AiConversation>>(emptyList())
    private val messages = MutableStateFlow<List<AiMessage>>(emptyList())

    override fun getConversationsForDocument(documentId: String) =
        conversations.map { list -> list.filter { it.documentId == documentId } }

    override fun getMessages(conversationId: String) =
        messages.map { list -> list.filter { it.conversationId == conversationId } }

    override suspend fun getConversationById(id: String): PamResult<AiConversation> =
        conversations.value.firstOrNull { it.id == id }
            ?.let { PamResult.Success(it) }
            ?: PamResult.Error(PamError.DatabaseError())

    override suspend fun createConversation(conversation: AiConversation): PamResult<AiConversation> {
        conversations.value = conversations.value + conversation
        return PamResult.Success(conversation)
    }

    override suspend fun addMessage(message: AiMessage): PamResult<AiMessage> {
        messages.value = messages.value + message
        return PamResult.Success(message)
    }

    override suspend fun updateMessage(message: AiMessage): PamResult<Unit> {
        messages.value = messages.value.map { if (it.id == message.id) message else it }
        return PamResult.Success(Unit)
    }

    override suspend fun deleteConversation(id: String): PamResult<Unit> {
        conversations.value = conversations.value.filterNot { it.id == id }
        return PamResult.Success(Unit)
    }
}
