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

// Modified by PostsAiManager: replaces the Google AI Edge Gallery's ui/common/GalleryWebView.kt BaseGalleryWebViewClient (v1.0.20), whose
// WebViewAssetLoader also served the app's whole files directory (`allowFileAccess = true`, any URL not for the loader went to
// the network, `domStorageEnabled`, camera and microphone prompts). Here a skill's page can load nothing but the files of its own
// bundled folder, never the network, never a file: the policy is a pure object (tested without Android), and the client and
// settings that apply it are below.

package com.postsaimanager.feature.chat.skills

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * What an offline skill page may load. The page is shown under a fixed https address (the Gallery's trick: it makes the page a
 * secure context, so `crypto.subtle` works, while no request is ever sent: every request is answered here from the app's assets).
 * Everything that is not a file of the skill's own `scripts/` or `assets/` folder is blocked.
 */
object SkillSandbox {

    /** The Gallery's host for local pages (`LOCAL_URL_BASE`); nothing is ever fetched from it. */
    const val HOST = "appassets.androidplatform.net"

    private const val BASE = "https://$HOST/skills/"

    private val ALLOWED_FOLDERS = listOf("scripts", "assets")

    /**
     * Tells the page itself to stay offline, in the page's own terms: it may only load its own origin (the answers above), no
     * frame, no object, no form post. Inline scripts and styles are the skill's own and stay allowed (the Gallery's skills use them).
     */
    const val CONTENT_SECURITY_POLICY: String =
        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
            "font-src 'self' data:; media-src 'self' data: blob:; connect-src 'self'; frame-src 'none'; object-src 'none'; " +
            "form-action 'none'; base-uri 'self'"

    /** The address a skill file is shown under; [relativePath] is relative to the skills folder (`<skill>/scripts/index.html`). */
    fun urlFor(relativePath: String): String = BASE + relativePath

    /** The skill folder the address [url] belongs to, or null when it is not an address of this sandbox. */
    fun skillFolderOf(url: String): String? {
        val rest = restOf(url) ?: return null
        return rest.substringBefore('/').takeIf { it.isNotEmpty() && rest.contains('/') }
    }

    /**
     * The asset path (`skills/<folder>/…`) [url] asks for, or null when the request must be blocked: it is not https, not this
     * host, not a plain path, outside the folder [skillFolder], or not in its `scripts/` or `assets/`.
     */
    fun assetPath(url: String, skillFolder: String): String? {
        val rest = restOf(url) ?: return null
        val parts = rest.split('/')
        if (parts.size < 3 || parts[0] != skillFolder || parts[1] !in ALLOWED_FOLDERS) return null
        return "skills/$rest"
    }

    /** The path after `/skills/` when [url] is an https address of this host with a plain path; null otherwise. */
    private fun restOf(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != HOST || uri.port != -1 || uri.userInfo != null) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/skills/") || path.contains('%') || path.contains('\\')) return null
        val rest = path.removePrefix("/skills/")
        if (rest.isEmpty() || rest.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return rest
    }

    /** The media type a skill file is served as, from its extension. */
    fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/**
 * Answers every request of a skill page from the app's assets, or blocks it ([SkillSandbox]). Navigation away from the skill's
 * own addresses is refused too.
 */
class OfflineSkillWebViewClient(
    private val context: Context,
    private val skillFolder: String,
    private val onPageFinished: () -> Unit = {},
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return blocked()
        val path = SkillSandbox.assetPath(url, skillFolder) ?: return blocked()
        return try {
            WebResourceResponse(
                SkillSandbox.mimeOf(path),
                "UTF-8",
                200,
                "OK",
                mapOf("Content-Security-Policy" to SkillSandbox.CONTENT_SECURITY_POLICY, "Cache-Control" to "no-store"),
                context.assets.open(path),
            )
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** True blocks the navigation: a skill page cannot send the view anywhere but to its own files. */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return true
        return SkillSandbox.assetPath(url, skillFolder) == null
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

/**
 * Puts a WebView in the sandbox: JavaScript on (a skill is a script), everything else that reaches beyond the skill's page off.
 * Network loads are blocked by the engine as well as by [OfflineSkillWebViewClient].
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.applyOfflineSandbox(context: Context, skillFolder: String, onPageFinished: () -> Unit = {}) {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
    }
    webViewClient = OfflineSkillWebViewClient(context, skillFolder, onPageFinished)
    webChromeClient = object : WebChromeClient() {
        // No camera, microphone or other device permission, whatever the page asks (the Gallery's page could ask for both).
        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }
    }
}
