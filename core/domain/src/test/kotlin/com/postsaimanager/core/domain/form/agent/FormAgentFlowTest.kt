package com.postsaimanager.core.domain.form.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.form.FakeEmbedder
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** The form agent end to end: a scripted model emits tool calls; the form, the people, the storage and the chat are the real code. */
class FormAgentFlowTest {

    private fun chip(text: String) = FormChip(FormChipAction.ANSWER, label = text, arg = text)

    // ── The whole swim-course run, decided call by call by the model ──

    @Test
    fun `a swim form is filled for Ahmad, asked, remembered and finished, every step a tool call`() = runTest {
        val h = AgentHarness()

        // Turn 1 (the card's "Help me fill it"): read the form, list the people, ask who it is for.
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Ich"))
        h.agent.start("doc")

        assertThat(h.model.sent.first()).isEqualTo(FormAgentTranscript.START_INSTRUCTION)
        val read = h.results()[0]
        assertThat(read).contains("\"pages\":2")
        assertThat(read).contains("f1|Name des Kindes|p1|Angaben zum Kind|text|key=full_name|role=subject|open")
        // The reading found the likely subject, quoted from the form, as a person the model can name.
        assertThat(read).contains("\"suggested_person\":{\"person_id\":\"p2\",\"name\":\"Ahmad\",\"reason_quoted_from_form\":\"Kinder 6–10 Jahre · Kursbeginn im Herbst\"}")
        assertThat(h.results()[1]).contains("\"person_id\":\"p1\",\"name\":\"Me\"")
        assertThat(h.results()[1]).contains("\"person_id\":\"p2\",\"name\":\"Ahmad\",\"relationship\":\"child\",\"age\":7,\"guardians\":[\"p1\"]")
        val question = h.shown().last { it.first.kind == FormMessageKind.QUESTION }
        assertThat(question.second).isEqualTo("Für wen ist das Formular?")
        assertThat(question.first.chips.map { it.label }).containsExactly("Ahmad", "Ich").inOrder()
        assertThat(question.first.chips.all { it.action == FormChipAction.ANSWER }).isTrue()
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASKING)

        // The user taps Ahmad: the model fills the roles from the stored details (a secret only as its token).
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "guardian"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "payer"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.chip("doc", chip("Ahmad"), "Ahmad")

        assertThat(h.field("Name des Kindes").value).isEqualTo("Ahmad Mustermann")
        assertThat(h.field("Geburtsdatum").value).isEqualTo("12.03.2019")
        assertThat(h.field("Name der Erziehungsberechtigten").value).isEqualTo("Mohammad Mustermann")
        assertThat(h.field("IBAN").value).isEqualTo("DE89 3704 0044 0532 0130 00")
        assertThat(h.field("Ort, Datum").valueSource).isEqualTo(FormValueSource.TODAY)
        assertThat(h.results().joinToString()).doesNotContain("DE89") // the model never saw the IBAN
        assertThat(h.results().joinToString()).contains("***iban")
        assertThat(h.fill().roleProfiles.values).containsAtLeast("ahmad", "me")

        // The user types the answer; the model maps it onto the printed option, and asks the next thing with the options as chips.
        h.model.reply(h.call("fill_field", "field_id" to h.alias("Hat Ihr Kind das Seepferdchen bereits?"), "value" to "Nein", "source" to "option"))
        h.model.reply(h.ask("Welcher Kurstermin passt?", "Montag 16:00 Uhr", "Mittwoch 15:00 Uhr", "Samstag 10:00 Uhr"))
        assertThat(h.agent.route("doc", "Nein")).isEqualTo(FormRoute.HANDLED)
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isEqualTo("Nein")
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").valueSource).isEqualTo(FormValueSource.FORM_OPTION)

        h.model.reply(h.call("fill_field", "field_id" to h.alias("Kurstermin"), "value" to "Mittwoch 15:00 Uhr", "source" to "option"))
        h.model.reply(h.ask("Hat Ahmad Allergien oder Hinweise zur Gesundheit?"))
        h.agent.route("doc", "Mittwoch")
        assertThat(h.field("Kurstermin").value).isEqualTo("Mittwoch 15:00 Uhr")

        // The user's own words are written as they typed them; then the model asks whether to remember, and only then does.
        h.model.reply(h.call("fill_field", "field_id" to h.alias("Allergien / Hinweise zur Gesundheit"), "value" to "Nussallergie", "source" to "user", "person_id" to "p2"))
        h.model.reply(h.ask("Soll ich mir das für Ahmad merken?", "Ja", "Nein"))
        h.agent.route("doc", "Nussallergie")
        assertThat(h.field("Allergien / Hinweise zur Gesundheit").value).isEqualTo("Nussallergie")
        assertThat(h.field("Allergien / Hinweise zur Gesundheit").valueSource).isEqualTo(FormValueSource.USER)
        assertThat(h.facts.facts("ahmad")).isEmpty() // nothing is remembered before the user agreed

