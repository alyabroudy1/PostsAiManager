package com.postsaimanager.core.domain.form.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.agent.AgentStepTrace
import com.postsaimanager.core.domain.agent.AgentTrace
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * What the tool results tell a small model (the STATE block, the suggested next step), the user's reply as the result of its question,
 * the repeat block and the language check that break the loop a 0.8B model got into on the device, the fresh run on an old transcript
 * and the per-step trace.
 */
class FormAgentGuidanceTest {

    private fun chip(text: String) = FormChip(FormChipAction.ANSWER, label = text, arg = text)

    // ── The loop of the device: the same question again and again ──

    @Test
    fun `a model that asks the same question again is turned back by the repeat block and the suggestion`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Ich"))
        h.agent.start("doc")

        // The user taps Ahmad. The small model asks the very same question once more...
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Ich"))
        // ...is told it was answered, with the step the state points to, and then follows it.
        h.model.reply { message ->
            assertThat(message).contains("already answered: Ahmad")
            assertThat(message).contains("suggested next: fill_from_profile(person_id=p2, role=subject)")
            h.call("fill_from_profile", "person_id" to "p2", "role" to "subject")
        }
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "guardian"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.chip("doc", chip("Ahmad"), "Ahmad")

        // The loop is broken: the form is filled from Ahmad and the conversation moved on to the next question.
        assertThat(h.field("Name des Kindes").value).isEqualTo("Ahmad Mustermann")
        val questions = h.shown().filter { it.first.kind == FormMessageKind.QUESTION }.map { it.second }
        assertThat(questions).containsExactly("Für wen ist das Formular?", "Hat Ahmad das Seepferdchen schon?").inOrder()
    }

    @Test
    fun `a near-identical question is also a repeat, a different one is not`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Ich"))
        h.agent.start("doc")

        h.model.reply(h.ask("Für wen ist das Formular denn?", "Ahmad", "Ich")) // near-identical wording
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.chip("doc", chip("Ahmad"), "Ahmad")

        assertThat(h.results().any { it.contains("already answered: Ahmad") }).isTrue()
        assertThat(h.shown().last().second).isEqualTo("Hat Ahmad das Seepferdchen schon?")
    }

    // ── The reply is the result of the question ──

    @Test
    fun `the user's reply reaches the model as the result of its ask_user, with what it matched and the state`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Ich"))
        h.agent.start("doc")

        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.chip("doc", chip("Ahmad"), "Ahmad")

        val reply = h.model.sent[3]
        assertThat(reply).startsWith("<tool_response>")
        assertThat(reply).contains("\"ok\":true,\"answer\":\"Ahmad\",\"matched_chip\":\"Ahmad\",\"matched_person\":\"p2\"")
        assertThat(reply).contains("suggested next: fill_from_profile(person_id=p2, role=subject)")
        // It is not also sent as a plain user message, but the user's words are what a value may come from.
        assertThat(h.model.sent).doesNotContain("Ahmad")
    }

    @Test
    fun `a typed answer is a result too, without a matched chip, and an option answer suggests the field to fill`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "guardian"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "payer"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.start("doc")

        h.model.reply(h.ask("Etwas anderes?"))
        assertThat(h.agent.route("doc", "Nein")).isEqualTo(FormRoute.HANDLED)

        val reply = h.model.sent.last()
        assertThat(reply).contains("\"answer\":\"Nein\",\"matched_chip\":\"Nein\"")
        assertThat(reply).doesNotContain("matched_person")
        assertThat(reply).contains("source=option")
    }

    @Test
    fun `every tool result ends with the state, the open fields and the next step`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen?", "Ahmad"))
        h.agent.start("doc")

        val first = h.results()[0]
        assertThat(first).contains("language: German")
        assertThat(first).contains("No person is chosen yet.")
        assertThat(first).contains("Open: f1 Name des Kindes (Angaben zum Kind)")
        assertThat(first).contains("suggested next: ask_user who the form is for")
        assertThat(first.indexOf("\"state\"")).isGreaterThan(first.indexOf("\"fields\"")) // the state comes last
    }

    // ── Language ──

    @Test
    fun `the system prompt names the form's language and a question in another script is refused`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("لمن هذا النموذج؟", "Ahmad"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad"))
        h.agent.start("doc")

        assertThat(h.model.sessions.single().system).contains("The form is in German")
        assertThat(h.results().any { it.contains("the question is not written in German: write it in German") }).isTrue()
        assertThat(h.shown().last().second).isEqualTo("Für wen ist das Formular?")
    }

    @Test
    fun `a question in the script the user wrote in is accepted`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen?", "Ahmad"))
        h.agent.start("doc")

        h.model.reply(h.ask("هل تريد المتابعة؟"))
        h.agent.route("doc", "نعم")

        assertThat(h.shown().last().second).isEqualTo("هل تريد المتابعة؟")
    }

    @Test
    fun `the writing script of a language is data, and a text's dominant script is read from its letters`() {
        assertThat(WritingScript.of(Locale.GERMAN)).isEqualTo(Character.UnicodeScript.LATIN)
        assertThat(WritingScript.of(Locale.forLanguageTag("ar"))).isEqualTo(Character.UnicodeScript.ARABIC)
        assertThat(WritingScript.dominant("Wer ist das? 12")).isEqualTo(Character.UnicodeScript.LATIN)
        assertThat(WritingScript.dominant("من هو أحمد Ahmad؟")).isEqualTo(Character.UnicodeScript.ARABIC)
        assertThat(WritingScript.dominant("12 ?")).isNull()
    }

    // ── A fresh run on an old transcript ──

    private suspend fun seedOldChat(h: AgentHarness, status: FormFillStatus) {
        // What the earlier, code-driven version stored: a beta line without an agent version and answers it kept as plain messages.
        h.log.post("doc", FormMessage(FormMessageKind.STATUS, FormText.BETA_NOTICE))
        h.log.userSaid("doc", "alter Antwort Ahmad")
        h.fills.saveFill(
            FormFill(
                id = "fill-doc", documentId = "doc", status = status, conversationId = "conv-doc",
                createdAt = h.nowMs, updatedAt = h.nowMs,
            ),
        )
    }

    @Test
    fun `an old code-driven transcript is history, a new agent run starts and the model never sees it`() = runTest {
        val h = AgentHarness()
        seedOldChat(h, FormFillStatus.STOPPED)
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen?", "Ahmad"))

        h.agent.start("doc")

        assertThat(h.model.sent.first()).isEqualTo(FormAgentTranscript.START_INSTRUCTION)
        assertThat(h.model.sessions.single().history).isEmpty()
        assertThat(h.model.sent.joinToString()).doesNotContain("alter Antwort")
        // The old messages stay visible above the new run.
        assertThat(h.userTexts(h.messages())).containsExactly("alter Antwort Ahmad")
        val starts = h.messages().filter { FormAgentTranscript.isRunStart(it) }
        assertThat(starts).hasSize(2)
        assertThat(FormAgentTranscript.isCurrentRun(starts.first())).isFalse()
        assertThat(FormAgentTranscript.isCurrentRun(starts.last())).isTrue()
        assertThat(FormAgentTranscript("doc", h.log).entries().filterIsInstance<com.postsaimanager.core.domain.agent.AgentEntry.UserText>().map { it.text })
            .containsExactly(FormAgentTranscript.START_INSTRUCTION)
    }

    @Test
    fun `a stopped agent run offers Continue and Start over, a finished one Start over, and Start over begins afresh`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.start("doc")
        assertThat(h.field("Name des Kindes").value).isEqualTo("Ahmad Mustermann")

        // The user leaves a run stopped, then taps "Form filling (beta)" again.
        h.fills.saveFill(h.fill().copy(status = FormFillStatus.STOPPED))
        val calls = h.model.sent.size
        h.newAgent().start("doc")
        val offer = h.shown().last().first
        assertThat(offer.text).isEqualTo(FormText.AGENT_RESUME_OFFER)
        assertThat(offer.chips.map { it.action }).containsExactly(FormChipAction.CONTINUE, FormChipAction.START_OVER).inOrder()
        assertThat(h.model.sent).hasSize(calls) // no model call, nothing restarted silently
        h.newAgent().start("doc")
        assertThat(h.shown().count { it.first.text == FormText.AGENT_RESUME_OFFER }).isEqualTo(1) // not repeated

        // A finished run offers only Start over.
        h.fills.saveFill(h.fill().copy(status = FormFillStatus.DONE))
        h.log.post("doc", FormMessage(FormMessageKind.STATUS, FormText.NO_MODEL)) // something else was said since
        h.newAgent().start("doc")
        assertThat(h.shown().last().first.text).isEqualTo(FormText.AGENT_DONE_OFFER)
        assertThat(h.shown().last().first.chips.map { it.action }).containsExactly(FormChipAction.START_OVER)

        // Start over forgets the answers and begins a new run from the opening instruction.
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen?", "Ahmad"))
        h.newAgent().chip("doc", h.shown().last().first.chips.single(), "Start over")
        assertThat(h.fields().mapNotNull { it.value }).isEmpty()
        assertThat(h.fill().roleProfiles).isEmpty()
        assertThat(h.model.sent[calls]).isEqualTo(FormAgentTranscript.START_INSTRUCTION)
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASKING)
    }

    // ── The trace ──

    @Test
    fun `every step is traced with its tool, argument names, outcome and timings, and never a value`() = runTest {
        val h = AgentHarness()
        val steps = mutableListOf<AgentStepTrace>()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.call("fill_field", "field_id" to "f99", "value" to "Ahmad Mustermann", "source" to "profile"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))

        h.newAgent(AgentTrace { steps += it }).start("doc")

        assertThat(steps.map { it.tool }).containsExactly("read_form", "fill_from_profile", "fill_field", "ask_user").inOrder()
        assertThat(steps.map { it.step }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(steps.map { it.turn }.toSet()).containsExactly(1)
        assertThat(steps[1].argKeys).containsExactly("person_id", "role").inOrder()
        assertThat(steps.map { it.outcome }).containsExactly("ok", "ok", "error", "ended_turn").inOrder()
        assertThat(steps.map { it.validation }.toSet()).containsExactly("ok")
        assertThat(steps.first().rebuilt).isTrue()
        assertThat(steps.drop(1).none { it.rebuilt }).isTrue()
        assertThat(steps.all { it.contextTokens > 0 && it.modelMs >= 0 && it.toolMs >= 0 }).isTrue()
        assertThat(steps.last().note).matches("filled=\\d+/\\d+ open=\\d+ question=\"Hat Ahmad das Seepferdchen schon\\?\" chips=\\[Ja\\|Nein]")
        assertThat(steps.all { it.note!!.substringBefore(" question").matches(Regex("filled=\\d+/\\d+ open=\\d+")) }).isTrue()
        val lines = steps.joinToString("\n") { it.line() }
        assertThat(lines).contains("turn=1 step=2 tool=fill_from_profile args=person_id,role valid=ok outcome=ok")
        assertThat(lines).doesNotContain("Mustermann")
    }
}
