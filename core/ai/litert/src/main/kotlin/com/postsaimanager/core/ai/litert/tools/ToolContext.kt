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
import com.postsaimanager.core.model.ToolExchange

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

    @Volatile
    private var exchangeSink: (ToolExchange) -> Unit = {}

    private val proposed = mutableSetOf<Pair<String, String>>()

    /** Every call of the reply in flight (or the last one) with its result, oldest first: what the conversation replays next time. */
    private val exchanges = mutableListOf<ToolExchange>()

    /**
     * Starts a reply: [documentId] is the letter it is about, [sink] receives every proposed action, [onExchange] every call made
     * with the result it got (for the app to store with the reply).
     */
    @Synchronized
    fun bind(documentId: String?, sink: (ToolActionCall) -> Unit, onExchange: (ToolExchange) -> Unit = {}) {
        this.documentId = documentId
        this.sink = sink
        this.exchangeSink = onExchange
        proposed.clear()
        exchanges.clear()
    }

    /** Ends the reply: a late tool call finds nobody to tell. The recorded [exchanges] stay until the next reply. */
    @Synchronized
    fun release() {
        documentId = null
        sink = {}
        exchangeSink = {}
        proposed.clear()
    }

    /** Notes one call and its result. */
    @Synchronized
    fun record(exchange: ToolExchange) {
        exchanges += exchange
        exchangeSink(exchange)
    }

    /** The calls of the reply in flight or the last one. */
    @Synchronized
    fun exchanges(): List<ToolExchange> = exchanges.toList()

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
