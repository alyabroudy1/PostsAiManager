package com.postsaimanager.core.domain.form

/**
 * Cuts a system + user prompt into the part decoded once (the prefix) and the part that closes the user turn and opens
 * the assistant's (the tail appended to every question). A port so the form steps stay testable without a chat template;
 * [AiEnginePromptFraming] binds it to the loaded model's template.
 */
fun interface PromptFraming {
    fun frame(system: String, user: String): Pair<String, String>
}
