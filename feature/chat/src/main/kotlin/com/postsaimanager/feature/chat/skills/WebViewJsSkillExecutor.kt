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

// Modified by PostsAiManager: the way the Google AI Edge Gallery runs a JS skill (customtasks/agentchat/AgentChatScreen.kt, the
// CallJsToolAction branch, v1.0.20): load the skill's page, then call `ai_edge_gallery_get_result(data)` and read its answer through
// a JavaScript interface named `AiEdgeGallery`. Differences: the WebView is created here, off screen, and lives for one call (the
// Gallery keeps a visible 300 dp one in its screen); it is the offline sandbox of SkillSandbox; no secret is passed.

package com.postsaimanager.feature.chat.skills

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.postsaimanager.core.domain.skills.JsSkillExecutor
import com.postsaimanager.core.domain.skills.JsSkillPaths
import com.postsaimanager.core.domain.skills.JsSkillRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Runs a skill's script in an off-screen, offline WebView ([applyOfflineSandbox]) and returns what its
 * `ai_edge_gallery_get_result(data)` answered. A script is bundled data (the skill's own folder); it cannot reach the network or
 * any file outside that folder, so what it is given (the model's `data`) cannot leave the phone through it.
 */
@Singleton
class WebViewJsSkillExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : JsSkillExecutor {

    override suspend fun run(request: JsSkillRequest): String = withContext(Dispatchers.Main) {
        val path = JsSkillPaths.script(request.skillFolder, request.scriptName)
            ?: return@withContext error("The script ${request.skillFolder}/${request.scriptName} is not a file of the skill.")
        withTimeoutOrNull(TIMEOUT_MS) { load(request, SkillSandbox.urlFor(path)) }
            ?: error("The script did not answer in time.")
    }

    @SuppressLint("JavascriptInterface")
    private suspend fun load(request: JsSkillRequest, url: String): String = suspendCancellableCoroutine { continuation ->
        val main = Handler(Looper.getMainLooper())
        val webView = WebView(context)
        var done = false
        fun finish(result: String) {
            if (done) return
            done = true
            if (continuation.isActive) continuation.resume(result)
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
        webView.applyOfflineSandbox(context, request.skillFolder) {
            // The Gallery's script: wait (up to 10 s) for the page to define the entry point, then call it with the data.
            webView.evaluateJavascript(callScript(request.data), null)
        }
        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onResultReady(result: String) {
                    main.post { finish(result) }
                }
            },
            INTERFACE,
        )
        continuation.invokeOnCancellation { main.post { finish("") } }
        webView.loadUrl(url)
    }

    private fun callScript(data: String): String =
        """
        (async function() {
            try {
                var startTs = Date.now();
                while (typeof ai_edge_gallery_get_result !== 'function' && Date.now() - startTs < 10000) {
                    await new Promise(function(resolve) { setTimeout(resolve, 100); });
                }
                var result = await ai_edge_gallery_get_result(${JSONObject.quote(data)}, "");
                $INTERFACE.onResultReady(typeof result === 'string' ? result : JSON.stringify(result));
            } catch (e) {
                $INTERFACE.onResultReady(JSON.stringify({error: String(e)}));
            }
        })()
        """.trimIndent()

    private fun error(message: String): String = JSONObject().put("error", message).toString()

    private companion object {
        const val INTERFACE = "AiEdgeGallery"
        const val TIMEOUT_MS = 45_000L
    }
}
