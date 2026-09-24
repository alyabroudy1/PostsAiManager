package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.usecase.ChatErrorAction
import com.postsaimanager.core.domain.usecase.ChatTurn
import com.postsaimanager.core.domain.usecase.ObserveInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.ObserveInstalledModelsUseCase
import com.postsaimanager.core.domain.usecase.PreloadActiveModelUseCase
import com.postsaimanager.core.domain.usecase.ResetInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.SelectActiveModelUseCase
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.domain.usecase.UnblockGpuUseCase
import com.postsaimanager.core.domain.usecase.UpdateInferenceSettingUseCase
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ModelLoadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendChatMessage: SendChatMessageUseCase,
    private val conversationRepository: ConversationRepository,
    private val engine: AiEngine,
    private val observeInstalledModels: ObserveInstalledModelsUseCase,
    private val selectActiveModel: SelectActiveModelUseCase,
    private val preloadActiveModel: PreloadActiveModelUseCase,
    private val observeInferenceSettings: ObserveInferenceSettingsUseCase,
    private val updateInferenceSetting: UpdateInferenceSettingUseCase,
    private val resetInferenceSettings: ResetInferenceSettingsUseCase,
    private val unblockGpu: UnblockGpuUseCase,
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

    /**
     * Everything the chat header chip and its model sheet render — which model is loaded,
     * which are installed, and the schema-driven inference settings for the active one.
     * Kept separate from [uiState] rather than folded in: it changes on a completely
     * different rhythm (engine load transitions, not tokens streaming in) and the sheet is
     * dismissed most of the time, so most collectors never touch it.
     */
    val modelSheetState: StateFlow<ModelSheetUiState> =
        combine(
            engine.state,
            observeInstalledModels(),
            observeInferenceSettings(),
        ) { loadState, installed, inference ->
            ModelSheetUiState(
                loadState = loadState,
                installedModels = installed.models,
                activeModelId = installed.activeModelId,
                schema = inference.schema,
                overrides = inference.overrides,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ModelSheetUiState(),
        )

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
                                id = it.id,
                                text = it.content,
                                isUser = it.role == MessageRole.USER,
                                timestamp = it.createdAt,
                                thinking = it.thinking,
                                thinkingDurationMs = it.thinkingDurationMs,
                                incomplete = it.incomplete,
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
                _uiState.update { it.copy(engineReady = engineState is ModelLoadState.Ready) }
            }
        }
    }

    /** The text of the last message sent — what [retry] resends after a failure. */
    private var lastSentText: String? = null

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isProcessing) return
        lastSentText = text

        // The streamed reply is held separately from persisted history: it is not yet a
        // stored message, and merging the two would make history flicker as tokens arrive.
        //
        // `lastSentAt` is what makes the transcript ALWAYS jump to the bottom on send
        // (defect 2), regardless of `followBottom` — see ChatScreen's `LaunchedEffect` keyed
        // on it. Keyed on this send event rather than on `messages.size` so a message
        // arriving from elsewhere (history restore, a retry) never fights the user's own
        // scroll the way `followBottom`-gated auto-scroll otherwise correctly does.
        _uiState.update {
            it.copy(
                isProcessing = true,
                streamingText = "",
                thinkingText = "",
                thinkingDurationMs = null,
                isThinkingActive = false,
                error = null,
                lastSentAt = System.currentTimeMillis(),
            )
        }

        generationJob = viewModelScope.launch {
            sendChatMessage(
                conversationId = conversationId,
                documentId = documentId,
                text = text,
                thinkingEnabled = modelSheetState.value.overrides.thinkingEnabled ?: true,
            ).collect { turn ->
                when (turn) {
                    is ChatTurn.PreparingModel ->
                        _uiState.update { it.copy(statusText = "Loading model…") }

                    is ChatTurn.ThinkingToken ->
                        _uiState.update {
                            it.copy(
                                statusText = null,
                                isThinkingActive = true,
                                thinkingText = it.thinkingText + turn.text,
                            )
                        }

                    is ChatTurn.ThinkingComplete ->
                        // The answer is about to start — collapse the thinking card to its
                        // header, exactly as a finished, persisted message will render.
                        _uiState.update {
                            it.copy(
                                isThinkingActive = false,
                                thinkingDurationMs = turn.durationMs,
                            )
                        }

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
                                thinkingText = "",
                                isThinkingActive = false,
                                statusText = null,
                            )
                        }

                    is ChatTurn.Failed ->
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                streamingText = "",
                                isThinkingActive = false,
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
        _uiState.update {
            it.copy(
                isProcessing = false,
                streamingText = "",
                thinkingText = "",
                isThinkingActive = false,
                statusText = null,
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Re-sends the message that failed — the whole point of [ChatErrorAction.RETRY]. */
    fun retry() {
        lastSentText?.let { sendMessage(it) }
    }

    /**
     * Retries a stopped/incomplete assistant reply (defect 3's "Retry" affordance) — finds
     * the user turn that preceded [message] and re-sends exactly that text.
     *
     * Deliberately the simplest consistent behaviour: the incomplete message is **kept**,
     * not deleted — a fresh assistant reply is appended below it, exactly like any other
     * retry in this screen (see [retry]). Nothing here needs to reach into persistence to
     * delete a message, and the stopped reply stays visible as a record of what happened,
     * consistent with defect 3's "keep the partial answer visible" requirement.
     */
    fun retryMessage(message: ChatMessage) {
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0) return
        val precedingUserText = messages.subList(0, index)
            .lastOrNull { it.isUser }
            ?.text
            ?: return
        sendMessage(precedingUserText)
    }

    /**
     * Switches the active chat model and loads it immediately, so the header chip visibly
     * moves Loading → Ready on the new model rather than sitting still until the next
     * message — see [PreloadActiveModelUseCase].
     */
    fun selectModel(modelId: String) {
        viewModelScope.launch {
            selectActiveModel(modelId)
            preloadActiveModel()
        }
    }

    /** [value] is whatever the [ConfigSpec]'s own control produced — see the use case doc. */
    fun setInferenceSetting(key: String, value: Any) {
        viewModelScope.launch { updateInferenceSetting(key, value) }
    }

    fun resetInference() {
        viewModelScope.launch { resetInferenceSettings() }
    }

    /**
     * "Try GPU again" — clears the persisted GPU crash block for the active model so the
     * accelerator choice offers GPU again, without wiping any other app data. Re-selecting
     * GPU afterwards is still a separate, explicit [setInferenceSetting] call; this only
     * unblocks the option.
     *
     * Looks the active model's file path up from [modelSheetState] rather than taking a
     * parameter — [InferenceSettingsRepository.gpuBlockedModels][
     * com.postsaimanager.core.domain.repository.InferenceSettingsRepository.gpuBlockedModels]
     * is keyed by `InstalledModelSummary.filePath`, not `.id`, and the sheet only has the
     * latter to hand.
     */
    fun tryGpuAgain() {
        val state = modelSheetState.value
        val filePath = state.installedModels
            .firstOrNull { it.id == state.activeModelId }
            ?.filePath
            ?: return
        viewModelScope.launch { unblockGpu(filePath) }
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }
}

/** See [ChatViewModel.modelSheetState]. */
data class ModelSheetUiState(
    val loadState: ModelLoadState = ModelLoadState.Idle,
    val installedModels: List<InstalledModelSummary> = emptyList(),
    val activeModelId: String? = null,
    val schema: List<ConfigSpec> = emptyList(),
    val overrides: InferenceOverrides = InferenceOverrides.NONE,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    /** Partial reply while tokens stream in; empty when idle. */
    val streamingText: String = "",
    /** Partial reasoning trace while it streams; empty once the answer starts or is idle. */
    val thinkingText: String = "",
    /** True only while the thinking phase is actively streaming — drives auto-expand. */
    val isThinkingActive: Boolean = false,
    /** Set once the thinking phase ends, for the live "Thought for N s" header. */
    val thinkingDurationMs: Long? = null,
    /** Transient status such as "Loading model…". */
    val statusText: String? = null,
    val engineReady: Boolean = false,
    val error: ChatError? = null,
    /**
     * Set to `System.currentTimeMillis()` every time [ChatViewModel.sendMessage] runs —
     * never derived from [messages] itself. See [ChatViewModel.sendMessage]'s doc: this is
     * what makes the transcript always jump to the bottom on send (defect 2), independent
     * of `followBottom`.
     */
    val lastSentAt: Long = 0L,
)

/** A failure the user can act on, rather than a swallowed exception (see defect 6.7.12). */
data class ChatError(
    val message: String,
    val action: ChatErrorAction?,
)

data class ChatMessage(
    val id: String = "",
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    /** The model's reasoning trace for this reply, if any — display-only, see [com.postsaimanager.core.model.AiMessage]. */
    val thinking: String? = null,
    val thinkingDurationMs: Long? = null,
    /** True for a reply the user stopped, or one that failed mid-stream. See [com.postsaimanager.core.model.AiMessage.incomplete]. */
    val incomplete: Boolean = false,
)
