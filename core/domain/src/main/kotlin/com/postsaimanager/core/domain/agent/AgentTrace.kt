package com.postsaimanager.core.domain.agent

/**
 * One step of the loop as a device log needs it, to see what a small model did and how long it took. It never carries an
 * argument value or a result: only the tool name, the argument names, how the step ended and sizes.
 *
 * @property turn which user turn of the run (1 for the opening one).
 * @property step which step within the turn (1-based).
 * @property tool the called tool's name, or `-` when the reply was not a call.
 * @property argKeys the names of the arguments the model passed.
 * @property validation `ok`, `invalid` (the schema check failed), `unknown_tool`, or `unreadable` (not a call at all).
 * @property outcome `ok`, `error`, `ended_turn` or `unreadable`.
 * @property modelMs how long the model took to answer.
 * @property toolMs how long the tool took to run.
 * @property contextTokens a rough size of the standing session after the step (system prompt and conversation).
 * @property rebuilt whether the session had to be (re)built for this step.
 * @property note an optional feature remark for the log, such as the question of an ask (see [AgentSpec.traceNote]).
 */
data class AgentStepTrace(
    val turn: Int,
    val step: Int,
    val tool: String,
    val argKeys: List<String>,
    val validation: String,
    val outcome: String,
    val modelMs: Long,
    val toolMs: Long,
    val contextTokens: Int,
    val rebuilt: Boolean,
    val note: String? = null,
) {
    fun line(): String = buildString {
        append("turn=$turn step=$step tool=$tool args=${argKeys.joinToString(",").ifEmpty { "-" }} valid=$validation outcome=$outcome")
        append(" model_ms=$modelMs tool_ms=$toolMs ctx_tokens~=$contextTokens rebuilt=${if (rebuilt) "yes" else "no"}")
        note?.let { append(" $it") }
    }
}

/** Where the loop reports its steps (debug builds log them; release builds and most tests report nothing). */
fun interface AgentTrace {

    fun step(trace: AgentStepTrace)

    companion object {
        val NONE = AgentTrace { }
    }
}
