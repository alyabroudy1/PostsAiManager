package com.postsaimanager.core.domain.form.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The second device loop: the user chose "someone else" for a role and the model kept asking the role question. The next step is the
 * person's name; a repeat of the role question is refused with that hint (a new question passes), the typed text fills the name
 * fields, "Me" without a profile of the user is somebody else too, and the fill card appears with the first fill.
 */
class FormAgentRoleNameTest {

    private fun chip(text: String) = FormChip(FormChipAction.ANSWER, label = text, arg = text)

    private fun askCall(question: String, vararg chips: String) = AgentEntry.Call(
        "ask1", "ask_user",
        JsonObject(mapOf("question" to JsonPrimitive(question), "chips" to JsonArray(chips.map(::JsonPrimitive)))),
    )

    private val roleQuestion = "Wer ist die erziehungsberechtigte Person?"

    /** The run up to the guardian question: the user is the subject (tapped Me) and nobody is known as the guardian. */
    private suspend fun AgentHarness.untilGuardianAsked() {
        model.reply(call("read_form"))
        model.reply(call("list_people"))
        model.reply(ask("Für wen ist das Formular?", "Ahmad", "Me"))
        agent.start("doc")
        model.reply(call("fill_from_profile", "person_id" to "p1", "role" to "subject"))
        model.reply(ask(roleQuestion, "Ahmad", "Someone else"))
        agent.chip("doc", chip("Me"), "Me")
    }

    @Test
    fun `after someone else the role question is refused with the name hint, a new question passes and the typed name fills the name fields`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.untilGuardianAsked()

        // The model asks the role question again, as on the device.
        h.model.reply(h.ask(roleQuestion, "Ahmad", "Someone else"))
        h.model.reply { message ->
            assertThat(message).contains("The user will give the name of the")
            assertThat(message).contains("Ask for their name now (no chips)")
            assertThat(message).doesNotContain("already answered: Someone else")
            assertThat(message).doesNotContain("What is the person's name?")
            h.ask(roleQuestion, "Ahmad", "Someone else")
        }
        h.model.reply { message ->
            assertThat(message).contains("Ask for their name now (no chips)")
            assertThat(message).doesNotContain("What is the person's name?")
            h.ask(roleQuestion, "Ahmad", "Someone else")
        }
        // The third time the result also carries the question to ask (still the model's call).
        h.model.reply { message ->
            assertThat(message).contains("tools_now: ask_user")
            assertThat(message).contains("What is the person's name?")
            h.ask("Wie lautet der volle Name der erziehungsberechtigten Person?")
        }
        h.agent.chip("doc", chip("Someone else"), "Someone else")

        assertThat(h.shown().last { it.first.kind == FormMessageKind.QUESTION }.second).isEqualTo("Wie lautet der volle Name der erziehungsberechtigten Person?")
        assertThat(h.results().count { it.contains("The user will give the name of the") }).isAtLeast(3)

        // The user types the name (any text): it goes into the role's name field.
        val alias = h.alias("Name der Erziehungsberechtigten")
        h.model.reply { message ->
            assertThat(message).contains("\"answer\":\"Erika Test\"")
            assertThat(message).contains("value=Erika Test, source=user")
            h.call("fill_field", "field_id" to alias, "value" to "Erika Test", "source" to "user")
        }
        h.model.reply(h.ask("Wer ist der Kontoinhaber?", "Me", "Ahmad", "Someone else"))
        h.agent.route("doc", "Erika Test")

