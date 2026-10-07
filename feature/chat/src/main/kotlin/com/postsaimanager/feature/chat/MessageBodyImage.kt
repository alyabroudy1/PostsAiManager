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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's ui/common/chat/MessageBodyImage.kt (v1.0.20). A Gallery
// ChatMessageImage carries decoded Bitmaps; here a message stores the files of its pictures (the app's chat-attachments folder), and
// Coil draws them. The layout is the Gallery's: one picture at its size (bounded), several in a grid of 100 dp squares, 3 columns
// (2 for four). The click opens the picture larger.

package com.postsaimanager.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.ceil

/** The tag of one picture of [MessageBodyImage]: `message-image-<index>`. */
fun messageImageTag(index: Int) = "message-image-$index"

/** The longest side of a lone picture in a bubble, in dp. */
private const val SINGLE_IMAGE_MAX_DP = 240

@Composable
fun MessageBodyImage(
    paths: List<String>,
    onImageClicked: (selectedIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageCount = paths.size
    if (imageCount == 0) return
    // Single image.
    if (imageCount == 1) {
        AsyncImage(
            model = File(paths[0]),
            contentDescription = stringResource(R.string.chat_image_description),
            modifier = modifier
                .testTag(messageImageTag(0))
                .widthIn(max = SINGLE_IMAGE_MAX_DP.dp)
                .heightIn(max = SINGLE_IMAGE_MAX_DP.dp)
                .clickable { onImageClicked(0) },
            contentScale = ContentScale.Fit,
        )
        return
    }
    // Multiple images, in a grid.
    val colCount = if (imageCount == 4) 2 else 3
    val rowCount = ceil(imageCount.toFloat() / colCount).toInt()
    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0 until rowCount) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0 until colCount) {
                    val index = row * colCount + col
                    if (index >= imageCount) break
                    AsyncImage(
                        model = File(paths[index]),
                        contentDescription = stringResource(R.string.chat_image_in_group_description, index + 1, imageCount),
                        modifier = Modifier.testTag(messageImageTag(index)).size(100.dp).clickable { onImageClicked(index) },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}
