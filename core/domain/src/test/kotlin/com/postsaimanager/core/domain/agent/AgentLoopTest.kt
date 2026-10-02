package com.postsaimanager.core.domain.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiChatRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class AgentLoopTest {

    private val format = QwenToolCallFormat()
    private val model = ScriptedAgentModel()

    private val note = LambdaTool("note", ToolParams.schema(ToolParams.string("text", "A note.")))
    private val pick = LambdaTool(
        "pick", ToolParams.schema(ToolParams.enumString("color", "A color.", listOf("red", "blue"))),
        body = { args, _ -> ToolResult.ok("picked" to JsonPrimitive(args.string("color").orEmpty())) },
    )
    private val ask = LambdaTool(
        "ask", ToolParams.schema(ToolParams.string("question", "Q."), ToolParams.stringArray("chips", "C.", required = false)),
        endsTurn = true,
    )
    private val tools = ToolRegistry(listOf(note, pick, ask))
    private val specs = tools.specs()

    private fun loop(profile: AgentProfile = AgentProfile()) = AgentLoop(model, format, profile, newId = ids())

    private fun ids(): () -> String {
        var n = 0
        return { "c${++n}" }
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        format.renderCall(name, obj(*args.map { it.first to str(it.second) }.toTypedArray()), specs)

    private fun user(text: String) = AgentEntry.UserText(text)

    // ── The turn ──

    @Test
    fun `the loop runs tools until a turn-ending tool hands the conversation to the user`() = runTest {
        val transcript = MemoryTranscript(user("hello"))
        model.reply(call("note", "text" to "one"))
        model.reply(call("pick", "color" to "red"))
        model.reply(call("ask", "question" to "Which one?"))

        val outcome = loop().run(TestSpec(tools), transcript)

        assertThat(outcome).isInstanceOf(AgentOutcome.EndedTurn::class.java)
        assertThat((outcome as AgentOutcome.EndedTurn).call.name).isEqualTo("ask")
        // The user's message, then each step's call and result as stored; the ending call has no result.
        assertThat(transcript.stored.map { it::class.simpleName }).containsExactly(
            "UserText", "Call", "Result", "Call", "Result", "Call",
        ).inOrder()
        assertThat(model.sent).hasSize(3)
        assertThat(model.sent[0]).isEqualTo("hello")
        assertThat(model.sent[1]).startsWith("<tool_response>")
        assertThat(model.committed).hasSize(3)
    }

    @Test
    fun `every step is constrained by the grammar of the registered tools and runs without thinking`() = runTest {
        val transcript = MemoryTranscript(user("hi"))
        model.reply(call("ask", "question" to "Q?"))

        loop().run(TestSpec(tools), transcript)

        val request = model.requests.single()
        assertThat(request.grammar).isEqualTo(format.grammar(specs))
        assertThat(request.thinkingEnabled).isFalse()
        assertThat(request.thinkingBudgetTokens).isEqualTo(0)
        assertThat(request.maxTokens).isEqualTo(AgentProfile().maxStepTokens)
        // The tool descriptions are part of the system prompt (the model's template does not describe tools by itself).
        assertThat(model.sessions.single().system).contains("You are a test agent.")
        assertThat(model.sessions.single().system).contains("<tools>")
        assertThat(model.sessions.single().conversationId).isEqualTo("agent-test")
    }

    @Test
    fun `a result carries the running state summary and the hint after two failures`() = runTest {
        val transcript = MemoryTranscript(user("hi"))
        model.reply(call("note", "text" to "a"))
        model.reply(call("pick", "color" to "green")) // invalid enum
        model.reply(call("pick", "color" to "yellow")) // invalid again: the hint arrives
        model.reply(call("ask", "question" to "Q?"))

        loop().run(TestSpec(tools, state = "STATE-X", hint = "HINT-Y"), transcript)

        val results = transcript.stored.filterIsInstance<AgentEntry.Result>()
        assertThat(results[0].result.toModelText()).contains("\"state\":\"STATE-X\"")
        assertThat(results[1].result.errorMessage).contains("must be one of: red, blue")
        assertThat(results[1].result.toModelText()).doesNotContain("HINT-Y")
        assertThat(results[2].result.toModelText()).contains("\"hint\":\"HINT-Y\"")
    }

    // ── Validation ──

    @Test
    fun `a call the schema refuses is answered with the reason and the model corrects itself`() = runTest {
        val transcript = MemoryTranscript(user("hi"))
        model.reply(call("pick", "color" to "green"))
        model.reply { sent ->
            assertThat(sent).contains("must be one of: red, blue")
            call("pick", "color" to "blue")
        }
        model.reply(call("ask", "question" to "Done?"))

        loop().run(TestSpec(tools), transcript)

        assertThat(pick.calls).isEqualTo(1) // the refused call never ran
        val results = transcript.stored.filterIsInstance<AgentEntry.Result>()
        assertThat(results[0].result.ok).isFalse()
        assertThat(results[1].result.ok).isTrue()
    }

    @Test
    fun `missing and unknown arguments are refused before the tool runs`() {
        val schema = ToolParams.schema(ToolParams.string("a", "A."), ToolParams.integer("n", "N.", required = false))

        assertThat(ArgumentValidator.validate(schema, obj())).isEqualTo("missing required argument \"a\"")
        assertThat(ArgumentValidator.validate(schema, obj("a" to str("x"), "z" to str("y")))).contains("unknown argument \"z\"")
        assertThat(ArgumentValidator.validate(schema, obj("a" to JsonPrimitive(3)))).contains("must be a string")
        assertThat(ArgumentValidator.validate(schema, obj("a" to str("x"), "n" to str("7")))).contains("must be a integer")
        assertThat(ArgumentValidator.validate(schema, obj("a" to str("x"), "n" to JsonPrimitive(7)))).isNull()
        val chips = ToolParams.schema(ToolParams.stringArray("c", "C."))
        assertThat(ArgumentValidator.validate(chips, obj("c" to JsonArray(listOf(str("a")))))).isNull()
        assertThat(ArgumentValidator.validate(chips, obj("c" to str("a")))).contains("must be a array")
    }

    // ── Limits ──

    @Test
    fun `a turn stops at the step limit`() = runTest {
        val transcript = MemoryTranscript(user("hi"))
        repeat(10) { model.reply(call("note", "text" to "n$it")) }

        val outcome = loop(AgentProfile(maxStepsPerTurn = 4)).run(TestSpec(tools), transcript)

        assertThat(outcome).isEqualTo(AgentOutcome.StepLimit)
        assertThat(note.calls).isEqualTo(4)
        assertThat(model.sent).hasSize(4)
    }

    @Test
    fun `the same call twice in a turn is answered with already done, not run again`() = runTest {
        val transcript = MemoryTranscript(user("hi"))
        model.reply(call("note", "text" to "same"))
        model.reply(call("note", "text" to "same"))
        model.reply(call("ask", "question" to "Q?"))

        loop().run(TestSpec(tools), transcript)

        assertThat(note.calls).isEqualTo(1)
        val second = transcript.stored.filterIsInstance<AgentEntry.Result>()[1].result
        assertThat(second.ok).isFalse()
        assertThat(second.errorMessage).startsWith("already done: note")
    }

    @Test
    fun `the same call in a later turn runs again`() = runTest {
        val transcript = MemoryTranscript(user("hi"))
        model.reply(call("note", "text" to "same"))
        model.reply(call("ask", "question" to "Q?"))
        loop().run(TestSpec(tools), transcript)
        transcript.stored += user("again")
        model.reply(call("note", "text" to "same"))
        model.reply(call("ask", "question" to "Q2?"))

        loop().run(TestSpec(tools), transcript)

        assertThat(note.calls).isEqualTo(2)
    }

    @Test
    fun `a reply that is not a call is discarded and asked again, then the run fails`() = runTest {
        val transcript = MemoryTranscript(user("hi"))
        model.reply("I think the answer is blue.")
        model.reply(call("ask", "question" to "Q?"))

        val recovered = loop().run(TestSpec(tools), transcript)

        assertThat(recovered).isInstanceOf(AgentOutcome.EndedTurn::class.java)
        assertThat(model.discarded).isEqualTo(1)
        assertThat(model.sent[1]).contains("not a valid tool call")

        val failing = ScriptedAgentModel().apply { repeat(5) { reply("nope") } }
        val failed = AgentLoop(failing, format, AgentProfile(maxInvalidRetries = 2)).run(TestSpec(tools), MemoryTranscript(user("hi")))
        assertThat(failed).isInstanceOf(AgentOutcome.Failed::class.java)
        assertThat(failing.sent).hasSize(3)
    }

    @Test
    fun `a tool that throws becomes an error result`() = runTest {
        val boom = LambdaTool("boom", body = { _, _ -> error("disk full") })
        val registry = ToolRegistry(listOf(boom, ask))
        val transcript = MemoryTranscript(user("hi"))
        model.reply(format.renderCall("boom", obj(), registry.specs()))
        model.reply(format.renderCall("ask", obj("question" to str("Q?")), registry.specs()))

        loop().run(TestSpec(registry), transcript)

        assertThat(transcript.stored.filterIsInstance<AgentEntry.Result>().single().result.errorMessage).isEqualTo("boom failed: disk full")
    }

    // ── Resume and context ──

    @Test
    fun `a conversation that already waits for the user is left alone`() = runTest {
        val waiting = AgentEntry.Call("c0", "ask", obj("question" to str("Q?")))

        val outcome = loop().run(TestSpec(tools), MemoryTranscript(user("hi"), waiting))

        assertThat(outcome).isEqualTo(AgentOutcome.Waiting(waiting))
        assertThat(model.sent).isEmpty()
    }

    @Test
    fun `a stopped run resumes from its stored steps, the newest result being what the model answers`() = runTest {
        val stored = MemoryTranscript(
            user("hi"),
            AgentEntry.Call("c1", "note", obj("text" to str("a"))),
            AgentEntry.Result("c1", "note", ToolResult.ok()),
        )
        model.reply(call("ask", "question" to "Q?"))

        loop().run(TestSpec(tools), stored)

        // The session is rebuilt from the stored steps before the newest one, and the newest is what is sent.
        val history = model.sessions.single().history
        assertThat(history.map { it.role }).containsExactly(AiChatRole.USER, AiChatRole.ASSISTANT).inOrder()
        assertThat(history[1].content).isEqualTo(call("note", "text" to "a"))
        assertThat(model.sent.single()).isEqualTo(format.renderResult("note", ToolResult.ok().toModelText()))
    }

    @Test
    fun `the user's reply after a question is the next thing the model answers`() = runTest {
        val stored = MemoryTranscript(
            user("hi"),
            AgentEntry.Call("c1", "ask", obj("question" to str("Who?"))),
            user("Ahmad"),
        )
        model.reply(call("ask", "question" to "Thanks"))

        loop().run(TestSpec(tools), stored)

        assertThat(model.sent.single()).isEqualTo("Ahmad")
        assertThat(model.sessions.single().history.map { it.content }.last()).startsWith("<tool_call>")
    }

    @Test
    fun `a long history is cut to whole recent turns and the older ones are summarised by the spec`() = runTest {
        val entries = buildList {
            repeat(6) { turn ->
                add(user("message $turn ${"x".repeat(200)}"))
                add(AgentEntry.Call("a$turn", "ask", obj("question" to str("q$turn"))))
            }
            add(user("latest"))
        }.toTypedArray()
        model.reply(call("ask", "question" to "Q?"))

        loop(AgentProfile(historyChars = 700)).run(TestSpec(tools, state = "STATE-SUMMARY"), MemoryTranscript(*entries))

        val history = model.sessions.single().history
        assertThat(history.first().content).isEqualTo("Earlier steps are summarised here.\nSTATE-SUMMARY")
        assertThat(history.drop(1).first().role).isEqualTo(AiChatRole.USER)
        assertThat(history.any { it.content.startsWith("message 0") }).isFalse()
        assertThat(history.last().content).startsWith("<tool_call>") // q5, the last question before "latest"
    }

    @Test
    fun `a session that grew too large is rebuilt from the compact history`() = runTest {
        val transcript = MemoryTranscript(user("hi"))
        repeat(3) { model.reply(call("note", "text" to "n$it ${"y".repeat(600)}")) }
        model.reply(call("ask", "question" to "Q?"))

        // A small window: the conversation has room for little more than a couple of steps beyond the system prompt.
        loop(AgentProfile(contextTokens = 400)).run(TestSpec(tools), transcript)

        assertThat(model.resets).isAtLeast(1)
        assertThat(model.sessions.size).isAtLeast(2)
    }

    @Test
    fun `the room for a conversation is the window less one reply and the system prompt, never less than a floor`() {
        val profile = AgentProfile(contextTokens = 4_096, charsPerToken = 3, maxStepTokens = 256)

        assertThat(profile.conversationRoom(systemChars = 6_000)).isEqualTo((4_096 * 3 * 0.85).toInt() - 256 * 3 - 6_000)
        assertThat(profile.conversationRoom(systemChars = 20_000)).isEqualTo(1_500)
    }

    @Test
    fun `the context of a call tells a tool what the user said and which question it answers`() {
        val q = AgentEntry.Call("c1", "ask", obj("question" to str("Remember it?")))
        val entries = listOf(
            AgentEntry.UserText("Help me fill in this form.", isStart = true),
            q,
            AgentEntry.UserText("yes"),
            AgentEntry.Call("c2", "note", obj()),
        )

        val context = AgentContext.of(entries)

        assertThat(context.userReplies).containsExactly("yes")
        assertThat(context.turnStartedByUser).isTrue()
        assertThat(context.previousTurnEnd).isEqualTo(q)
        assertThat(context.turnCalls.map { it.id }).containsExactly("c2")
        assertThat(AgentContext.of(entries.take(1)).hasUserReply).isFalse()
    }

    @Test
    fun `a registry refuses two tools of one name`() {
        val result = runCatching { ToolRegistry(listOf(note, LambdaTool("note"))) }

        assertThat(result.isFailure).isTrue()
    }
}