        assertThat(h.field("Name der Erziehungsberechtigten").value).isEqualTo("Erika Test")
        assertThat(h.field("Name der Erziehungsberechtigten").valueSource).isEqualTo(FormValueSource.USER)
        // The fill card appeared with the first fill, without the model calling show_fill_card.
        assertThat(h.shown().count { it.first.kind == FormMessageKind.CARD }).isEqualTo(1)
        assertThat(h.messages().none { it.toolName == "show_fill_card" }).isTrue()
    }

    @Test
    fun `the name step is told plainly in the reply result`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.exec("read_form")
        h.exec("fill_from_profile", "person_id" to "p1", "role" to "subject")
        val guidance = FormGuidance(h.envFor("doc"))

        val result = guidance.replyResult(askCall(roleQuestion, "Ahmad", "Someone else"), "Someone else").toModelText()

        assertThat(result).contains("The user will give the name of the")
        assertThat(result).contains("Ask for their name now (no chips)")
        assertThat(guidance.stage(h.fields(), UserReply("Someone else", askCall(roleQuestion, "Ahmad", "Someone else")))).isEqualTo(FormStage.ROLE_NAME_NEEDED)
        assertThat(ToolPolicy().allowed(FormStage.ROLE_NAME_NEEDED, rememberPending = false)).containsExactly("ask_user", "skip_field").inOrder()
    }

    @Test
    fun `Me without a profile of the user is somebody else, with one it is assigned`() = runTest {
        val noSelf = AgentHarness(dynamicTools = true)
        noSelf.profiles.deleteProfile("me")
        noSelf.exec("read_form")
        noSelf.exec("fill_from_profile", "person_id" to "p1", "role" to "subject") // Ahmad: nobody is stored as his guardian now
        val asked = askCall(roleQuestion, "Ich", "Someone else")
        val situation = FormGuidance(noSelf.envFor("doc")).roleSituation(noSelf.fields(), UserReply("Me", asked))
        assertThat(situation).isEqualTo(RoleSituation.NeedsPerson(FormRole.GUARDIAN, someoneElse = true))

        val withSelf = AgentHarness(
            dynamicTools = true,
            wording = object : FormWording by FormWording.English {
                override fun me(language: java.util.Locale): String = "Ich"
            },
        )
        withSelf.exec("read_form")
        withSelf.exec("fill_from_profile", "person_id" to "p2", "role" to "subject") // Ahmad
        val guidance = FormGuidance(withSelf.envFor("doc"))
        assertThat(guidance.matchPerson("Ich")?.id).isEqualTo("me")
        val ready = guidance.roleSituation(withSelf.fields(), UserReply("Ich", asked))
        assertThat(ready).isInstanceOf(RoleSituation.Ready::class.java)
        assertThat((ready as RoleSituation.Ready).person.id).isEqualTo("me")
        assertThat(ready.fromAnswer).isTrue()
    }

    // ── The role's name in the STATE ──

    @Test
    fun `a heading that starts in lower case or is longer than three words never names the role, the label does`() = runTest {
        val h = AgentHarness()
        h.exec("read_form")
        val env = h.envFor("doc")
        val base = h.fields().first { it.role == FormRole.PAYER }
        fun payer(section: String) = listOf(base.copy(id = "x1", section = section, orderIndex = 1), base.copy(id = "x2", labelText = "IBAN", section = section, orderIndex = 2))

        assertThat(env.roles.nameIn(payer("die gezogene Lastschrift einzulösen"), FormRole.PAYER)).isEqualTo(base.labelText)
        assertThat(env.roles.nameIn(payer("Wer die gezogene Lastschrift einzulösen hat"), FormRole.PAYER)).isEqualTo(base.labelText)
        assertThat(env.roles.nameIn(payer("Zahlung per Lastschrift"), FormRole.PAYER)).isEqualTo("Zahlung per Lastschrift (${base.labelText})")
    }

    // ── The language guard ──

    @Test
    fun `a German question passes however few words it shares with the labels, English is refused unless it uses a word of the form`() = runTest {
        val h = AgentHarness()
        h.exec("read_form")

        val german = h.exec("ask_user", "question" to "Wer ist die gezogene Lastschrift einzulösen (Kontoinhaber/in)?")
        assertThat(german.ok).isTrue()
        val unrelatedGerman = h.exec("ask_user", "question" to "Möchten Sie dazu noch etwas ergänzen, bitte?")
        assertThat(unrelatedGerman.ok).isTrue()

        val english = h.exec("ask_user", "question" to "Who is the pass for the child?")
        assertThat(english.ok).isFalse()
        assertThat(english.text("error")).contains("not written in German")
        // A word of the document's OCR text (here the course title) makes it the form's language.
        val withFormWord = h.exec("ask_user", "question" to "Is the Schwimmkurs for the child?")
        assertThat(withFormWord.ok).isTrue()
    }

    // ── After a stop ──

    @Test
    fun `text typed after the run stopped shows the paused line with Continue and Start over instead of going to the plain chat`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        h.agent.start("doc")
        h.fills.saveFill(h.fill().copy(status = FormFillStatus.STOPPED))
        val calls = h.model.sent.size

        val route = h.agent.route("doc", "Was kostet der Kurs?")

        assertThat(route).isEqualTo(FormRoute.HANDLED)
        val line = h.shown().last().first
        assertThat(line.text).isEqualTo(FormText.AGENT_PAUSED)
        assertThat(line.chips.map { it.action }).containsExactly(FormChipAction.CONTINUE, FormChipAction.START_OVER).inOrder()
        assertThat(h.model.sent).hasSize(calls)
        assertThat(h.userTexts(h.messages())).contains("Was kostet der Kurs?")

        // A finished fill is not paused: the plain chat answers again.
        h.fills.saveFill(h.fill().copy(status = FormFillStatus.DONE))
        assertThat(h.agent.route("doc", "Was kostet der Kurs?")).isEqualTo(FormRoute.NOT_FOR_FORM)
    }

    @Test
    fun `a stale Continue does nothing while the run is not stopped`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        h.agent.start("doc")
        val calls = h.model.sent.size

        h.agent.chip("doc", FormChip(FormChipAction.CONTINUE, labelCode = com.postsaimanager.core.model.FormChipLabel.CONTINUE), "Continue")

        assertThat(h.model.sent).hasSize(calls)
    }
}
