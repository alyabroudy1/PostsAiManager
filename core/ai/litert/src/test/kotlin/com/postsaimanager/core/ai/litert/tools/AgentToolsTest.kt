package com.postsaimanager.core.ai.litert.tools

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.ToolActionCall
import com.postsaimanager.core.domain.skills.Skill
import com.postsaimanager.core.domain.skills.SkillCatalog
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** `load_skill` and `run_intent` with a fake catalog and a recording channel: the tools propose, they never execute. */
class AgentToolsTest {

    private val sendEmail = Skill("send-email", "Write an e-mail.", "1. Write it.\n2. Call run_intent.")

    private val catalog = object : SkillCatalog {
        override suspend fun skills(): List<Skill> = listOf(sendEmail)
    }

    private val context = ToolContext()
    private val emitted = mutableListOf<ToolActionCall>()
    private val now = LocalDateTime.of(2026, 10, 7, 14, 30, 5)
    private val calls = AgentToolCalls(catalog, context) { now }
    private val loadSkill = calls
    private val runIntent = calls

    private val emailJson = """{"extra_email":"a@b.de","extra_subject":"Re: Az. 1","extra_text":"Hello"}"""

    private fun startReply(documentId: String? = "doc-1") = context.bind(documentId) { emitted += it }

    @Test
    fun `load_skill returns the skill's instructions`() {
        val result = loadSkill.loadSkill("send-email")

        assertThat(result["skill_name"]).isEqualTo("send-email")
        assertThat(result["skill_instructions"]).isEqualTo(sendEmail.content())
        assertThat(result["skill_instructions"]).contains("Call run_intent")
    }

    @Test
    fun `load_skill forgives the way a small model spells the name`() {
        assertThat(loadSkill.loadSkill("Send_Email")["skill_instructions"]).isEqualTo(sendEmail.content())
    }

    @Test
    fun `load_skill says so for a skill that does not exist`() {
        assertThat(loadSkill.loadSkill("fly-to-the-moon")["skill_instructions"]).isEqualTo("Skill not found")
    }

    @Test
    @DisplayName("a parsed run_intent is emitted for the user's card and the model hears it waits for confirmation")
    fun `run_intent proposes`() {
        startReply()

        val result = runIntent.runIntent("send_email", emailJson)

        assertThat(emitted).containsExactly(ToolActionCall("send_email", emailJson, "doc-1"))
        assertThat(result["status"]).isEqualTo(AgentToolCalls.PROPOSED)
        assertThat(result["status"]).contains("waiting")
        assertThat(result).doesNotContainKey("error")
    }

    @Test
    fun `run_intent hands the model the reason when the call is rejected, and emits nothing`() {
        startReply()

        val result = runIntent.runIntent("send_email", """{"extra_subject":"no address"}""")

        assertThat(emitted).isEmpty()
        assertThat(result["status"]).isEqualTo("failed")
        assertThat(result["error"]).isEqualTo("Missing parameter: extra_email")
    }

    @Test
    fun `run_intent rejects a parameter string that is not JSON`() {
        startReply()

        val result = runIntent.runIntent("send_email", "not json")

        assertThat(emitted).isEmpty()
        assertThat(result["status"]).isEqualTo("failed")
    }

    @Test
    fun `a skill name used as an intent gets the hint to run it as a skill`() {
        startReply()

        val result = runIntent.runIntent("send-email", "{}")

        assertThat(result["error"]).isEqualTo("Intent not found. Try to run it as a skill")
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `an unknown intent is rejected with its name`() {
        startReply()

        val result = runIntent.runIntent("launch_rocket", "{}")

        assertThat(result["error"]).contains("launch_rocket")
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `the clock is answered at once, with no card`() {
        startReply()

        val result = runIntent.runIntent("get_current_date_and_time", "{}")

        assertThat(result["result"]).isEqualTo("2026-10-07T14:30:05 Wednesday")
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `the letter of the reply travels with the action, and none is none`() {
        startReply(documentId = null)

        runIntent.runIntent("send_email", emailJson)

        assertThat(emitted.single().documentId).isNull()
    }

    @Test
    fun `a reminder with no document id of its own opens the letter the reply is about`() {
        startReply(documentId = "doc-9")
        val json = """{"message":"Pay","year":2026,"month":11,"day":3,"hour":9,"minute":0}"""

        runIntent.runIntent("schedule_notification", json)

        val call = emitted.single()
        assertThat(call.documentId).isEqualTo("doc-9")
        assertThat(call.parse().toString()).contains("doc-9")
    }

    @Test
    fun `the same call twice in one reply is one card`() {
        startReply()

        runIntent.runIntent("send_email", emailJson)
        val second = runIntent.runIntent("send_email", emailJson)

        assertThat(emitted).hasSize(1)
        assertThat(second["status"]).isEqualTo(AgentToolCalls.PROPOSED)
    }

    @Test
    fun `a new reply may propose the same call again`() {
        startReply()
        runIntent.runIntent("send_email", emailJson)
        context.release()

        startReply()
        runIntent.runIntent("send_email", emailJson)

        assertThat(emitted).hasSize(2)
    }

    @Test
    fun `a call after the reply ended reaches nobody`() {
        startReply()
        context.release()

        runIntent.runIntent("send_email", emailJson)

        assertThat(emitted).isEmpty()
    }
}
