package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiEngineState
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.usecase.ChatErrorAction
import com.postsaimanager.core.domain.usecase.ChatTurn
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendChatMessage: SendChatMessageUseCase,
    private val conversationRepository: ConversationRepository,
    private val engine: AiEngine,
) : ViewModel() {

    private val documentId: String? = savedStateHandle["documentId"]

    /**
     * One conversation per document, so reopening a document resumes its history rather
     * than starting over. A document-less chat gets a fresh conversation per screen.
     */
    private val conversationId: String =
        documentId?.let { "conv-$it" } ?: "conv-${UuidGenerator.generate()}"

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    init {
        restoreHistory()
        observeEngine()
    }

    /** Chat survives process death — messages are persisted, not held in the ViewModel. */
    private fun restoreHistory() {
        viewModelScope.launch {
            conversationRepository.getMessages(conversationId).collect { messages ->
                _uiState.update { state ->
                    state.copy(
                        messages = messages.map {
                            ChatMessage(
                                text = it.content,
                                isUser = it.role == MessageRole.USER,
                                timestamp = it.createdAt,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun observeEngine() {
        viewModelScope.launch {
            engine.state.collect { engineState ->
                _uiState.update { it.copy(engineReady = engineState is AiEngineState.Ready) }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isProcessing) return

        // The streamed reply is held separately from persisted history: it is not yet a
        // stored message, and merging the two would make history flicker as tokens arrive.
        _uiState.update { it.copy(isProcessing = true, streamingText = "", error = null) }

        generationJob = viewModelScope.launch {
            sendChatMessage(
                conversationId = conversationId,
                documentId = documentId,
                text = text,
            ).collect { turn ->
                when (turn) {
                    is ChatTurn.PreparingModel ->
                        _uiState.update { it.copy(statusText = "Loading model…") }

                    is ChatTurn.Token ->
                        _uiState.update {
                            it.copy(
                                statusText = null,
                                streamingText = it.streamingText + turn.text,
                            )
                        }

                    is ChatTurn.Complete ->
                        // History reloads from the repository, so clear the streaming
                        // buffer to avoid showing the reply twice.
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                streamingText = "",
                                statusText = null,
                            )
                        }

                    is ChatTurn.Failed ->
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                streamingText = "",
                                statusText = null,
                                error = ChatError(turn.message, turn.action),
                            )
                        }
                }
            }
        }
    }

    /** Stops generation. Whatever was produced is still persisted by the use case. */
    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(isProcessing = false, streamingText = "", statusText = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    /** Partial reply while tokens stream in; empty when idle. */
    val streamingText: String = "",
    /** Transient status such as "Loading model…". */
    val statusText: String? = null,
    val engineReady: Boolean = false,
    val error: ChatError? = null,
)

/** A failure the user can act on, rather than a swallowed exception (see defect 6.7.12). */
data class ChatError(
    val message: String,
    val action: ChatErrorAction?,
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)
