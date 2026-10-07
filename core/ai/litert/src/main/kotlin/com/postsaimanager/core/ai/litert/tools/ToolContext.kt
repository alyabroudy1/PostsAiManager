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

// Modified by PostsAiManager: the role of the Gallery's agent/ToolExecutionContext and its actionChannel (tools publish what they
// want the UI to do, they do not do it). Here the channel is a plain callback bound per reply, because the tools run in the
// :inference process and the callback is the AIDL one.

package com.postsaimanager.core.ai.litert.tools

import com.postsaimanager.core.domain.ai.ToolActionCall

/**
 * What the tools know about the reply in flight, and where they publish: bound by the engine before each reply, shared by the
 * tool instances (which LiteRT-LM keeps for the life of a conversation).
 *
 * The sink never executes anything. It carries a [ToolActionCall] to the app process, which checks it and shows a card.
 */
internal class ToolContext {

    @Volatile
    private var documentId: String? = null

    @Volatile
    private var sink: (ToolActionCall) -> Unit = {}

    private val proposed = mutableSetOf<Pair<String, String>>()

    /** Starts a reply: [documentId] is the letter it is about, [sink] receives every proposed action. */
    @Synchronized
    fun bind(documentId: String?, sink: (ToolActionCall) -> Unit) {
        this.documentId = documentId
        this.sink = sink
        proposed.clear()
    }

    /** Ends the reply: a late tool call finds nobody to tell. */
    @Synchronized
    fun release() {
        documentId = null
        sink = {}
        proposed.clear()
    }

    /** The letter the reply in flight is about, or null. */
    fun chatDocumentId(): String? = documentId

    /**
     * Publishes the call, once per reply: a small model that repeats itself must not put two identical cards in the chat.
     *
     * @return false when this reply already proposed exactly this call.
     */
    @Synchronized
    fun propose(intent: String, parametersJson: String): Boolean {
        if (!proposed.add(intent to parametersJson)) return false
        sink(ToolActionCall(intent = intent, parametersJson = parametersJson, documentId = documentId))
        return true
    }
}
