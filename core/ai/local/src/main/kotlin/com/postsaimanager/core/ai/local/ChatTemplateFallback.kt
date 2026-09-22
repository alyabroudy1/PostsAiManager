package com.postsaimanager.core.ai.local

import com.postsaimanager.core.domain.ai.AiChatMessage

/**
 * ChatML formatting used when a model declares no chat template of its own.
 *
 * Both [LocalAiEngine] and [RemoteAiEngine] fell back to the model's own template first and
 * an identical hand-rolled ChatML string second — this is that second path, pulled out once
 * rather than kept in sync by hand in two files. A genuine compromise, not a default: a
 * Gemma model with no metadata would be formatted as Qwen and produce quietly worse output.
 * `AiCapabilities.hasNativeChatTemplate` reports which path an engine actually took.
 */
internal object ChatTemplateFallback {

    fun chatMl(messages: List<AiChatMessage>): String = buildString {
        messages.forEach { message ->
            appendLine("<|im_start|>${message.role.wireName}")
            appendLine("${message.content}<|im_end|>")
        }
        appendLine("<|im_start|>assistant")
    }
}
