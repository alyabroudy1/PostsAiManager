package com.postsaimanager.core.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** Dynamic tool exposure in the generic loop: the grammar and the model's tool list follow the spec's `allowedTools`, per step. */
class AgentToolExposureTest {

    private val model = ScriptedAgentModel()

    /** A format that counts how many grammars were built. */
    private class CountingFormat(private val inner: ToolCallFormat = QwenToolCallFormat()) : ToolCallFormat by inner {
        val built = mutableListOf<List<String>>()

        override fun grammar(tools: List<ToolSpec>): String {
            built += tools.map { it.name }
            return inner.grammar(tools)
        }
    }

    private val format = CountingFormat()
    private val read = LambdaTool("read", body = { _, _ -> ToolResult.ok("read" to JsonPrimitive(true)) })
    private val fill = LambdaTool("fill", ToolParams.schema(ToolParams.string("value", "")))
    private val ask = LambdaTool("ask", ToolParams.schema(ToolParams.string("question", "")), endsTurn = true)
    private val tools = ToolRegistry(listOf(read, fill, ask))
    private val specs = tools.specs()

    /** A spec that exposes `read` until it ran once in the run, then `fill` and `ask`. */
    private inner class StagedSpec : AgentSpec {
        override val conversationId = "agent-test"
        override val tools = this@AgentToolExposureTest.tools
        override suspend fun systemPrompt() = "You are a test agent."
        override suspend fun stateSummary() = null
        override suspend fun stuckHint() = null
        override suspend fun allowedTools(entries: List<AgentEntry>): List<String> =
            if (entries.any { it is AgentEntry.Call && it.name == "read" }) listOf("fill", "ask") else listOf("read")
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        format.renderCall(name, obj(*args.map { it.first to str(it.second) }.toTypedArray()), specs)

    private fun loop(trace: AgentTrace = AgentTrace.NONE) = AgentLoop(model, format, AgentProfile(), newId = ids(), trace = trace)

    private fun ids(): () -> String {
        var n = 0
        return { "c${++n}" }
    }

    @Test
    fun `each step's grammar allows only the tools of the state, and the model is told which`() = runTest {
        val transcript = MemoryTranscript(AgentEntry.UserText("hi"))
        model.reply(call("read"))
        model.reply(call("fill", "value" to "x"))
        model.reply(call("ask", "question" to "Q?"))

        loop().run(StagedSpec(), transcript)

        val plain = QwenToolCallFormat()
        assertThat(model.requests[0].grammar).isEqualTo(plain.grammar(specs.filter { it.name == "read" }))
        assertThat(model.requests[1].grammar).isEqualTo(plain.grammar(specs.filter { it.name != "read" }))
        assertThat(model.requests[0].grammar).doesNotContain("<function=fill>")
        assertThat(model.requests[1].grammar).doesNotContain("<function=read>")
        // The result the model answers ends with the tools of its step.
        assertThat(model.sent[1]).contains("\"tools_now\":\"fill, ask\"")
        assertThat(model.sent[2]).contains("\"tools_now\":\"fill, ask\"")
    }

    @Test
    fun `a grammar is built once per subset of tools`() = runTest {
        val transcript = MemoryTranscript(AgentEntry.UserText("hi"))
        model.reply(call("read"))
        repeat(3) { model.reply(call("fill", "value" to "v$it")) }
        model.reply(call("ask", "question" to "Q?"))

        loop().run(StagedSpec(), transcript)

        assertThat(format.built).containsExactly(listOf("read"), listOf("fill", "ask")).inOrder()
    }

    @Test
    fun `a call to a tool that is not exposed is refused and does not run`() = runTest {
        val transcript = MemoryTranscript(AgentEntry.UserText("hi"))
        model.reply(call("fill", "value" to "too early"))
        model.reply(call("read"))
        model.reply(call("ask", "question" to "Q?"))

        loop().run(StagedSpec(), transcript)

        assertThat(fill.calls).isEqualTo(0)
        assertThat(model.sent[1]).contains("fill is not available now. Available now: read")
    }

    @Test
    fun `a spec without the hook exposes every tool, as before`() = runTest {
        val transcript = MemoryTranscript(AgentEntry.UserText("hi"))
        model.reply(call("ask", "question" to "Q?"))

        loop().run(TestSpec(tools), transcript)

        assertThat(model.requests.single().grammar).isEqualTo(QwenToolCallFormat().grammar(specs))
        assertThat(format.built).containsExactly(listOf("read", "fill", "ask"))
    }

    @Test
    fun `the trace of an error step carries the first characters of its reason, quoted values left out`() = runTest {
        val steps = mutableListOf<AgentStepTrace>()
        val refuse = LambdaTool("refuse", ToolParams.schema(ToolParams.string("value", "")), body = { args, _ ->
            ToolResult.error("\"${args.string("value")}\" is not what the user wrote. Use the user's own words exactly as they wrote them, or ask again.")
        })
        val registry = ToolRegistry(listOf(refuse, ask))
        val transcript = MemoryTranscript(AgentEntry.UserText("hi"))
        model.reply(format.renderCall("refuse", obj("value" to str("Erika Test")), registry.specs()))
        model.reply(format.renderCall("ask", obj("question" to str("Q?")), registry.specs()))

        loop(AgentTrace { steps += it }).run(TestSpec(registry), transcript)

        val error = steps.first()
        assertThat(error.outcome).isEqualTo("error")
        assertThat(error.line()).contains("reason=\"\"…\" is not what the user wrote. Use the user's own words exa\"")
        assertThat(error.line()).doesNotContain("Erika")
        assertThat(AgentStepTrace.shortReason("x".repeat(100))).hasLength(AgentStepTrace.REASON_CHARS)
        assertThat(steps.last().line()).doesNotContain("reason=")
    }
}
