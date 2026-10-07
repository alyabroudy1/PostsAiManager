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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's ui/common/chat/MessageBodyWebview.kt (v1.0.20). Their
// GalleryWebView (network allowed, camera and microphone prompts, `allowFileAccess`, an iframe wrapper) is replaced by the offline
// sandbox (skills/SkillSandbox.kt): the page can only load the files of its own bundled skill folder. The chat message is the
// stored ToolStep's webview (the url relative to the skills folder and its aspect ratio); the "full screen" chip is the Gallery's.

package com.postsaimanager.feature.chat

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.postsaimanager.core.domain.skills.JsSkillWebview
import com.postsaimanager.feature.chat.skills.SkillSandbox
import com.postsaimanager.feature.chat.skills.applyOfflineSandbox
import kotlinx.coroutines.launch

/** The tag of the webview chip of [MessageBodyWebview], for tests. */
const val WEBVIEW_FULL_SCREEN_TAG = "webview-full-screen"

/** A skill's own page shown in the chat, offline: [webview].url is relative to the skills folder (`<skill>/assets/<page>`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBodyWebview(webview: JsSkillWebview, modifier: Modifier = Modifier) {
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val url = SkillSandbox.urlFor(webview.url)

    Column(modifier = modifier) {
        OfflineSkillWebView(url = url, modifier = Modifier.fillMaxWidth().aspectRatio(webview.aspectRatio))
        AssistChip(
            onClick = { showBottomSheet = true },
            modifier = Modifier.testTag(WEBVIEW_FULL_SCREEN_TAG),
            leadingIcon = {
                Icon(
                    Icons.Outlined.FitScreen,
                    contentDescription = null,
                    Modifier.size(AssistChipDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            label = { Text(stringResource(R.string.chat_webview_full_screen)) },
        )
    }

    if (showBottomSheet) {
        ModalBottomSheet(onDismissRequest = { showBottomSheet = false }, sheetState = sheetState, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                OfflineSkillWebView(url = url, modifier = Modifier.fillMaxSize())
                IconButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            showBottomSheet = false
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_webview_close))
                }
            }
        }
    }
}

/** A WebView in the offline sandbox showing [url], which must be an address of [SkillSandbox]; any other is not loaded. */
@Composable
private fun OfflineSkillWebView(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val folder = SkillSandbox.skillFolderOf(url)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                if (folder != null) {
                    applyOfflineSandbox(context, folder)
                    // The page scrolls inside itself, not the chat under it.
                    setOnTouchListener { v, _ ->
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    loadUrl(url)
                }
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}
