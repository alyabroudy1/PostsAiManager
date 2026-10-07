/*
 * Copyright 2026 Google LLC
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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's ui/common/chat/MessageBodyCollapsableProgressPanel.kt
// (v1.0.20). The Gallery's ChatMessageCollapsableProgressPanel (a live message with a spinner, logs and a console viewer) becomes
// the steps the reply stored (`ToolStep`): the panel shows what the model did (loaded a skill, proposed an action, ran a
// script) after the reply, and survives reopening the chat. Left out: the spinner/title animation of a live panel, the console
// logs viewer. Texts are string resources; the rounded steps and the expand/collapse header are the Gallery's.

package com.postsaimanager.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.byContentDirection
import com.postsaimanager.core.domain.skills.ToolStep
import com.postsaimanager.core.domain.skills.ToolStepKind

/** The tag of the header of [MessageBodyCollapsableProgressPanel], for tests. */
const val PROGRESS_HEADER_TAG = "progress-header"

private const val MAX_DESCRIPTION_LINES = 5

/** A rounded panel with a title and the steps of the reply, collapsed until tapped. */
@Composable
fun MessageBodyCollapsableProgressPanel(steps: List<ToolStep>, modifier: Modifier = Modifier) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .testTag(PROGRESS_HEADER_TAG)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = pluralStringResource(R.plurals.chat_progress_title, steps.size, steps.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(if (isExpanded) R.string.chat_progress_collapse else R.string.chat_progress_expand),
            )
        }

        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                steps.forEach { step ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (step.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer),
                        )
                        Column {
                            Text(
                                text = stepTitle(step),
                                style = MaterialTheme.typography.labelMedium.byContentDirection(),
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                            val detail = stepDetail(step)
                            if (detail.isNotEmpty()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall.byContentDirection(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .heightIn(max = (MaterialTheme.typography.labelMedium.lineHeight.value * MAX_DESCRIPTION_LINES).dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun stepTitle(step: ToolStep): String = when (step.kind) {
    ToolStepKind.LOAD_SKILL -> stringResource(if (step.failed) R.string.chat_step_load_skill_failed else R.string.chat_step_load_skill, step.subject)
    ToolStepKind.RUN_INTENT -> stringResource(if (step.failed) R.string.chat_step_intent_failed else R.string.chat_step_intent, step.subject)
    ToolStepKind.RUN_JS -> stringResource(if (step.failed) R.string.chat_step_js_failed else R.string.chat_step_js, step.subject)
    ToolStepKind.OTHER -> step.subject
}

/** What the step shows under its title: a script's file, an intent's parameters. */
@Composable
private fun stepDetail(step: ToolStep): String = when (step.kind) {
    ToolStepKind.RUN_JS -> step.detail
    ToolStepKind.RUN_INTENT -> step.detail
    else -> ""
}
