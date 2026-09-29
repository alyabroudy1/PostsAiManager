package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.usecase.ChatErrorAction
import com.postsaimanager.core.domain.usecase.ChatTurn
import com.postsaimanager.core.domain.usecase.DocumentPreview
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.ObserveInstalledModelsUseCase
import com.postsaimanager.core.domain.usecase.ResetInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.SelectActiveModelUseCase
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.domain.usecase.SuggestedChatQuestions
import com.postsaimanager.core.domain.usecase.UnblockGpuUseCase
import com.postsaimanager.core.domain.usecase.UpdateInferenceSettingUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.MessageSource
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ThinkingEffort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendChatMessage: SendChatMessageUseCase,
    private val conversationRepository: ConversationRepository,
    private val documentRepository: DocumentRepository,
    private val engine: AiEngine,
    private val observeInstalledModels: ObserveInstalledModelsUseCase,
    private val selectActiveModel: SelectActiveModelUseCase,
    private val observeInferenceSettings: ObserveInferenceSettingsUseCase,
    private val updateInferenceSetting: UpdateInferenceSettingUseCase,
    private val resetInferenceSettings: ResetInferenceSettingsUseCase,
    private val unblockGpu: UnblockGpuUseCase,
    private val getDocumentPreview: GetDocumentPreviewUseCase,
) : ViewModel() {

    private val _preview = MutableStateFlow<CitationPreviewState?>(null)

    /** The in-chat page preview opened from a citation chip, or null while it is closed. */
    val preview: StateFlow<CitationPreviewState?> = _preview.asStateFlow()

    private var previewJob: Job? = null

    /** A citation chip was tapped: load that document's pages and show them at the cited one. */
    fun openPreview(source: ChatSource) {
        if (source.documentDeleted) return
        previewJob?.cancel()
        _preview.value = CitationPreviewState(source = source, loading = true)
        previewJob = viewModelScope.launch {
            val loaded = getDocumentPreview(source.documentId, source.chunkId)
            // Closed (or replaced) while loading: do not resurrect it.
            if (_preview.value?.source != source) return@launch
            _preview.value = if (loaded == null) {
                CitationPreviewState(source = source, loading = false, unavailable = true)
            } else {
                CitationPreviewState(
                    source = source,
                    loading = false,
                    preview = loaded,
                    initialPageIndex = loaded.pages
                        .indexOfFirst { it.pageNumber == source.pageNumber }
                        .coerceAtLeast(0),
                )
            }
        }
    }

    fun closePreview() {
        previewJob?.cancel()
        _preview.value = null
    }

    private val documentId: String? = savedStateHandle["documentId"]

    /**
     * One conversation per document, so reopening a document resumes its history rather
     * than starting over.
     *
     * A document-less chat (4.2, "Ask about your documents") gets exactly one standing
     * conversation across the whole app, for the same reason: [ConversationRepository] has
     * no concept of "which standalone session" beyond the id it is given, so a fresh random
     * id every time this screen opened would silently orphan the previous conversation's
     * history instead of resuming it. If per-session standalone conversations (a history
     * list to pick from, say) are ever wanted, that needs an actual UI to choose one and is
     * out of scope here.
     */
    private val conversationId: String = documentId?.let { "conv-$it" } ?: STANDALONE_CONVERSATION_ID

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

    /**
     * Starter questions for the empty-conversation state (5.2) — see [SuggestedChatQuestions].
     * Document chat re-derives these from [DocumentRepository.observeExtractedData] as
     * extraction fills fields in, so a chat opened right after scanning (no fields yet) picks
     * up the deadline/amount-specific questions the moment they appear, without the screen
     * needing to be reopened. Standalone chat has no per-document fields to react to, so it
     * is computed once.
     */
    val suggestedQuestions: StateFlow<List<String>> = if (documentId != null) {
        documentRepository.observeExtractedData(documentId)
            .map { fields -> SuggestedChatQuestions.forDocument(fields) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )
    } else {
        MutableStateFlow(SuggestedChatQuestions.forStandaloneChat()).asStateFlow()
    }

    init {
        restoreHistory()
        observeEngine()
        preWarmModel()
    }

    /**
     * Requests the active model load *and* primes this conversation's session as soon as the
     * chat screen opens, rather than waiting for the first message.
     *
     * [SendChatMessageUseCase.primeConversation] calls `engine.load` — the same path
     * [sendMessage] uses — so a send that races this simply joins the same in-flight load
     * ([ModelLoadCoordinator][com.postsaimanager.core.ai.local.ModelLoadCoordinator] is
     * single-flight), then `engine.ensureChatSession`, guarded by the engine's own session
     * lock: a send that starts while this is still priming blocks on that lock and then finds
     * the session already primed (a no-op), rather than re-doing the work or racing it. See
     * [SendChatMessageUseCase.primeConversation]'s doc for why this is safe to call
     * concurrently with a real send.
     *
     * [isPrimingConversation] only covers the priming half — [modelSheetState] already
     * observes [engine]'s [ModelLoadState][com.postsaimanager.core.model.ModelLoadState]
     * independently for the model-load half, so the header chip moves through Loading -> Ready
     * on its own. A no-op, not a crash or a log warning, when nothing is installed — see
     * [com.postsaimanager.core.domain.usecase.PreloadActiveModelUseCase]'s early return,
     * which `primeConversation` shares.
     */
    private fun preWarmModel() {
        viewModelScope.launch { primeConversation() }
    }

    /** Shared by [preWarmModel] and [selectModel] — see their docs. */
    private suspend fun primeConversation() {
        // Sampled before our own load/prime starts, so a busy engine here can only be someone
        // else's work (a document being read) — the header then explains the wait.
        val busyWithOtherWork = engine.isBusy
        _uiState.update {
            it.copy(isPrimingConversation = true, primeWaitingForDocument = busyWithOtherWork)
        }
        try {
            sendChatMessage.primeConversation(conversationId, documentId) {
                _uiState.update { it.copy(primeWaitingForDocument = false) }
            }
        } finally {
            _uiState.update { it.copy(isPrimingConversation = false, primeWaitingForDocument = false) }
        }
    }

    /** Chat survives process death — messages are persisted, not held in the ViewModel. */
    private fun restoreHistory() {
        viewModelScope.launch {
            conversationRepository.getMessages(conversationId).collect { messages ->
                val chatMessages = messages.map { message ->
                    val sources = message.sources.map { toChatSource(it) }
                    ChatMessage(
                        id = message.id,
                        text = message.content,
                        isUser = message.role == MessageRole.USER,
                        timestamp = message.createdAt,
                        thinking = message.thinking,
                        thinkingDurationMs = message.thinkingDurationMs,
                        incomplete = message.incomplete,
                        cutOff = message.cutOff,
                        // 4.3: every source SendChatMessageUseCase persisted was *shown* to
                        // the model — narrow that down to what the answer actually cites, or
                        // keep them all when it cited none (see pickVisibleSources).
                        sources = pickVisibleSources(message.content, sources),
                    )
                }
                _uiState.update { it.copy(messages = chatMessages) }
            }
        }
    }

    /**
     * Cache of document id -> (title, deleted), for [toChatSource]. Never invalidated for a
     * *title*: a document's title barely ever changes after it is scanned, and a stale title
     * on a citation chip is a cosmetic, not a correctness, problem — not worth a Flow
     * subscription per source. A trashed/deleted verdict is re-checked every time instead
     * (see [toChatSource]) since that one does matter — a chip must not keep looking live
     * after the user deletes the document mid-conversation.
     */
    private val documentTitleCache = mutableMapOf<String, String?>()

    /**
     * [ChatSource.title] is only resolved for a document-less conversation ([documentId] is
     * null here) — a document-scoped chat already has exactly one document in view, so
     * `ChatScreen` never needs the title to render "Page N" (see [ChatSource]'s doc comment).
     * [ChatSource.documentDeleted] is checked regardless of chat type: a document-scoped chat
     * whose document was trashed since the message was sent still needs its own chips to stop
     * being tappable.
     */
    private suspend fun toChatSource(source: MessageSource): ChatSource {
        val document = (documentRepository.getDocumentById(source.documentId) as? PamResult.Success)?.data
        val title = if (documentId == null) {
            documentTitleCache.getOrPut(source.documentId) { document?.title }
        } else {
            null
        }
        return ChatSource(
            documentId = source.documentId,
            pageNumber = source.pageNumber,
            title = title,
            chunkId = source.chunkId,
            documentDeleted = document == null || document.isTrashed,
        )
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
                // Default OFF — see InferenceOverrides.thinkingEffort's KDoc.
                thinkingEffort = modelSheetState.value.overrides.thinkingEffort ?: ThinkingEffort.OFF,
            ).collect(::applyTurn)
        }
    }

    /**
     * Regenerates the LATEST assistant reply (5.1) — offered by `ChatScreen` only on that
     * one message, so this never has to figure out which reply is meant. Streams and
     * persists a fresh answer to the same question exactly like [sendMessage]; see
     * [SendChatMessageUseCase.regenerateLastReply] for how the standing session is kept in
     * sync with the deleted reply.
     */
    fun regenerate() {
        if (_uiState.value.isProcessing) return

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
            sendChatMessage.regenerateLastReply(
                conversationId = conversationId,
                documentId = documentId,
                // Default OFF — see InferenceOverrides.thinkingEffort's KDoc.
                thinkingEffort = modelSheetState.value.overrides.thinkingEffort ?: ThinkingEffort.OFF,
            ).collect(::applyTurn)
        }
    }

    /** Shared by [sendMessage] and [regenerate] — both stream the identical [ChatTurn] shape. */
    private fun applyTurn(turn: ChatTurn) {
        when (turn) {
            is ChatTurn.PreparingModel ->
                _uiState.update { it.copy(statusText = turn.reason ?: "Loading model…") }

            is ChatTurn.PreparingConversation ->
                _uiState.update { it.copy(statusText = "Preparing conversation…") }

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
     * Switches the active chat model and loads + primes it immediately, so the header chip
     * visibly moves Loading → Ready (and then shows "Preparing conversation…" briefly) on the
     * new model rather than sitting still until the next message — same path as
     * [preWarmModel], since a model switch invalidates whatever session the previous model had
     * primed just as much as opening the screen fresh does.
     */
    fun selectModel(modelId: String) {
        viewModelScope.launch {
            selectActiveModel(modelId)
            primeConversation()
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

    private companion object {
        /** The one standing conversation for a document-less chat — see [conversationId]. */
        const val STANDALONE_CONVERSATION_ID = "conv-standalone"
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
    /**
     * True while [ChatViewModel.preWarmModel] is priming this conversation's session (model
     * load + grounding/history prefill) in the background, before any message has been sent.
     * Drives the "Preparing conversation…" header subtitle — see [ModelHeaderChip].
     */
    val isPrimingConversation: Boolean = false,
    /** The pre-warm started while the engine was busy reading a document, and is still waiting. */
    val primeWaitingForDocument: Boolean = false,
    val error: ChatError? = null,
    /**
     * Set to `System.currentTimeMillis()` every time [ChatViewModel.sendMessage] runs —
     * never derived from [messages] itself. See [ChatViewModel.sendMessage]'s doc: this is
     * what makes the transcript always jump to the bottom on send (defect 2), independent
     * of `followBottom`.
     */
    val lastSentAt: Long = 0L,
) {
    /**
     * True while the chat is stuck behind a document being read: either the pre-warm found
     * the engine busy, or a send is waiting with the use case's busy reason. Drives the
     * header's "Waiting for a document to finish reading…".
     */
    val isWaitingForDocument: Boolean
        get() = primeWaitingForDocument ||
            (isProcessing && statusText == ChatTurn.PreparingModel.WAITING_FOR_DOCUMENT)
}

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
    /** With [incomplete]: the reply hit its token cap ("Answer was cut off") rather than being stopped. */
    val cutOff: Boolean = false,
    /** The passages this reply cites/was grounded on, for [ChatScreen]'s citation chips (4.3). */
    val sources: List<ChatSource> = emptyList(),
)

/**
 * A citation chip's worth of a [com.postsaimanager.core.model.MessageSource] — resolved with
 * whatever a UI needs and nothing it would have to look up itself.
 *
 * @param title the source document's title, resolved by [ChatViewModel.toChatSource] — only
 *   for a document-less (standalone) conversation, where several documents can appear in one
 *   answer and a chip needs to say which. Null in a document-scoped chat, where `ChatScreen`
 *   renders "Page N" without it — see that screen's `SourceChip`.
 */
data class ChatSource(
    val documentId: String,
    val pageNumber: Int?,
    val title: String?,
    /**
     * True when [documentId] no longer resolves to a live document — trashed or
     * permanently deleted. `ChatScreen`'s chip renders disabled and un-tappable for one of
     * these: there is nowhere left to navigate a tap to.
     */
    val documentDeleted: Boolean = false,
    /** The retrieved passage behind this chip, so the preview can highlight it on its page. */
    val chunkId: String? = null,
)

/** The page preview opened from a citation chip. */
data class CitationPreviewState(
    val source: ChatSource,
    val loading: Boolean = false,
    /** The document is gone (or has no pages) — nothing to preview. */
    val unavailable: Boolean = false,
    val preview: DocumentPreview? = null,
    /** Index into [DocumentPreview.pages] of the cited page. */
    val initialPageIndex: Int = 0,
)
