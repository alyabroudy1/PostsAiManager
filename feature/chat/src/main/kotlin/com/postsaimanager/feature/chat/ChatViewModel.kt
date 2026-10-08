package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.form.agent.FormChatLog
import com.postsaimanager.core.domain.form.agent.FormFillAgent
import com.postsaimanager.core.domain.form.agent.FormRoute
import com.postsaimanager.core.domain.form.fill.FillProgress
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.domain.ai.MessageImages
import com.postsaimanager.core.domain.skills.ToolSteps
import com.postsaimanager.core.domain.usecase.AttachChatImageUseCase
import com.postsaimanager.core.domain.usecase.ChatErrorAction
import com.postsaimanager.core.domain.usecase.ChatImageSupportUseCase
import com.postsaimanager.core.domain.usecase.ChatSessionTracker
import com.postsaimanager.core.domain.usecase.ContinuityTail
import com.postsaimanager.core.domain.usecase.ChatTurn
import com.postsaimanager.core.domain.usecase.StartNewChatUseCase
import com.postsaimanager.feature.chat.skills.JsSkillRelay
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.ObserveInstalledModelsUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.domain.usecase.ResetInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.SelectActiveModelUseCase
import com.postsaimanager.core.domain.usecase.SearchModelHint
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.domain.usecase.UnblockGpuUseCase
import com.postsaimanager.core.domain.usecase.UpdateInferenceSettingUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.DocumentPreview
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormFillingFlag
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.MessageSource
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ThinkingEffort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val engine: ChatEngine,
    private val observeInstalledModels: ObserveInstalledModelsUseCase,
    private val selectActiveModel: SelectActiveModelUseCase,
    private val observeInferenceSettings: ObserveInferenceSettingsUseCase,
    private val updateInferenceSetting: UpdateInferenceSettingUseCase,
    private val resetInferenceSettings: ResetInferenceSettingsUseCase,
    private val unblockGpu: UnblockGpuUseCase,
    private val getDocumentPreview: GetDocumentPreviewUseCase,
    private val observeSuggestedQuestions: ObserveSuggestedQuestionsUseCase,
    private val formFill: FormFillAgent,
    private val formFills: FormFillRepository,
    private val searchModelHint: SearchModelHint,
    private val startNewChat: StartNewChatUseCase,
    private val attachChatImage: AttachChatImageUseCase,
    private val chatImageSupport: ChatImageSupportUseCase,
    private val jsSkillRelay: JsSkillRelay,
    private val formFillingFlag: FormFillingFlag = FormFillingFlag.ON,
    /** Which chats have a live session: this screen is a visit that ends when it is left or idle for 10 minutes. */
    private val sessions: ChatSessionTracker = ChatSessionTracker(),
) : ViewModel() {

    private val _preview = MutableStateFlow<CitationPreviewState?>(null)

    /** The in-chat page preview opened from a citation chip, or null while it is closed. */
    val preview: StateFlow<CitationPreviewState?> = _preview.asStateFlow()

    private var previewJob: Job? = null

    /** A citation chip was tapped: load that document's pages and show them at the cited one. */
    fun openPreview(source: ChatSource) {
        if (source.documentDeleted) return
        showPreview(source) { getDocumentPreview(source.documentId, source.chunkId) }
    }

    /** A fill card row's page chip: the page of [field] with the box where its value goes marked. */
    fun openFieldPreview(field: FormField) {
        val document = documentId ?: return
        val source = ChatSource(documentId = document, pageNumber = field.page, title = null)
        val box = (field.fillBox ?: field.labelBox)?.let { TextBounds(it.left, it.top, it.right, it.bottom) }
        showPreview(source) { getDocumentPreview.forField(document, field.page, box) }
    }

    private fun showPreview(source: ChatSource, load: suspend () -> DocumentPreview?) {
        previewJob?.cancel()
        _preview.value = CitationPreviewState(source = source, loading = true)
        previewJob = viewModelScope.launch {
            val loaded = load()
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
    private val conversationId: String = conversationIdFor(documentId)

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
     * Starter questions for the empty-conversation state (5.2) — the three the model wrote for the
     * document while it read it, see [ObserveSuggestedQuestionsUseCase]. A document chat picks them
     * up the moment extraction stores them; the all-documents chat shows those of the most recent
     * actionable document, or nothing (there is no static fallback list).
     */
    val suggestedQuestions: StateFlow<List<String>> =
        (if (documentId != null) observeSuggestedQuestions.forDocument(documentId) else observeSuggestedQuestions.forAllDocuments())
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    private val searchModelHintRefresh = MutableStateFlow(0)

    /**
     * Whether the one-time "answers can show their sources once the search model is installed" hint is shown: the search model is not
     * installed and the user has not dismissed it. Read again by [refreshSearchModelHint] (the screen returns from the models screen).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val searchModelHintVisible: StateFlow<Boolean> = searchModelHintRefresh
        .flatMapLatest { searchModelHint.observe() }
        .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = false)

    /** Looks again at whether the search model is installed, e.g. when the screen is resumed. */
    fun refreshSearchModelHint() {
        searchModelHintRefresh.update { it + 1 }
    }

    /** The hint was dismissed, or followed to the models screen: not shown again. */
    fun dismissSearchModelHint() {
        viewModelScope.launch { searchModelHint.dismiss() }
    }

    /**
     * The fill card's data: the document's fill and its fields, live, so the card re-renders on every answer. Null until a
     * fill was started (and always for the all-documents chat).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val fillCard: StateFlow<FillCardState?> = (
        documentId?.let { id ->
            formFills.observeFill(FormChatLog.fillId(id)).flatMapLatest { fill ->
                if (fill == null) flowOf(null) else formFills.observeFields(fill.id).map { FillCardState(fill, it) }
            }
        } ?: flowOf(null)
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // The model's `run_js` calls are answered by the offline sandbox from the first reply on.
        jsSkillRelay.ensureStarted()
        restoreHistory()
        observeEngine()
        preWarmModel()
        openFormFill(startRequested = savedStateHandle.get<Boolean>(ARG_FILL) == true && savedStateHandle.get<Boolean>(STATE_FILL_STARTED) != true)
        if (documentId != null) savedStateHandle[STATE_FILL_STARTED] = true
    }

    /**
     * The form-fill side of opening a document chat: "Help me fill it" (the [ARG_FILL] argument) starts the fill; otherwise an
     * understanding that was interrupted (the user left, the process died) is picked up again. Both run in the background with a
     * progress line in the chat; leaving the chat cancels them.
     */
    private fun openFormFill(startRequested: Boolean) {
        if (!formFillingFlag.enabled) return
        val document = documentId ?: return
        runFormWork(announce = startRequested) { if (startRequested) formFill.start(document) else formFill.resume(document) }
    }

    /**
     * Runs conversation work in the background, showing the typing state (and the Stop button, which cancels it). Cancelled with
     * the screen; a failure is shown as the chat's own error card, never swallowed.
     */
    private fun runFormWork(announce: Boolean = true, block: suspend () -> Unit) {
        if (_uiState.value.isProcessing) return
        _uiState.update {
            it.copy(isProcessing = true, error = null, lastSentAt = if (announce) System.currentTimeMillis() else it.lastSentAt)
        }
        generationJob = viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = ChatError(e.message ?: "Something went wrong.", ChatErrorAction.RETRY)) }
            } finally {
                _uiState.update { it.copy(isProcessing = false, statusText = null) }
            }
        }
    }

    /** A chip of the form conversation was tapped; [shownText] is its label as the user saw it. */
    fun onFormChip(chip: FormChip, shownText: String) {
        if (!formFillingFlag.enabled) return
        val document = documentId ?: return
        runFormWork { formFill.chip(document, chip, shownText) }
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
        startWarmUp()
    }

    /** The pre-warm in flight (model load, session, and the engine's conversation prefill), or null. */
    private var warmUpJob: Job? = null

    /**
     * Starts the pre-warm unless one is already running. Called when the chat opens and again each time the screen comes back to the
     * foreground (a warm-up that was stopped in the background, or a conversation that is no longer prepared, is picked up again;
     * for a prepared one every step is a no-op). Never blocks the UI and never gets in the way of typing: a message sent meanwhile
     * waits for the engine like any message does.
     */
    fun startWarmUp() {
        if (warmUpJob?.isActive == true) return
        warmUpJob = viewModelScope.launch {
            try {
                primeConversation()
            } finally {
                scheduleSessionIdleEnd()
            }
        }
    }

    /** The idle check of the session: one wake-up, [ChatSessionTracker.idleMs] after the last activity this screen noticed. */
    private var idleJob: Job? = null

    /**
     * Starts the idle clock again. A session that nothing touches for 10 minutes ends (the engine's conversation is then built from
     * the card and the last exchange by the next send), and the divider moves to where the model's context will start. Called after
     * each activity of this screen (the warm-up, a send, a finished reply); the tracker owns the rule, this only wakes up.
     */
    private fun scheduleSessionIdleEnd() {
        idleJob?.cancel()
        val wait = sessions.idleInMs(conversationId) ?: return
        idleJob = viewModelScope.launch {
            delay(wait)
            if (sessions.endIfIdle(conversationId)) {
                contextStartId = ContinuityTail.select(rawMessages).firstOrNull()?.id
                _uiState.update { it.copy(contextStartMessageId = contextStartId) }
            }
        }
    }

    /** The stored messages as the repository last emitted them, for the divider. */
    private var rawMessages: List<com.postsaimanager.core.model.AiMessage> = emptyList()

    /** The first message of the continuity tail when the model's context was last (re)built; null before the first load or without a tail. */
    private var contextStartId: String? = null
    private var contextStartKnown = false

    /**
     * Stops the pre-warm: the screen left the foreground, or the chat is closing. The engine stops what it can; a prefill that is
     * already running in the native engine finishes and its result is kept (it is a valid conversation for the next message).
     */
    fun stopWarmUp() {
        warmUpJob?.cancel()
        warmUpJob = null
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
            sendChatMessage.primeConversation(
                conversationId,
                documentId,
                onLoadFinished = { _uiState.update { it.copy(primeWaitingForDocument = false) } },
                // The mode of the first reply (default OFF, as `chatTurn` reads it): it decides which conversation the engine prepares.
                thinkingEffort = modelSheetState.value.overrides.thinkingEffort ?: ThinkingEffort.OFF,
            )
        } finally {
            _uiState.update { it.copy(isPrimingConversation = false, primeWaitingForDocument = false) }
        }
    }

    /** Chat survives process death — messages are persisted, not held in the ViewModel. */
    private fun restoreHistory() {
        viewModelScope.launch {
            conversationRepository.getMessages(conversationId).collect { messages ->
                // The agent's own protocol (its stored tool calls and results) is not a message: only the calls that show
                // something (a question, the card, a page chip, the closing message) are, and the user never sees raw tool JSON.
                val chatMessages = FormMessageCodec.rendered(messages).map { message ->
                    val sources = message.sources.map { toChatSource(it) }
                    ChatMessage(
                        id = message.id,
                        text = message.content,
                        form = FormMessageCodec.parse(message),
                        isUser = message.role == MessageRole.USER,
                        timestamp = message.createdAt,
                        thinking = message.thinking,
                        thinkingDurationMs = message.thinkingDurationMs,
                        images = if (message.role == MessageRole.USER) MessageImages.decode(message.mediaPath) else emptyList(),
                        toolSteps = ToolSteps.of(message.toolTrace),
                        incomplete = message.incomplete,
                        cutOff = message.cutOff,
                        // 4.3: every source SendChatMessageUseCase persisted was *shown* to
                        // the model — narrow that down to what the answer actually cites, or
                        // keep them all when it cited none (see pickVisibleSources).
                        sources = pickVisibleSources(message.content, sources),
                    )
                }
                rawMessages = messages
                // The model's context starts at the last exchange the first time the chat shows; a live session then only grows
                // below that line (see scheduleSessionIdleEnd for when it moves).
                if (!contextStartKnown) {
                    contextStartKnown = true
                    contextStartId = ContinuityTail.select(messages).firstOrNull()?.id
                }
                _uiState.update { it.copy(messages = chatMessages, contextStartMessageId = contextStartId) }
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

    /** Sends [text] with the pictures attached so far (cleared once sent). */
    fun sendMessage(text: String) = send(text, alreadyStored = false)

    /**
     * @param alreadyStored the message is already the last row of the conversation (a retry after a failed attempt): it is sent
     *   again as it is (with the pictures it was stored with), so the chat shows one bubble, not one per attempt.
     */
    private fun send(text: String, alreadyStored: Boolean) {
        if (text.isBlank() || _uiState.value.isProcessing) return
        com.postsaimanager.core.common.util.TimingLog.mark()
        com.postsaimanager.core.common.util.TimingLog.log("t0 send tapped (${text.length} chars, formFilling=${formFillingFlag.enabled})")
        lastSentText = text
        if (alreadyStored) {
            startChatTurn(text, persistUserMessage = false)
            return
        }
        // A message with pictures is for the chat model: the form agent reads words, not pictures.
        val attached = _uiState.value.attachments
        if (attached.isNotEmpty()) {
            _uiState.update { it.copy(attachments = emptyList()) }
            startChatTurn(text, imagePaths = attached)
            return
        }
        val document = documentId
        // Form filling switched off: the message is never read for a fill request (no detector, no model call).
        if (document == null || !formFillingFlag.enabled) {
            startChatTurn(text)
            return
        }
        // In a document chat the form agent reads the message first: while it runs, every message is the user's next message to it
        // (it decides what the message means); a request to fill the form starts it; anything else is the normal grounded chat.
        runFormWork {
            val route = formFill.route(document, text)
            com.postsaimanager.core.common.util.TimingLog.at("form route done: $route")
            when (route) {
                FormRoute.HANDLED -> Unit
                FormRoute.NOT_FOR_FORM -> chatTurn(text)
            }
        }
    }

    /** The normal chat turn, in the background of the view model (the all-documents chat). */
    private fun startChatTurn(text: String, persistUserMessage: Boolean = true, imagePaths: List<String> = emptyList()) {
        beginChatTurn()
        generationJob = viewModelScope.launch { chatTurn(text, persistUserMessage, imagePaths) }
    }

    /** Streams the grounded reply to [text] until it completes, fails or is stopped. */
    private suspend fun chatTurn(text: String, persistUserMessage: Boolean = true, imagePaths: List<String> = emptyList()) {
        beginChatTurn()
        sendChatMessage(
            conversationId = conversationId,
            documentId = documentId,
            text = text,
            // Default OFF — see InferenceOverrides.thinkingEffort's KDoc.
            thinkingEffort = modelSheetState.value.overrides.thinkingEffort ?: ThinkingEffort.OFF,
            persistUserMessage = persistUserMessage,
            imagePaths = imagePaths,
        ).collect(::applyTurn)
    }

    // ── Pictures ──────────────────────────────────────────────────────────────────────────────────

    /** Looks again at whether the chat model takes pictures (the screen opens, or the model changed). */
    fun refreshImageSupport() {
        viewModelScope.launch {
            val supported = chatImageSupport()
            _uiState.update { it.copy(imageInputSupported = supported) }
        }
    }

    /**
     * A picture from the picker ([source] is its URI): copied into the app's own storage and attached to the next message, up
     * to the Gallery's limit. A file that is not a picture is not attached, and says so.
     */
    fun attachImage(source: String) {
        if (_uiState.value.attachments.size >= MessageImages.MAX_PER_MESSAGE) return
        viewModelScope.launch {
            val stored = attachChatImage(conversationId, source)
            _uiState.update {
                when {
                    stored == null -> it.copy(error = ChatError("", null, R.string.chat_error_image_unreadable))
                    it.attachments.size >= MessageImages.MAX_PER_MESSAGE -> it
                    else -> it.copy(attachments = it.attachments + stored)
                }
            }
        }
    }

    /** The pages of this letter that can be attached, once the attach menu is opened. */
    fun loadAttachablePages() {
        val document = documentId ?: return
        viewModelScope.launch {
            val pages = getDocumentPreview(document)?.pages.orEmpty()
            _uiState.update { it.copy(attachablePages = pages.map { page -> AttachablePage(page.pageNumber, page.imagePath) }) }
        }
    }

    /** A page image of the current letter attached as a picture (the stored page image is copied, scaled, like a picked photo). */
    fun attachPage(page: AttachablePage) = attachImage(page.imagePath)

    fun removeAttachment(path: String) {
        _uiState.update { it.copy(attachments = it.attachments - path) }
    }

    // ── A new chat ────────────────────────────────────────────────────────────────────────────────

    /**
     * The Gallery's "new session" for this chat: stops a reply in flight, deletes the conversation (history, citations and
     * pictures) and drops the model's conversation. What is deleted stays deleted; the next message starts a fresh one.
     */
    fun newChat() {
        generationJob?.cancel()
        generationJob = null
        previewJob?.cancel()
        _preview.value = null
        viewModelScope.launch {
            val cleared = startNewChat(conversationId)
            lastSentText = null
            if (cleared) {
                // Nothing before the next message: no tail, no divider; the session is gone without an end event.
                idleJob?.cancel()
                contextStartId = null
                contextStartKnown = true
            }
            _uiState.update {
                if (cleared) {
                    it.copy(
                        messages = emptyList(),
                        contextStartMessageId = null,
                        isProcessing = false,
                        streamingText = "",
                        thinkingText = "",
                        isThinkingActive = false,
                        thinkingDurationMs = null,
                        statusText = null,
                        attachments = emptyList(),
                        error = null,
                    )
                } else {
                    it.copy(isProcessing = false, error = ChatError("", null, R.string.chat_error_new_chat_failed))
                }
            }
            // The cleared chat has no prepared conversation any more: prepare the new one now, as when the chat opened.
            if (cleared) startWarmUp()
        }
    }

    private fun beginChatTurn() {
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

            is ChatTurn.Complete -> {
                scheduleSessionIdleEnd()
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
            }

            is ChatTurn.Failed -> {
                // The reason is kept in the log (no letter content): "sometimes it fails" is only fixable with it.
                runCatching { android.util.Log.w("ChatViewModel", "chat turn failed (${turn.action}): ${turn.message}") }
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        streamingText = "",
                        isThinkingActive = false,
                        statusText = null,
                        error = chatErrorOf(turn),
                    )
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
        val text = lastSentText ?: return
        // The failed attempt stored the user's message (a crash mid-generation must not lose what they typed): when it is the last
        // message of the chat, retrying re-sends THAT message instead of storing a second copy of it.
        val last = _uiState.value.messages.lastOrNull()
        send(text, alreadyStored = last != null && last.isUser && last.text == text)
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
        // The warm-up of the model being left would hold the engine: stop it before the switch.
        stopWarmUp()
        warmUpJob = viewModelScope.launch {
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
        // Leaving the chat ends the visit: the next send (or the next open) builds the conversation from the card and the last exchange.
        idleJob?.cancel()
        sessions.leave(conversationId)
        stopWarmUp()
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** The one standing conversation for a document-less chat — see [conversationId]. */
        private const val STANDALONE_CONVERSATION_ID = "conv-standalone"

        /** The conversation of a chat: the one of the document it is about, or the standing one. The one owner of this id. */
        internal fun conversationIdFor(documentId: String?): String = documentId?.let { "conv-$it" } ?: STANDALONE_CONVERSATION_ID

        /** The navigation argument of "Help me fill it": open the document chat with the form fill started. */
        const val ARG_FILL = "fill"

        /** Set once the chat was opened, so a recreated view model does not start the fill again. */
        private const val STATE_FILL_STARTED = "fillStarted"
    }
}

/**
 * The fill card's data: the fill and its fields as stored now.
 * [openFieldId] is the field the conversation is asking about, if any.
 */
data class FillCardState(val fill: FormFill, val fields: List<FormField>) {
    val progress: FillProgress get() = FillProgress.of(fields)
    val openFieldId: String? get() = fill.awaiting?.fieldId
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
    /** The pictures attached to the message being written (files of the app's chat-attachments folder), oldest first. */
    val attachments: List<String> = emptyList(),
    /** The chat model can look at pictures: the attach button is shown only then. */
    val imageInputSupported: Boolean = false,
    /** The pages of this letter that can be attached; loaded when the attach menu opens. */
    val attachablePages: List<AttachablePage> = emptyList(),
    /**
     * Set to `System.currentTimeMillis()` every time [ChatViewModel.sendMessage] runs —
     * never derived from [messages] itself. See [ChatViewModel.sendMessage]'s doc: this is
     * what makes the transcript always jump to the bottom on send (defect 2), independent
     * of `followBottom`.
     */
    val lastSentAt: Long = 0L,
    /**
     * The first message the model reads when its conversation is built from the transcript (the start of the continuity tail); the
     * list shows a quiet divider above it when there are older messages. Null for no tail.
     */
    val contextStartMessageId: String? = null,
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
    /** The app's own wording of the failure (a string resource); when set it is shown instead of [message]. */
    @androidx.annotation.StringRes val messageRes: Int? = null,
)

/** The error the chat shows for a failed turn; a model that cannot chat gets the app's own translated wording and a way out. */
internal fun chatErrorOf(turn: ChatTurn.Failed): ChatError = ChatError(
    message = turn.message,
    action = turn.action,
    messageRes = if (turn.action == ChatErrorAction.CHOOSE_CHAT_MODEL) R.string.chat_error_model_cannot_chat else null,
)

/** A page of the current letter the user can attach to a message as a picture. */
data class AttachablePage(val pageNumber: Int, val imagePath: String)

data class ChatMessage(
    val id: String = "",
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    /** The model's reasoning trace for this reply, if any — display-only, see [com.postsaimanager.core.model.AiMessage]. */
    val thinking: String? = null,
    val thinkingDurationMs: Long? = null,
    /** The pictures the user attached to this message (files), for a user message. */
    val images: List<String> = emptyList(),
    /** The tools the model used for this reply (skills loaded, actions proposed, scripts run), for the progress panel. */
    val toolSteps: List<com.postsaimanager.core.domain.skills.ToolStep> = emptyList(),
    /** True for a reply the user stopped, or one that failed mid-stream. See [com.postsaimanager.core.model.AiMessage.incomplete]. */
    val incomplete: Boolean = false,
    /** With [incomplete]: the reply hit its token cap ("Answer was cut off") rather than being stopped. */
    val cutOff: Boolean = false,
    /** The passages this reply cites/was grounded on, for [ChatScreen]'s citation chips (4.3). */
    val sources: List<ChatSource> = emptyList(),
    /** The payload of a form-conversation message (a status line, a question with chips, the fill card); null for a chat message. */
    val form: FormMessage? = null,
) {
    /**
     * A finished reply with no words at all: the model answered with an action only (a skill's card) and said nothing around it.
     * The card is the answer, so the screen shows no empty bubble (nor its citation chips) for it.
     */
    val isEmptyReply: Boolean
        get() = !isUser && form == null && text.isBlank() && thinking.isNullOrBlank() && !incomplete
}

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
