package com.postsaimanager.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The tag of the "N actions waiting" pill, for tests. */
internal const val ACTION_WAITING_PILL_TAG = "actionWaitingPill"

/**
 * The conversation as one reversed list of [entries] (newest first, see [ChatTimeline]): messages, the live reply and the action
 * cards all scroll together. While a pending card is scrolled out of view, a pill just above the composer says how many wait,
 * and tapping it scrolls to the newest one ([onJumpToCard] is called first, so the screen stops following the bottom).
 */
@Composable
internal fun ChatTranscript(
    entries: List<TimelineEntry>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onJumpToCard: () -> Unit = {},
    bottomOverlay: @Composable ColumnScope.() -> Unit = {},
    entryContent: @Composable (TimelineEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentEntries by rememberUpdatedState(entries)
    // From the list's own layout (what is on screen now), not a timer; nothing is "off screen" before the first layout.
    val waiting by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) 0 else ChatTimeline.waitingOffScreen(currentEntries, visible.map { it.key }.toSet())
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Index 0 renders at the BOTTOM and increasing indices move upward, so `scrollToItem(0)` is the true bottom.
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.key }) { entry -> entryContent(entry) }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            bottomOverlay()
            if (waiting > 0) {
                ActionWaitingPill(
                    count = waiting,
                    onClick = {
                        val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                        val index = ChatTimeline.newestWaitingOffScreen(currentEntries, visible) ?: return@ActionWaitingPill
                        onJumpToCard()
                        scope.launch { listState.animateScrollToItem(index) }
                    },
                )
            }
        }
    }
}

/** "1 action waiting" / "2 actions waiting": a small pill, not a bar, so it never covers the conversation. */
@Composable
internal fun ActionWaitingPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag(ACTION_WAITING_PILL_TAG),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Text(
            text = pluralStringResource(R.plurals.action_waiting_pill, count, count),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