        h.model.reply(
            h.callJson(
                "remember_detail", "person_id" to JsonPrimitive("p2"), "key" to JsonPrimitive("allergies"),
                "value" to JsonPrimitive("Nussallergie"), "user_agreed" to JsonPrimitive(true),
            ),
        )
        h.model.reply(h.call("show_fill_card"))
        h.model.reply(h.call("show_on_page", "field_id" to h.alias("Unterschrift")))
        h.model.reply(h.call("finish", "summary" to "Fertig: nur die Unterschrift auf Seite 2 fehlt noch."))
        h.agent.route("doc", "Ja")

        val fact = h.facts.facts("ahmad").single()
        assertThat(fact.key).isEqualTo("allergies")
        assertThat(fact.value).isEqualTo("Nussallergie")
        assertThat(fact.source).isEqualTo(FactSource.FORM_ANSWER)
        assertThat(fact.sourceDocumentId).isEqualTo("doc")
        assertThat(h.fill().status).isEqualTo(FormFillStatus.DONE)
        assertThat(h.field("Unterschrift").value).isNull()
        assertThat(h.userTexts(h.messages())).containsExactly("Ahmad", "Nein", "Mittwoch", "Nussallergie", "Ja").inOrder()

        // What the user sees: questions with chips, the card, the page chip and the closing message. Never raw tool JSON.
        val kinds = h.shown().map { it.first.kind }
        assertThat(kinds).containsAtLeast(FormMessageKind.QUESTION, FormMessageKind.CARD, FormMessageKind.PAGE).inOrder()
        assertThat(h.shown().last().first.kind).isEqualTo(FormMessageKind.QUESTION)
        assertThat(h.shown().last().second).isEqualTo("Fertig: nur die Unterschrift auf Seite 2 fehlt noch.")
        assertThat(h.shown().single { it.first.kind == FormMessageKind.PAGE }.first.fieldId).isEqualTo(h.alias("Unterschrift"))
        assertThat(h.shown().joinToString { it.second }).doesNotContain("tool_call")
        // Every step is stored in the message's tool columns, with its call id.
        val calls = h.messages().filter { it.role == MessageRole.TOOL_CALL }
        assertThat(calls.map { it.toolName }).containsAtLeast("read_form", "list_people", "ask_user", "fill_field", "remember_detail", "finish").inOrder()
        assertThat(calls.all { it.toolCallId != null && it.toolArgs != null }).isTrue()
        assertThat(h.messages().filter { it.role == MessageRole.TOOL_RESULT && it.toolCallId != null }.all { it.toolResult != null }).isTrue()
    }

    @Test
    fun `a refused call comes back as an error the model corrects in the same turn`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_field", "field_id" to "f1", "value" to "Fantasie Müller", "source" to "profile", "person_id" to "p2"))
        h.model.reply(h.call("get_person_details", "person_id" to "p2"))
        h.model.reply(h.call("fill_field", "field_id" to "f1", "value" to "Ahmad Mustermann", "source" to "profile", "person_id" to "p2"))
        h.model.reply(h.ask("Stimmt das?", "Ja", "Nein"))

        h.agent.start("doc")

        val results = h.results()
        assertThat(results[1]).contains("\"ok\":false")
        assertThat(results[1]).contains("is not a stored detail of Ahmad")
        assertThat(results[2]).contains("\"full_name\":\"Ahmad Mustermann\"")
        assertThat(results[2]).contains("\"birth_date\":\"2019-03-12\"")
        assertThat(h.field("Name des Kindes").value).isEqualTo("Ahmad Mustermann")
        // The model read the refusal as the next message it answered.
        assertThat(h.model.sent[2]).contains("is not a stored detail of Ahmad")
    }

    @Test
    fun `an argument the schema refuses never reaches the tool`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_field", "field_id" to "f1", "value" to "x", "source" to "invented"))
        h.model.reply(h.ask("Q?"))

        h.agent.start("doc")

        assertThat(h.results()[1]).contains("must be one of: profile, user, option")
        assertThat(h.fields().mapNotNull { it.value }).isEmpty()
    }

    // ── Limits ──

    @Test
    fun `a turn that never reaches the user stops at the step limit and offers to go on`() = runTest {
        val h = AgentHarness()
        repeat(8) { h.model.reply(h.call("list_people")) }

        h.agent.start("doc")

        assertThat(h.model.sent).hasSize(6)
        val last = h.shown().last().first
        assertThat(last.text).isEqualTo(FormText.AGENT_STUCK)
        assertThat(last.chips.map { it.labelCode }).containsExactly(FormChipLabel.CONTINUE, FormChipLabel.START_OVER).inOrder()
        assertThat(h.fill().status).isEqualTo(FormFillStatus.STOPPED)
        // The repeats were answered "already done", not run again.
        assertThat(h.results().count { it.contains("already done: list_people") }).isEqualTo(5)
    }

    @Test
    fun `a model that keeps answering with something else fails with a message`() = runTest {
        val h = AgentHarness()
        repeat(4) { h.model.reply("I would rather chat.") }

        h.agent.start("doc")

        assertThat(h.shown().last().first.text).isEqualTo(FormText.AGENT_FAILED)
        assertThat(h.model.discarded).isEqualTo(3)
    }

    @Test
    fun `no model installed says so and starts nothing`() = runTest {
        val h = AgentHarness()
        h.model.loaded = com.postsaimanager.core.common.result.PamResult.Error(com.postsaimanager.core.common.result.PamError.ModelNotLoaded("chat"))

        h.agent.start("doc")

        assertThat(h.shown().last().first.text).isEqualTo(FormText.NO_MODEL)
        assertThat(h.model.sent).isEmpty()
    }

    @Test
    fun `the standing prompt with all the tools leaves a small model room in its window`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.ask("Q?"))
        h.agent.start("doc")

        val system = h.model.sessions.single().system

        // About 1,700 tokens of a 4,096-token window; the rest is the conversation and one reply. (Raised from 5,500 for the one worked
        // example and the tools_now line, about 500 characters, which are what helps a 0.8B call the right tool at the right time.)
        assertThat(system.length).isLessThan(6_000)
        assertThat(system).contains("German") // the form's language is spoken until the user writes in another
        assertThat(system).contains("<function=example_function_name>")
        assertThat(com.postsaimanager.core.domain.agent.AgentProfile().conversationRoom(system.length)).isAtLeast(2_000)
    }

    // ── Stop, leave and resume ──

    @Test
    fun `stopping pauses the run, and continuing picks it up from the stored steps`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply { throw CancellationException("the user pressed Stop") }

        val stopped = runCatching { h.agent.start("doc") }.exceptionOrNull()

        assertThat(stopped).isInstanceOf(CancellationException::class.java)
        assertThat(h.fill().status).isEqualTo(FormFillStatus.STOPPED)
        val paused = h.shown().last().first
        assertThat(paused.text).isEqualTo(FormText.AGENT_PAUSED)
        assertThat(h.messages().filter { it.role == MessageRole.TOOL_CALL }.map { it.toolName }).containsExactly("read_form")

        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen?", "Ahmad", "Ich"))
        assertThat(paused.chips.map { it.action }).containsExactly(FormChipAction.CONTINUE, FormChipAction.START_OVER).inOrder()
        h.agent.chip("doc", paused.chips.first(), "Continue")

        // The model answered the stored result of read_form: the form was not read again.
        assertThat(h.model.sent[2]).startsWith("<tool_response>")
        assertThat(h.model.sent[2]).contains("\"pages\":2")
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASKING)
        assertThat(h.shown().last().first.kind).isEqualTo(FormMessageKind.QUESTION)
    }

    @Test
    fun `a run cut off by a crash goes on when the chat is opened again, one that waits for the user does not`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply { error("process died") }
        runCatching { h.agent.start("doc") }
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASKING) // nothing paused it

        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen?", "Ahmad"))
        h.newAgent().resume("doc")
        assertThat(h.shown().last().second).isEqualTo("Für wen?")

        // Now it waits for the user: opening the chat again costs no model call.
        val calls = h.model.sent.size
        h.newAgent().resume("doc")
        assertThat(h.model.sent).hasSize(calls)
    }

    @Test
    fun `after a restart the answer continues the conversation, and the engine session is rebuilt from the stored steps`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Ich"))
        h.agent.start("doc")

        h.model.resetSession() // the engine lost its KV cache
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.ask("Welcher Kurstermin?", "Montag 16:00 Uhr"))
        val restarted = h.newAgent()
        val before = h.model.sent.size
        assertThat(restarted.route("doc", "Ahmad")).isEqualTo(FormRoute.HANDLED)
        // The user's answer reaches the model as the result of its ask_user, with what it matched.
        assertThat(h.model.sent[before]).startsWith("<tool_response>")
        assertThat(h.model.sent[before]).contains("\"answer\":\"Ahmad\",\"matched_chip\":\"Ahmad\",\"matched_person\":\"p2\"")

        val rebuilt = h.model.sessions.last()
        // A session is rebuilt from whole recent turns within the history budget; what does not fit is replaced by the state summary. (The
        // standing prompt with the worked example leaves about 1,900 characters of history on the default 4,096-token window, so the
        // rebuild in the middle of this turn may start from the summary.)
        assertThat(rebuilt.history.first().role).isEqualTo(AiChatRole.USER)
        assertThat(rebuilt.history.any { it.role == AiChatRole.ASSISTANT && it.content.contains("<function=") }).isTrue()
        assertThat(h.model.sessions[1].history.map { it.role }).containsExactly(
            AiChatRole.USER, AiChatRole.ASSISTANT, AiChatRole.USER, AiChatRole.ASSISTANT, AiChatRole.USER, AiChatRole.ASSISTANT,
        ).inOrder()
        assertThat(h.field("Name des Kindes").value).isEqualTo("Ahmad Mustermann")
    }

    // ── Entry ──

    @Test
    fun `a typed request starts the run and the user's words reach the agent, an ordinary question does not`() = runTest {
        val h = AgentHarness(documentIsForm = false, embedder = FakeEmbedder(ready = false))
        h.fillRequests += "fülle das für Ahmad aus"

        assertThat(h.agent.route("doc", "Was kostet der Kurs?")).isEqualTo(FormRoute.NOT_FOR_FORM)
        assertThat(h.messages()).isEmpty()

        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        assertThat(h.agent.route("doc", "fülle das für Ahmad aus")).isEqualTo(FormRoute.HANDLED)

        assertThat(h.model.sent.first()).isEqualTo("fülle das für Ahmad aus")
        assertThat(h.userTexts(h.messages())).containsExactly("fülle das für Ahmad aus")
        assertThat(h.shown().first().first.text).isEqualTo(FormText.BETA_NOTICE) // the run begins before the user's words
        assertThat(h.messages().first().role).isEqualTo(MessageRole.TOOL_RESULT)
    }

    @Test
    fun `a finished fill is left to the normal chat until it is asked for again`() = runTest {
        val h = AgentHarness()
        h.fillRequests += "nochmal ausfüllen"
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("finish", "summary" to "Fertig."))
        h.agent.start("doc")
        assertThat(h.fill().status).isEqualTo(FormFillStatus.DONE)

        assertThat(h.agent.route("doc", "Wann ist Kursbeginn?")).isEqualTo(FormRoute.NOT_FOR_FORM)

        h.model.reply(h.ask("Soll ich etwas ändern?"))
        assertThat(h.agent.route("doc", "nochmal ausfüllen")).isEqualTo(FormRoute.HANDLED)
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASKING)
        assertThat(h.model.sent.last()).isEqualTo("nochmal ausfüllen")
    }

    // ── What the chat renders ──

    @Test
    fun `the chat renders the calls that show something and hides the protocol`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("show_fill_card"))
        h.model.reply(h.call("show_on_page", "field_id" to "f2"))
        h.model.reply(h.ask("Wer?", "Ahmad", "Ich"))
        h.agent.start("doc")

        val messages = h.messages()
        val byTool = messages.filter { it.toolCallId != null }.groupBy { it.toolName }
        assertThat(FormMessageCodec.isAgentStep(byTool.getValue("read_form").first())).isTrue()
        assertThat(messages.filter { FormMessageCodec.isAgentStep(it) }.mapNotNull { FormMessageCodec.parse(it)?.kind })
            .containsExactly(FormMessageKind.CARD, FormMessageKind.PAGE, FormMessageKind.QUESTION).inOrder()
        assertThat(FormMessageCodec.parse(byTool.getValue("read_form").first())).isNull()
        assertThat(FormMessageCodec.parse(byTool.getValue("ask_user").first())!!.chips.map { it.arg }).containsExactly("Ahmad", "Ich").inOrder()
    }

    @Test
    fun `the transcript of a run starts at its newest beginning and ignores the plain chat between`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Wer?", "Ahmad"))
        h.agent.start("doc")

        val entries = FormAgentTranscript("doc", h.log).entries()

        assertThat(entries.map { it::class.simpleName }).containsExactly("UserText", "Call", "Result", "Call").inOrder()
        assertThat((entries.first() as AgentEntry.UserText).isStart).isTrue()
    }
}
