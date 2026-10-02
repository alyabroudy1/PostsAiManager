package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.MessageRole
import kotlinx.coroutines.flow.first

/**
 * The one writer of the form conversation's messages in a document's chat: what the user said, the assistant's status lines,
 * and the stored tool calls and results. Every message gets a strictly increasing time, so messages written in the same
 * millisecond keep their order.
 */
class FormChatLog(
    private val conversations: ConversationRepository,
    private val documents: DocumentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastStamp = 0L

    /** The time each progress line was first written, so an update keeps its place in the transcript. */
    private val progressStamps = HashMap<String, Long>()

    private fun stamp(): Long {
        lastStamp = maxOf(clock(), lastStamp + 1)
        return lastStamp
    }

    suspend fun ensureConversation(documentId: String): String {
        val id = conversationId(documentId)
        if (conversations.getConversationById(id) is PamResult.Error) {
            val title = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.title.orEmpty()
            conversations.createConversation(
                AiConversation(
                    id = id, documentId = documentId, aiModelId = null, modelType = AiModelType.LOCAL, title = title,
                    lastMessageAt = clock(), createdAt = clock(),
                ),
            )
        }
        return id
    }

    suspend fun messages(documentId: String): List<AiMessage> = conversations.getMessages(conversationId(documentId)).first()

    suspend fun userSaid(documentId: String, text: String) {
        val id = ensureConversation(documentId)
        conversations.addMessage(AiMessage(id = UuidGenerator.generate(), conversationId = id, role = MessageRole.USER, content = text, createdAt = stamp()))
    }

    /** A status line, a question or a card of the form conversation (see [FormMessage]). */
    suspend fun post(documentId: String, form: FormMessage, content: String = "") {
        val id = ensureConversation(documentId)
        conversations.addMessage(FormMessageCodec.toMessage(UuidGenerator.generate(), id, stamp(), form, content))
    }

    /** Adds the progress line [id] the first time and updates it in place afterwards. */
    suspend fun upsert(id: String, documentId: String, form: FormMessage) {
        val conversation = ensureConversation(documentId)
        val first = id !in progressStamps
        val at = progressStamps.getOrPut(id) { stamp() }
        val message = FormMessageCodec.toMessage(id, conversation, at, form)
        if (first) conversations.addMessage(message) else conversations.updateMessage(message)
    }

    /** Remembers that [id] is an existing progress line (one written before a restart), keeping its time. */
    fun adopt(id: String, createdAt: Long) {
        progressStamps[id] = createdAt
    }

    /** A message of the agent's own protocol (a stored tool call or result), stamped now. */
    suspend fun add(message: AiMessage) {
        conversations.addMessage(message.copy(createdAt = stamp()))
    }

    companion object {
        /** A document's own chat (one conversation per document, see the chat screen). */
        fun conversationId(documentId: String) = "conv-$documentId"

        /** The one fill of a document. */
        fun fillId(documentId: String) = "fill-$documentId"
    }
}
