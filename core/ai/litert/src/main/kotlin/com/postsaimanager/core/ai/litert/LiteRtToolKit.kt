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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's tools/ToolsProvider.kt (getLiteRtToolProviders, `tool(it)`) and
// the system-prompt assembly of customtasks/agentchat/AgentChatTaskModule.kt (the skills' names and descriptions).

package com.postsaimanager.core.ai.litert

import android.util.Log
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.postsaimanager.core.ai.litert.tools.AgentToolCalls
import com.postsaimanager.core.ai.litert.tools.LoadSkillTool
import com.postsaimanager.core.ai.litert.tools.RunIntentTool
import com.postsaimanager.core.ai.litert.tools.ToolContext
import com.postsaimanager.core.domain.ai.ToolActionCall
import com.postsaimanager.core.domain.skills.ChatToolsPrompt
import com.postsaimanager.core.domain.skills.SkillCatalog
import com.postsaimanager.core.model.ToolExchange

/**
 * The Agent Skills tools of one LiteRT-LM engine: `load_skill` and `run_intent`, the system-prompt text that introduces the skills,
 * and the [ToolContext] the engine binds before each reply.
 *
 * The one owner of the skills is the [SkillCatalog]; this class reads it and nothing else.
 */
internal class LiteRtToolKit(private val skills: SkillCatalog) {

    private val context = ToolContext()

    /** The tools as LiteRT-LM's conversation config takes them (the Gallery's `getLiteRtToolProviders`). */
    val providers: List<ToolProvider> by lazy {
        val calls = AgentToolCalls(skills, context, log = { Log.i("PamTools", it) })
        listOf(tool(LoadSkillTool(calls)), tool(RunIntentTool(calls)))
    }

    /**
     * The skills section of the system prompt, or null when no skill is bundled (then the model gets no tools: nothing would
     * tell it what they are for).
     */
    suspend fun systemPrompt(): String? {
        val list = skills.namesAndDescriptions()
        return if (list.isBlank()) null else ChatToolsPrompt.build(list)
    }

    /** Starts a reply about [documentId]; every proposed action goes to [onAction]. */
    fun bind(documentId: String?, onAction: (ToolActionCall) -> Unit, onExchange: (ToolExchange) -> Unit = {}) =
        context.bind(documentId, onAction, onExchange)

    /** The calls the reply made (the last reply's, after [release]) with the results the model got. */
    fun exchanges(): List<ToolExchange> = context.exchanges()

    /** Ends the reply. */
    fun release() = context.release()
}
