package com.postsaimanager.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.postsaimanager.core.domain.usecase.CitationParser
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import com.postsaimanager.core.domain.usecase.ChatErrorAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.postsaimanager.core.domain.ai.MessageImages
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.MarkdownText
import com.postsaimanager.core.designsystem.component.byContentDirection
import com.postsaimanager.core.designsystem.component.PagePreviewDialog
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.component.ReportAnswerButton
import com.postsaimanager.core.designsystem.component.ReportAnswerDialog
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.FormMessageKind
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The tag of the divider above the messages the assistant still reads, for tests. */
internal const val CONTEXT_DIVIDER_TAG = "contextDivider"

/** A quiet line between the older messages (shown, but not read by the assistant) and the ones it still reads. */
@Composable
internal fun ContextDivider(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.chat_context_divider),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).testTag(CONTEXT_DIVIDER_TAG),
    )
}

/** Slack (px) for "is the last item basically fully visible" — avoids flicker at the edge. */
private const val BOTTOM_SLACK_PX = 24

/** Coalesces a burst of token updates into one scroll instead of one per token. */
private const val SCROLL_THROTTLE_MS = 80L

/** The tags of the new-chat button and its dialog's confirm button, for tests. */
const val NEW_CHAT_TAG = "new-chat"
const val NEW_CHAT_CONFIRM_TAG = "new-chat-confirm"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    documentId: String?,
    onNavigateBack: () -> Unit,
    onManageModelsClick: () -> Unit = {},
    /** "Open document" in the citation preview — navigate to that source's full document
     * detail, at the page being previewed. Tapping a chip itself only opens the preview. */
    onSourceClick: (ChatSource) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    actionCardsViewModel: ActionCardsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actionCards by actionCardsViewModel.cards.collectAsStateWithLifecycle()
    val modelSheetState by viewModel.modelSheetState.collectAsStateWithLifecycle()
    val suggestedQuestions by viewModel.suggestedQuestions.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val fillCard by viewModel.fillCard.collectAsStateWithLifecycle()
    val searchModelHintVisible by viewModel.searchModelHintVisible.collectAsStateWithLifecycle()
    // Coming back from the models screen: the search model may be installed by now.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshSearchModelHint()
        viewModel.refreshImageSupport()
        onPauseOrDispose {}
    }
    // The chat's pre-warm (the model, the conversation's prefill) runs only while the screen is in the foreground: it starts when
    // the chat opens or returns, and stops when the app goes to the background or the chat closes.
    LifecycleStartEffect(viewModel) {
        viewModel.startWarmUp()
        onStopOrDispose {
            viewModel.stopWarmUp()
            viewModel.onScreenHidden()
        }
    }
    var inputText by rememberSaveable { mutableStateOf("") }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    var confirmNewChat by rememberSaveable { mutableStateOf(false) }
    // A picture of a message, opened larger.
    var viewedImage by remember { mutableStateOf<String?>(null) }
    // The Gallery's picker: the system photo picker, no storage permission.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MessageImages.MAX_PER_MESSAGE)) { uris ->
        uris.forEach { viewModel.attachImage(it.toString()) }
    }
    // The camera: the photo is taken into a temporary file in the app's cache (shared with the camera app through the
    // FileProvider), copied into the chat's storage, and the temporary file deleted.
    val context = LocalContext.current
    var cameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        cameraFilePath?.let { viewModel.attachCameraPhoto(File(it), taken) }
        cameraFilePath = null
    }
    fun launchCamera() {
        val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "photo-${System.nanoTime()}.jpg")
        cameraFilePath = file.absolutePath
        takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else viewModel.onCameraPermissionDenied()
    }
    var showNoVisionSheet by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 5.1: "Copy" needs only a brief, non-blocking confirmation — a Snackbar rather than a
    // dialog, and one already dismissing itself is replaced rather than queued behind.
    fun copyToClipboard(text: String) {
        clipboardManager.setText(AnnotatedString(text))
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_to_clipboard))
        }
    }

    // "Follow" mirrors what every streaming chat UI does: stick to the bottom while new
    // content arrives, but the instant the user scrolls up, stop — nothing is more hostile
    // than fighting a person's own scroll gesture mid-read.
    var followBottom by remember { mutableStateOf(true) }

    // The list is `reverseLayout = true` with newest content at index 0, so "at bottom"
    // is simply "resting at the start of the list" — no item-height math, and no
    // mid-bubble false negatives while the last message is still growing.
    val isAtBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= BOTTOM_SLACK_PX
        }
    }

    // Any user-initiated drag disables follow immediately, whether or not it ends up
    // actually leaving the bottom — a person is reading, not asking to be interrupted.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followBottom = false
        }
    }

    // Reaching the bottom again — by hand or via the jump button — resumes following.
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) followBottom = true
    }

    // Debounced, not per-token: a burst of tokens collapses into one scroll via
    // `collectLatest`, so this never fires more than once every [SCROLL_THROTTLE_MS].
    // The jump is an instant `scrollToItem(0)`, not animated — with `reverseLayout`,
    // index 0 IS the bottom, so this never fights a mid-stream layout the way animating
    // to the last item's *top* used to.
    LaunchedEffect(listState) {
        snapshotFlow {
            // A card arriving with the reply grows the transcript like a message does, so it follows the bottom the same way.
            listOf(uiState.messages.size, uiState.streamingText.length, uiState.thinkingText.length, actionCards.size)
        }.collectLatest {
            if (!followBottom) return@collectLatest
            delay(SCROLL_THROTTLE_MS)
            listState.scrollToItem(0)
        }
    }

    // The cards the model proposes are checked against the user's own words too: a value they wrote is theirs.
    LaunchedEffect(uiState.messages) {
        actionCardsViewModel.updateUserMessages(uiState.messages.filter { it.isUser }.map { it.text })
    }

    // Defect 2: sending a message must ALWAYS snap the transcript to the bottom, even if the
    // user had scrolled away to read older messages — a person who just tapped Send wants to
    // see what they sent, full stop. Keyed on `uiState.lastSentAt` (a ViewModel-owned send
    // event), not on `uiState.messages.size` — a message arriving for any other reason (e.g.
    // history restore) must not force-scroll and fight a person who is deliberately reading
    // up in the transcript.
    LaunchedEffect(uiState.lastSentAt) {
        if (uiState.lastSentAt == 0L) return@LaunchedEffect
        followBottom = true
        listState.scrollToItem(0)
    }

    // Layered over the chat, not navigated to: the transcript (and `listState`) underneath
    // stays composed, so closing returns to exactly where the user was reading.
    preview?.let { state ->
        PagePreviewDialog(
            title = state.source.title,
            preview = state.preview,
            loading = state.loading,
            initialPageIndex = state.initialPageIndex,
            onClose = viewModel::closePreview,
            onOpenDocument = { pageNumber ->
                viewModel.closePreview()
                onSourceClick(
                    state.source.copy(
                        documentId = state.preview?.documentId ?: state.source.documentId,
                        pageNumber = pageNumber,
                        title = state.preview?.title ?: state.source.title,
                    ),
                )
            },
        )
    }

    viewedImage?.let { path -> ImageViewerDialog(path = path, onDismiss = { viewedImage = null }) }

    if (confirmNewChat) {
        AlertDialog(
            onDismissRequest = { confirmNewChat = false },
            title = { Text(stringResource(R.string.chat_new_chat_title)) },
            text = { Text(stringResource(R.string.chat_new_chat_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNewChat = false
                        viewModel.newChat()
                    },
                    modifier = Modifier.testTag(NEW_CHAT_CONFIRM_TAG),
                ) { Text(stringResource(R.string.chat_new_chat_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmNewChat = false }) { Text(stringResource(R.string.chat_new_chat_cancel)) } },
        )
    }

    if (showNoVisionSheet) {
        NoVisionSheet(
            onSwitchModel = {
                showNoVisionSheet = false
                showModelSheet = true
            },
            onDismiss = { showNoVisionSheet = false },
        )
    }

    if (showModelSheet) {
        ModelConfigBottomSheet(
            state = modelSheetState,
            onSelectModel = viewModel::selectModel,
            onManageModelsClick = {
                showModelSheet = false
                onManageModelsClick()
            },
            onSetInferenceSetting = viewModel::setInferenceSetting,
            onResetInference = viewModel::resetInference,
            onTryGpuAgain = viewModel::tryGpuAgain,
            onDismiss = { showModelSheet = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PamTopAppBar(
                title = stringResource(if (documentId != null) R.string.chat_title_document else R.string.chat_title_assistant),
                onNavigateBack = onNavigateBack,
                actions = {
                    // Debug builds only: try an action card before the model proposes any.
                    DebugActionMenu(
                        onPropose = { action ->
                            actionCardsViewModel.propose(action, userMessages = uiState.messages.filter { it.isUser }.map { it.text })
                        },
                    )
                    // The Gallery's "new session": clears this chat (after a question) and starts over.
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = { confirmNewChat = true }, modifier = Modifier.testTag(NEW_CHAT_TAG)) {
                            Icon(Icons.Filled.AddComment, contentDescription = stringResource(R.string.chat_new_chat))
                        }
                    }
                    ModelHeaderChip(
                        state = modelSheetState,
                        onClick = { showModelSheet = true },
                        modifier = Modifier.padding(end = 8.dp),
                        isPrimingConversation = uiState.isPrimingConversation,
                        isWaitingForDocument = uiState.isWaitingForDocument,
                    )
                },
            )
        },
        bottomBar = {
          Column {
            if (searchModelHintVisible) {
                SearchModelHintBar(
                    onInstall = {
                        viewModel.dismissSearchModelHint()
                        onManageModelsClick()
                    },
                    onDismiss = viewModel::dismissSearchModelHint,
                )
            }
            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                // The composer never locks: while generating, Send becomes Stop instead of
                // disabling input — the user can always cancel and type something else.
                isGenerating = uiState.isProcessing,
                onStop = viewModel::stopGeneration,
                attachments = uiState.attachments,
                imageInputSupported = uiState.imageInputSupported,
                attachablePages = uiState.attachablePages,
                onPickPhotos = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onTakePhoto = {
                    // The permission is asked only now, when the user chooses the camera.
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCamera()
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                onAttachUnsupported = { showNoVisionSheet = true },
                onOpenAttachMenu = viewModel::loadAttachablePages,
                onAttachPage = viewModel::attachPage,
                onRemoveAttachment = viewModel::removeAttachment,
            )
          }
        },
        modifier = modifier.imePadding(),
    ) { innerPadding ->
        if (uiState.messages.isEmpty() && actionCards.isEmpty()) {
            // Welcome state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = PamIcons.AiChat,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.chat_empty_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(if (documentId != null) R.string.chat_empty_hint_document else R.string.chat_empty_hint_all),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))

                // 5.2: starter questions the model wrote for the document — see
                // ChatViewModel.suggestedQuestions / ObserveSuggestedQuestionsUseCase.
                suggestedQuestions.forEach { suggestion ->
                    Surface(
                        onClick = {
                            viewModel.sendMessage(suggestion)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            text = suggestion,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            // The live reply: its text as it streams, or (before the first token) the status line / typing indicator — a
            // spinner alongside flowing text would just read as "still stuck".
            val live = uiState.streamingText.isNotEmpty() || (uiState.isProcessing && !uiState.isThinkingActive)
            // The live reasoning trace is shown the moment thinking starts, and kept (collapsed) once the answer streams.
            val thinking = uiState.thinkingText.isNotEmpty() || uiState.isThinkingActive
            // One list for everything, newest first (the list is reversed: index 0 is the bottom). Each action card sits right under
            // the reply that proposed it and scrolls with it; a card whose reply is not stored yet sits under the live reply.
            val entries = remember(uiState.messages, actionCards, uiState.error != null, live, thinking, uiState.contextStartMessageId) {
                ChatTimeline.build(
                    uiState.messages,
                    actionCards,
                    error = uiState.error != null,
                    live = live,
                    thinking = thinking,
                    contextStartId = uiState.contextStartMessageId,
                )
            }
            // 5.1: regenerate is offered only on the LATEST assistant reply — a finished one, not the live streaming bubble
            // (a separate row, never part of `uiState.messages` until it is persisted and reloaded).
            val latestAssistantId = uiState.messages.lastOrNull { !it.isUser && it.form == null }?.id
            // The form conversation: only the newest card is shown in full, and only the open question's chips are live.
            val latestCardId = uiState.messages.lastOrNull { it.form?.kind == FormMessageKind.CARD }?.id
            // The one pending question: the newest of what waits for an answer (a question, a status line with chips) or was an
            // answer (the user's message). A question or Continue / Start over that something came after is stale: disabled.
            val pendingChipsId = uiState.messages.lastOrNull { isPendingChipsMessage(it) }?.id
            ChatTranscript(
                entries = entries,
                listState = listState,
                modifier = Modifier.padding(innerPadding),
                // Leaving the bottom by tapping the "actions waiting" pill: the stream must not drag the list back down.
                onJumpToCard = { followBottom = false },
                // Never fights the user's own scroll — only appears once they've scrolled
                // away from live content, and both tapping it and scrolling back down
                // resume following (see `followBottom` above).
                bottomOverlay = {
                    AnimatedVisibility(
                        visible = !followBottom,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        FloatingActionButton(
                            onClick = {
                                // Flip the flag first so the FAB fades out immediately, rather
                                // than lingering for the duration of the scroll animation.
                                followBottom = true
                                coroutineScope.launch { listState.animateScrollToItem(0) }
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_jump_to_latest))
                        }
                    }
                },
            ) { entry ->
                when (entry) {
                    TimelineEntry.Error -> uiState.error?.let { error ->
                        ChatErrorCard(
                            error = error,
                            onDismiss = viewModel::dismissError,
                            onRetry = viewModel::retry,
                            onManageModelsClick = onManageModelsClick,
                        )
                    }

                    TimelineEntry.Live ->
                        if (uiState.streamingText.isNotEmpty()) {
                            ChatBubble(message = ChatMessage(text = uiState.streamingText, isUser = false))
                        } else {
                            uiState.statusText?.let { status ->
                                Text(
                                    text = ChatStatusText.localized(status) { context.getString(it.res) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            } ?: TypingIndicator()
                        }

                    TimelineEntry.ContextDivider -> ContextDivider()

                    TimelineEntry.Thinking ->
                        MessageBodyThinking(
                            thinkingText = uiState.thinkingText,
                            inProgress = uiState.isThinkingActive,
                            durationMs = uiState.thinkingDurationMs,
                        )

                    // The action a skill proposed, under the reply that proposed it: nothing runs until the user taps Open.
                    is TimelineEntry.Card -> {
                        val card = entry.card
                        ActionCard(
                            state = card,
                            onOpen = { actionCardsViewModel.open(card.id) },
                            onEdit = { actionCardsViewModel.startEditing(card.id) },
                            onCancel = { actionCardsViewModel.cancel(card.id) },
                            onRestore = { actionCardsViewModel.restore(card.id) },
                            onDoAgain = { actionCardsViewModel.doAgain(card.id) },
                            onChange = { field, text -> actionCardsViewModel.changeField(card.id, field, text) },
                        )
                    }

                    is TimelineEntry.Message -> {
                        val message = entry.message
                        val form = message.form
                        if (form != null) {
                            FormMessageItem(
                                message = message,
                                form = form,
                                fillCard = fillCard,
                                isLatestCard = message.id == latestCardId,
                                chipsEnabled = message.id == pendingChipsId && !uiState.isProcessing,
                                onChip = viewModel::onFormChip,
                                onShowOnPage = viewModel::openFieldPreview,
                                onCopy = ::copyToClipboard,
                                onOpenModels = onManageModelsClick,
                            )
                        } else {
                            ChatBubble(
                                message = message,
                                documentChat = documentId != null,
                                onRetry = { viewModel.retryMessage(message) },
                                onSourceClick = viewModel::openPreview,
                                onCopy = { copyToClipboard(message.text) },
                                onRegenerate = { viewModel.regenerate() },
                                onImageClick = { path -> viewedImage = path },
                                isLatestAssistantReply = !message.isUser &&
                                    message.id.isNotEmpty() &&
                                    message.id == latestAssistantId &&
                                    !uiState.isProcessing,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    documentChat: Boolean = false,
    onRetry: () -> Unit = {},
    onSourceClick: (ChatSource) -> Unit = {},
    /** 5.1: copies [ChatMessage.text] — the answer only, never [ChatMessage.thinking]. */
    onCopy: () -> Unit = {},
    /** 5.1: re-runs this reply — only ever wired when [isLatestAssistantReply] is true. */
    onRegenerate: () -> Unit = {},
    /** True only for the newest, finished assistant reply — see `ChatScreen`'s call site. */
    isLatestAssistantReply: Boolean = false,
    /** A picture of this (user) message was tapped: it is shown larger. */
    onImageClick: (String) -> Unit = {},
) {
    val isUser = message.isUser
    var reporting by rememberSaveable { mutableStateOf(false) }
    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        // A persisted reply that thought before answering shows its trace collapsed to a
        // "Thought for N s" header, right above the bubble — expandable, never streaming.
        if (!isUser && message.thinking != null) {
            MessageBodyThinking(
                thinkingText = message.thinking,
                inProgress = false,
                durationMs = message.thinkingDurationMs,
                modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 280.dp),
            )
        }
        // What the model did before it answered (skills loaded, actions proposed, scripts run), collapsed to a header.
        if (!isUser && message.toolSteps.isNotEmpty()) {
            MessageBodyCollapsableProgressPanel(
                steps = message.toolSteps,
                modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 280.dp),
            )
            // A JS skill's own page, shown offline right under the steps.
            message.toolSteps.mapNotNull { it.webview }.forEach { webview ->
                MessageBodyWebview(webview = webview, modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 280.dp))
            }
        }
        // The pictures the user attached, above their words.
        if (isUser && message.images.isNotEmpty()) {
            MessageBodyImage(
                paths = message.images,
                onImageClicked = { index -> onImageClick(message.images[index]) },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        // An action-only reply is its steps and its card: no empty bubble.
        if (!isUser && message.text.isBlank() && message.toolSteps.isNotEmpty() && !message.incomplete) return@Column
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PamIcons.AiChat,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                modifier = Modifier.widthIn(max = 280.dp),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp,
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                if (isUser) {
                    // User input is never markdown — show it verbatim.
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium.byContentDirection(),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    // Renders live for the streaming bubble too — the parser recovers from
                    // an unbalanced `**`/code fence mid-stream rather than throwing.
                    MarkdownText(
                        // Raw "[p.6]" markers are noise once the chips carry the same
                        // information; the live bubble (no id yet) is stripped too, so text
                        // does not reflow when the chips appear.
                        text = if (message.sources.isNotEmpty() || message.id.isEmpty()) {
                            CitationParser.stripMarkers(message.text)
                        } else {
                            message.text
                        },
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 4.3: what grounded this answer, as tappable chips — only for a finished assistant
        // reply with something to show (a streaming bubble, built from `ChatMessage(text=…)`
        // with no `id`, never has sources yet; see the streaming-bubble call site above).
        if (!isUser && message.sources.isNotEmpty()) {
            // A wrapping FlowRow, never a clipped horizontal scroller, and above the
            // copy/regenerate row below.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.sources.forEach { source ->
                    SourceChip(source = source, documentChat = documentChat, onClick = { onSourceClick(source) })
                }
            }
        }

        // Defect 3: a stopped/crashed reply keeps its partial text (see `ChatBubble` above,
        // unchanged) rather than being deleted, marked with a small "Stopped" caption rather
        // than looking like a normal finished reply — and offers Retry rather than a dead
        // end. Never shown for a user bubble; `AiMessage.incomplete` is assistant-only by
        // construction (`SendChatMessageUseCase` never sets it on a user message).
        if (!isUser && message.incomplete) {
            Row(
                modifier = Modifier.padding(start = 40.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(if (message.cutOff) R.string.chat_answer_cut_off else R.string.chat_answer_stopped),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.chat_action_retry), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // The action the reply proposed was refused (no card): say so next to the words, and offer to try again.
        if (message.actionRefused) {
            Row(
                modifier = Modifier.padding(start = 40.dp, top = 2.dp).testTag("actionNotSet"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_action_not_set),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.chat_action_retry), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // 5.1: Copy/Regenerate on a finished reply only — a stopped/crashed one already
        // shows its own Stopped/Retry row above, and copying or regenerating half an answer
        // is not a real action. Regenerate is further limited to the LATEST assistant reply
        // ([isLatestAssistantReply], decided by `ChatScreen`) — anything older would delete a
        // reply with turns still after it in the transcript.
        if (!isUser && !message.incomplete && message.text.isNotBlank()) {
            Row(
                modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy_answer),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isLatestAssistantReply) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.chat_regenerate_answer),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Google Play's generative-AI policy: every AI answer can be reported (an e-mail draft the user sends).
                ReportAnswerButton(onClick = { reporting = true }, modifier = Modifier.size(32.dp))
            }
            if (reporting) {
                ReportAnswerDialog(
                    answerText = CitationParser.stripMarkers(message.text),
                    onDismiss = { reporting = false },
                )
            }
        }
    }
}

/**
 * One citation chip under an assistant reply (4.3) — "Page 2" (or "Excerpt 3" when the
 * source predates page-aware chunking, see [MessageSource.pageNumber][
 * com.postsaimanager.core.model.MessageSource.pageNumber]) in a document chat, where the
 * document is already the one thing being discussed; "<title> · p.2" in a standalone chat,
 * which can cite several documents in one answer and so needs to say which.
 */
@Composable
private fun SourceChip(source: ChatSource, documentChat: Boolean, onClick: () -> Unit) {
    val page = source.pageNumber
    val title = source.title
    val label = when {
        // A trashed/permanently-deleted source: nothing left to navigate a tap to, in
        // either chat type — see ChatSource.documentDeleted's KDoc.
        source.documentDeleted -> stringResource(R.string.chat_source_deleted)
        documentChat -> if (page != null) stringResource(R.string.chat_source_page, page) else stringResource(R.string.chat_source_excerpt)
        else -> {
            val name = title ?: stringResource(R.string.chat_source_untitled)
            if (page != null) stringResource(R.string.chat_source_title_page, name, page) else stringResource(R.string.chat_source_title_excerpt, name)
        }
    }
    val documentName = title ?: stringResource(R.string.chat_source_this_document)
    val description = if (page != null) {
        stringResource(R.string.chat_source_description_page, page, documentName)
    } else {
        stringResource(R.string.chat_source_description_excerpt, documentName)
    }
    AssistChip(
        onClick = onClick,
        enabled = !source.documentDeleted,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(PamIcons.Documents, contentDescription = null, modifier = Modifier.size(14.dp))
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .heightIn(min = 28.dp)
            .semantics {
                contentDescription = description
            },
    )
}

/** A picture of a message, shown larger over the chat; a tap anywhere closes it. */
@Composable
private fun ImageViewerDialog(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f),
        ) {
            AsyncImage(
                model = java.io.File(path),
                contentDescription = stringResource(R.string.chat_image_description),
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PamIcons.AiChat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    // Generic "generation is starting" indicator — distinct from the
                    // reasoning-trace ThinkingCard, which has its own "Thinking…" header.
                    text = stringResource(R.string.chat_working),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


/**
 * A failure the user can act on.
 *
 * Chat's most common error by far is "no model installed", which is entirely fixable — so
 * it offers the fix rather than merely reporting the problem.
 */
@Composable
private fun ChatErrorCard(
    error: ChatError,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onManageModelsClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = error.messageRes?.let { stringResource(it) } ?: error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (error.action == ChatErrorAction.INSTALL_MODEL) {
                // A dead-end plain-text hint used to sit here. This is the actual fix,
                // one tap away — the model picker already lives behind this callback.
                FilledTonalButton(onClick = onManageModelsClick) {
                    Text(stringResource(R.string.chat_error_get_model))
                }
            }
            if (error.action == ChatErrorAction.CHOOSE_CHAT_MODEL) {
                FilledTonalButton(onClick = onManageModelsClick) {
                    Text(stringResource(R.string.chat_error_open_ai_models))
                }
            }
            Row {
                // Whatever was already produced stays in the transcript as its own
                // message — this only re-sends the user's text, exactly what failed.
                if (error.action == ChatErrorAction.RETRY || error.action == ChatErrorAction.MODEL_DOWNLOADING) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_action_retry)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_error_dismiss)) }
            }
        }
    }
}
