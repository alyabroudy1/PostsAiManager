package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiEngineState
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** Progress of one assistant turn, as the UI needs to render it. */
sealed interface ChatTurn {
    /** A model is being loaded — first message after app start pays this cost. */
    data object PreparingModel : ChatTurn

    /** An incremental token. */
    data class Token(val text: String) : ChatTurn

    /** Finished; [message] is the persisted assistant message. */
    data class Complete(val message: AiMessage) : ChatTurn

    /** Something went wrong, phrased for a person, with an action where one exists. */
    data class Failed(val message: String, val action: ChatErrorAction?) : ChatTurn
}

enum class ChatErrorAction {
    /** Send the user to model management — nothing is installed. */
    INSTALL_MODEL,

    /** Transient; offer retry. */
    RETRY,
}

/**
 * Sends a user message and streams the assistant's reply.
 *
 * Orchestration lives here rather than in the ViewModel so the same behaviour is reachable
 * from anywhere — including, later, an `AiTool` (architecture: the LLM is a client of the
 * domain layer exactly as the UI is).
 *
 * Persistence brackets generation: the user's message is stored **before** the model runs,
 * so a crash mid-generation cannot lose what the user typed. Spike Q3 established that a
 * native abort kills the whole process, which makes that ordering load-bearing rather than
 * merely tidy.
 */
class SendChatMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val engine: AiEngine,
    private val activeModelProvider: ActiveModelProvider,
    private val buildChatContext: BuildChatContextUseCase,
) {

    operator fun invoke(
        conversationId: String,
        documentId: String?,
        text: String,
        systemPrompt: String? = null,
    ): Flow<ChatTurn> = flow {
        val now = System.currentTimeMillis()

        // Ensure a conversation exists before anything can reference it.
        if (conversationRepository.getConversationById(conversationId) is PamResult.Error) {
            conversationRepository.createConversation(
                AiConversation(
                    id = conversationId,
                    documentId = documentId,
                    aiModelId = null,
                    modelType = AiModelType.LOCAL,
                    title = text.take(CONVERSATION_TITLE_LENGTH),
                    lastMessageAt = now,
                    createdAt = now,
                ),
            )
        }

        // Persist the user's message first — see the class note on ordering.
        val userMessage = AiMessage(
            id = UuidGenerator.generate(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = text,
            createdAt = now,
        )
        conversationRepository.addMessage(userMessage)

        if (!engine.isReady) {
            val modelPath = activeModelProvider.activeModelPath()
            if (modelPath == null) {
                emit(
                    ChatTurn.Failed(
                        "No AI model is installed yet. Install one to chat about your documents.",
                        ChatErrorAction.INSTALL_MODEL,
                    ),
                )
                return@flow
            }

            emit(ChatTurn.PreparingModel)
            val loaded = engine.load(modelPath, activeModelProvider.activeModelContextTokens())
            if (loaded is PamResult.Error) {
                emit(ChatTurn.Failed(loaded.error.userMessage, ChatErrorAction.RETRY))
                return@flow
            }
        }

        // Ground the conversation in the document. Built *after* the model is loaded so
        // the real context window is known — budgeting against a guess would either waste
        // capacity or overflow it.
        val contextTokens = when (val state = engine.state.value) {
            is AiEngineState.Ready -> state.capabilities.contextTokens
            else -> AiEngine.DEFAULT_CONTEXT_TOKENS
        }
        val grounding = systemPrompt ?: buildChatContext(documentId, contextTokens)

        // The engine applies the *model's own* chat template — Qwen, Gemma and Llama 3 all
        // differ, and the wrong one degrades output silently rather than failing.
        val prompt = engine.formatPrompt(
            buildList {
                if (grounding.isNotBlank()) {
                    add(AiChatMessage(AiChatRole.SYSTEM, grounding))
                }
                add(AiChatMessage(AiChatRole.USER, text))
            },
        )
        val builder = StringBuilder()

        try {
            engine.generate(AiRequest(prompt = prompt)).collect { token ->
                builder.append(token)
                emit(ChatTurn.Token(token))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The user stopped generation. Whatever was produced is still worth keeping.
            persistAssistant(conversationId, builder.toString())
            throw e
        } catch (e: Exception) {
            emit(ChatTurn.Failed(e.message ?: "Generation failed.", ChatErrorAction.RETRY))
            return@flow
        }

        val assistant = persistAssistant(conversationId, builder.toString())
        emit(ChatTurn.Complete(assistant))
    }

    private suspend fun persistAssistant(conversationId: String, content: String): AiMessage {
        val message = AiMessage(
            id = UuidGenerator.generate(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = content,
            createdAt = System.currentTimeMillis(),
        )
        conversationRepository.addMessage(message)
        return message
    }


    private companion object {
        const val CONVERSATION_TITLE_LENGTH = 60
    }
}
