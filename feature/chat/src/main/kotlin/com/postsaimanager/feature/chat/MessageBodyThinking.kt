/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's ui/common/chat/MessageBodyThinking.kt (v1.0.20). The Gallery's
// R.string and MarkdownText are the app's string resources and designsystem MarkdownText (right-to-left aware); the long-press copy
// is not taken (the app never copies a reasoning trace, see ChatMessage.thinking). It starts COLLAPSED and stays as the user leaves
// it, also while the model is thinking (the Gallery expands it by itself); the title says "Thinking..." while live and
// "Thought for N s" after.

package com.postsaimanager.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.MarkdownText

/** The tag of the header of [MessageBodyThinking], for tests. */
const val THINKING_HEADER_TAG = "thinking-header"

/** The tag of the reasoning text of [MessageBodyThinking], for tests. */
const val THINKING_TEXT_TAG = "thinking-text"

@Composable
fun MessageBodyThinking(
    thinkingText: String,
    inProgress: Boolean,
    durationMs: Long?,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.testTag(THINKING_HEADER_TAG).clickable { isExpanded = !isExpanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = when {
                    inProgress -> stringResource(R.string.chat_thinking_live)
                    durationMs != null -> stringResource(R.string.chat_thought_for, formatThinkingDuration(durationMs, LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()))
                    else -> stringResource(R.string.chat_thinking_done)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(if (isExpanded) R.string.chat_thinking_hide else R.string.chat_thinking_show),
            )
        }

        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            val lineColor = MaterialTheme.colorScheme.outlineVariant
            Column(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp, start = 8.dp)
                    .drawBehind {
                        // The rule sits on the leading edge: the left in left-to-right text, the right in right-to-left.
                        val x = if (layoutDirection == LayoutDirection.Rtl) size.width else 0f
                        drawLine(color = lineColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                    .padding(start = 12.dp)
                    .testTag(THINKING_TEXT_TAG),
            ) {
                MarkdownText(
                    text = thinkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "3.2s" under ten seconds, "27s" above. */
internal fun formatThinkingDuration(durationMs: Long, locale: java.util.Locale = java.util.Locale.ROOT): String {
    val seconds = durationMs / 1000.0
    return if (seconds < 10) String.format(locale, "%.1fs", seconds) else String.format(locale, "%ds", seconds.toInt())
}
