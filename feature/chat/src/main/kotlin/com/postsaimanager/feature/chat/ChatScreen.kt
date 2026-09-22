package com.postsaimanager.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.MarkdownText
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Slack (px) for "is the last item basically fully visible" — avoids flicker at the edge. */
private const val BOTTOM_SLACK_PX = 24

/** Coalesces a burst of token updates into one scroll instead of one per token. */
private const val SCROLL_THROTTLE_MS = 80L

/** Bounded height for the thinking card's own inner scroll region. */
private const val THINKING_CARD_MAX_HEIGHT_DP = 160

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    documentId: String?,
    onNavigateBack: () -> Unit,
    onManageModelsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modelSheetState by viewModel.modelSheetState.collectAsStateWithLifecycle()
    var inputText by rememberSaveable { mutableStateOf("") }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

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
            Triple(uiState.messages.size, uiState.streamingText.length, uiState.thinkingText.length)
        }.collectLatest {
            if (!followBottom) return@collectLatest
            delay(SCROLL_THROTTLE_MS)
            listState.scrollToItem(0)
        }
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
        topBar = {
            PamTopAppBar(
                title = if (documentId != null) "Document Chat" else "AI Assistant",
                onNavigateBack = onNavigateBack,
                actions = {
                    ModelHeaderChip(
                        state = modelSheetState,
                        onClick = { showModelSheet = true },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
        bottomBar = {
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
            )
        },
        modifier = modifier.imePadding(),
    ) { innerPadding ->
        if (uiState.messages.isEmpty()) {
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
                    text = "Ask me about your documents",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (documentId != null) "I can help you understand this document, find key information, and draft responses."
                    else "Select a document or ask me a general question about your mail management.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Quick action chips
                val suggestions = if (documentId != null) listOf(
                    "Summarize this document",
                    "What are the deadlines?",
                    "Draft a response",
                    "Who is the sender?",
                ) else listOf(
                    "Show recent deadlines",
                    "Summarize my unread mail",
                    "Help me organize my documents",
                )
                suggestions.forEach { suggestion ->
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
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // With `reverseLayout = true`, index 0 renders at the BOTTOM and
                    // increasing indices move upward — so the most recent content
                    // (error, then the live streaming bubble/status, then the thinking
                    // trace) is declared first, and persisted messages follow newest-first.
                    // This is what makes `scrollToItem(0)` land exactly at the true bottom
                    // regardless of how tall the last bubble is.
                    uiState.error?.let { error ->
                        item {
                            ChatErrorCard(
                                error = error,
                                onDismiss = viewModel::dismissError,
                                onRetry = viewModel::retry,
                            )
                        }
                    }

                    // Stream the reply into a live bubble. Only fall back to the typing
                    // indicator before the first token arrives — once text is flowing, a
                    // spinner alongside it just reads as "still stuck".
                    if (uiState.streamingText.isNotEmpty()) {
                        item {
                            ChatBubble(
                                message = ChatMessage(
                                    text = uiState.streamingText,
                                    isUser = false,
                                ),
                            )
                        }
                    } else if (uiState.isProcessing && !uiState.isThinkingActive) {
                        item {
                            uiState.statusText?.let { status ->
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            } ?: TypingIndicator()
                        }
                    }

                    // The live reasoning trace gets its own bounded, auto-following card —
                    // shown the moment thinking starts, and kept around (collapsed) once the
                    // answer starts streaming, so it never just vanishes mid-turn.
                    if (uiState.thinkingText.isNotEmpty() || uiState.isThinkingActive) {
                        item {
                            ThinkingCard(
                                thinkingText = uiState.thinkingText,
                                isActive = uiState.isThinkingActive,
                                durationMs = uiState.thinkingDurationMs,
                            )
                        }
                    }

                    items(
                        uiState.messages.asReversed(),
                        key = { it.id.ifEmpty { it.timestamp.toString() } },
                    ) { message ->
                        ChatBubble(message = message)
                    }
                }

                // Never fights the user's own scroll — only appears once they've scrolled
                // away from live content, and both tapping it and scrolling back down
                // resume following (see `followBottom` above).
                AnimatedVisibility(
                    visible = !followBottom,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
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
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    onStop: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Stays enabled and editable even while generating — the user can queue up
            // their next thought, or just cancel via the button on the right.
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                maxLines = 4,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isGenerating) {
                // Send → Stop while a reply streams, rather than disabling the button —
                // cancelling is always one tap away, never a dead end.
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop generating",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank(),
                ) {
                    Icon(
                        imageVector = PamIcons.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.isUser
    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        // A persisted reply that thought before answering shows its trace collapsed to a
        // "Thought for N s" header, right above the bubble — expandable, never streaming.
        if (!isUser && message.thinking != null) {
            ThinkingCard(
                thinkingText = message.thinking,
                isActive = false,
                durationMs = message.thinkingDurationMs,
                modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 280.dp),
            )
        }
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    // Renders live for the streaming bubble too — the parser recovers from
                    // an unbalanced `**`/code fence mid-stream rather than throwing.
                    MarkdownText(
                        text = message.text,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * A collapsible card for a model's reasoning trace — bounded height with its own inner
 * scroll while [isActive], a chevron to maximize/minimize any time, and a header that
 * reads "Thinking…" while live and "Thought for N s" once [durationMs] is known.
 *
 * Expansion state resets whenever [isActive] flips: starts expanded the moment thinking
 * begins (so the user sees it happening, not a flat header), and collapses the instant the
 * answer starts streaming or a persisted message is shown — matching the spec's "auto-
 * collapse to header-only when the answer starts streaming" and "persisted messages show
 * the collapsed header, expandable". A manual tap on the chevron always overrides this
 * within one streaming session.
 */
@Composable
private fun ThinkingCard(
    thinkingText: String,
    isActive: Boolean,
    durationMs: Long?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(isActive) { mutableStateOf(isActive) }
    val scrollState = rememberScrollState()

    // Follows the newest thinking text while live — the same "stick to the bottom of what's
    // streaming" idea as the main transcript, scoped to this card's own inner scroll.
    LaunchedEffect(thinkingText, isActive, expanded) {
        if (isActive && expanded) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    PulsingDot(modifier = Modifier.padding(end = 8.dp))
                } else {
                    Icon(
                        imageVector = PamIcons.AiChat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = when {
                        isActive -> "Thinking…"
                        durationMs != null -> "Thought for ${formatThinkingDuration(durationMs)}"
                        else -> "Thoughts"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse thinking" else "Expand thinking",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = thinkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = THINKING_CARD_MAX_HEIGHT_DP.dp)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** A small breathing dot next to "Thinking…" — enough motion to read as live, no more. */
@Composable
private fun PulsingDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-pulse-alpha",
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

private fun formatThinkingDuration(durationMs: Long): String {
    val seconds = durationMs / 1000.0
    return if (seconds < 10) "%.1fs".format(seconds) else "${seconds.toInt()}s"
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
                    text = "Working…",
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
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (error.action == ChatErrorAction.INSTALL_MODEL) {
                Text(
                    text = "Settings → AI models",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Row {
                // Whatever was already produced stays in the transcript as its own
                // message — this only re-sends the user's text, exactly what failed.
                if (error.action == ChatErrorAction.RETRY) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
