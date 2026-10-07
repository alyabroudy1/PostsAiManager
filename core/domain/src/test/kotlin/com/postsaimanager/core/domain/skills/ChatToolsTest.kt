package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ChatToolsRequest
import com.postsaimanager.core.domain.ai.ToolActionCall
import com.postsaimanager.core.domain.ai.ToolActionWire
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeChatEngine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** The Agent Skills glue in the domain: when a chat gets tools, the action channel's wire form, and the rebuild in the app process. */
class ChatToolsTest {

    private fun config(runtime: ModelRuntime, supportsTools: Boolean) =
        InferenceConfig(contextTokens = 4096, threads = 4, runtime = runtime, supportsTools = supportsTools)

    @Test
    @DisplayName("tools are on only for a LiteRT-LM model whose catalogue entry declares them")
    fun `tools need the LiteRT runtime and the declared support`() {
        assertThat(ChatToolsPolicy.enabledFor(config(ModelRuntime.LITERT_LM, supportsTools = true))).isTrue()
        assertThat(ChatToolsPolicy.enabledFor(config(ModelRuntime.LITERT_LM, supportsTools = false))).isFalse()
        // llama.cpp chat stays tool-less, whatever its descriptor says.
        assertThat(ChatToolsPolicy.enabledFor(config(ModelRuntime.LLAMA_CPP, supportsTools = true))).isFalse()
        assertThat(ChatToolsPolicy.enabledFor(config(ModelRuntime.LLAMA_CPP, supportsTools = false))).isFalse()
    }

    @Test
    fun `a request is made only when tools are enabled`() {
        assertThat(ChatToolsPolicy.requestFor(config(ModelRuntime.LLAMA_CPP, true), "d1", emptyList())).isNull()
        assertThat(ChatToolsPolicy.requestFor(config(ModelRuntime.LITERT_LM, true), "d1", emptyList()))
            .isEqualTo(ChatToolsRequest("d1"))
    }

    @Test
    @DisplayName("a chat about one letter grounds on it; a chat over all letters only on a letter every passage came from")
    fun `the grounding letter`() {
        assertThat(ChatToolsPolicy.documentFor("d1", listOf("x", "y"))).isEqualTo("d1")
        assertThat(ChatToolsPolicy.documentFor(null, listOf("x", "x"))).isEqualTo("x")
        // Two letters: guessing which one the user meant would check the values against the wrong one.
        assertThat(ChatToolsPolicy.documentFor(null, listOf("x", "y"))).isNull()
        assertThat(ChatToolsPolicy.documentFor(null, emptyList())).isNull()
    }

    @Test
    @DisplayName("the prompt is the Gallery's skills prompt with our skills list: its steps and rules, the list in step 1, no placeholder left")
    fun `the prompt is the Gallery's with the skills listed`() {
        val list = SkillPrompt.namesAndDescriptions(
            listOf(Skill("send-email", "Write an e-mail.", ""), Skill("schedule-reminder", "Remind the user.", "")),
        )

        val prompt = ChatToolsPrompt.build(list)

        assertThat(prompt).startsWith("You are an AI assistant that helps users by answering questions and completes tasks using skills.")
        assertThat(prompt).contains("1. First, find the most relevant skill from the following list:\n\n$list\n\nAfter this step")
        assertThat(prompt).contains("use the `load_skill` tool to read its instructions")
        assertThat(prompt).contains("You MUST NOT use `run_intent` under any circumstances at this step.")
        assertThat(prompt).contains("4. If no relevant skill is found, output \"No relevant skills found\" and stop.")
        assertThat(prompt).doesNotContain("___SKILLS___")
    }

    @Test
    @DisplayName("the date is not in the prompt (the Gallery gives it through the get_current_date_and_time intent)")
    fun `the prompt carries no date`() {
        assertThat(ChatToolsPrompt.build("- x")).doesNotContainMatch("20\\d\\d-\\d\\d-\\d\\d")
    }

    @Test
    fun `an action survives the wire, awkward JSON and Arabic included`() {
        val json = """{"extra_email":"a@b.de","extra_subject":"إعادة: \"Az. 12/3\"","extra_text":"Line 1\nLine 2"}"""
        val call = ToolActionCall("send_email", json, "doc-7")

        val back = ToolActionWire.fromWire(call.intent, call.parametersJson, ToolActionWire.documentIdToWire(call.documentId))

        assertThat(back).isEqualTo(call)
        assertThat((back!!.parse() as ActionParse.Parsed).action)
            .isEqualTo(AgentAction.SendEmail("a@b.de", "إعادة: \"Az. 12/3\"", "Line 1\nLine 2"))
    }

    @Test
    fun `no document is the empty string on the wire and null again after it`() {
        assertThat(ToolActionWire.documentIdToWire(null)).isEmpty()
        assertThat(ToolActionWire.fromWire("send_email", "{}", "")!!.documentId).isNull()
        assertThat(ToolActionWire.fromWire("send_email", "{}", null)!!.documentId).isNull()
    }

    @Test
    fun `a wire call with no intent is nothing to act on`() {
        assertThat(ToolActionWire.fromWire(null, "{}", "d")).isNull()
        assertThat(ToolActionWire.fromWire("  ", "{}", "d")).isNull()
    }

    private fun observed(vararg calls: ToolActionCall): ObserveToolActionsUseCase {
        val engine = object : ChatEngine by FakeChatEngine() {
            override val toolActions = flowOf(*calls)
        }
        return ObserveToolActionsUseCase(engine)
    }

    @Test
    @DisplayName("the app process rebuilds the action from what the model wrote, with the letter it was about")
    fun `a proposal is rebuilt from the call`() = runTest {
        val reminder = ToolActionCall(
            "schedule_notification",
            """{"message":"Pay","year":2026,"month":11,"day":3,"hour":9,"minute":0}""",
            "d1",
        )

        val proposals = observed(reminder).invoke().toList()

        assertThat(proposals).containsExactly(
            ToolProposal(AgentAction.ScheduleReminder(LocalDateTime.of(2026, 11, 3, 9, 0), "Pay", "d1"), "d1"),
        )
    }

    @Test
    fun `a call that cannot be rebuilt, and the clock, make no proposal`() = runTest {
        val proposals = observed(
            ToolActionCall("send_email", """{"extra_subject":"no address"}""", null),
            ToolActionCall("get_current_date_and_time", "{}", null),
            ToolActionCall("unknown", "{}", null),
        ).invoke().toList()

        assertThat(proposals).isEmpty()
    }
}
