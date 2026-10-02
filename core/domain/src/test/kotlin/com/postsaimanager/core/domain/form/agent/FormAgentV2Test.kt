package com.postsaimanager.core.domain.form.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.QwenToolCallFormat
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The agent's second version for small models: tools exposed per state (the policy, the grammar of each subset), the role answered
 * with a typed name, the fresh session on Start over, role names from field labels and the few-shot example in the prompt.
 */
class FormAgentV2Test {

    private fun chip(text: String) = FormChip(FormChipAction.ANSWER, label = text, arg = text)

    private fun askCall(vararg chips: String) = AgentEntry.Call(
        "ask1", "ask_user",
        JsonObject(mapOf("question" to JsonPrimitive("Q?"), "chips" to JsonArray(chips.map(::JsonPrimitive)))),
    )

    private val start = AgentEntry.UserText("Help me fill in this form.", isStart = true)

    private suspend fun AgentHarness.allowed(vararg entries: AgentEntry): List<String> =
        tools.specFor("doc").allowedTools(listOf(start) + entries).orEmpty()

    // ── The policy: which tools in which state ──

    @Test
    fun `before the form is read only read_form is exposed`() = runTest {
        val h = AgentHarness(dynamicTools = true)

        assertThat(h.allowed()).containsExactly("read_form")
    }

    @Test
    fun `with the subject unknown the model may list the people or ask`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.exec("read_form")

        assertThat(h.allowed()).containsExactly("list_people", "ask_user").inOrder()
    }

    @Test
    fun `a subject the user named exposes only fill_from_profile, a guessed guardian also ask_user`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.exec("read_form")

        // The user answered the question about the subject with a stored person's name (tapped or typed): the answer must be used.
        assertThat(h.allowed(askCall("Ahmad", "Me"), AgentEntry.UserText("Ahmad"))).containsExactly("fill_from_profile")
        assertThat(h.allowed(askCall("Ahmad", "Me"), AgentEntry.UserText("ahmad"))).containsExactly("fill_from_profile")

        // The child's guardian is a guess (not an answer): it may be filled or asked about.
        h.exec("fill_from_profile", "person_id" to "p2", "role" to "subject")
        assertThat(h.allowed()).containsExactly("fill_from_profile", "ask_user").inOrder()
    }

    @Test
    fun `an open role without a person exposes ask_user and skip_field`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.exec("read_form")
        h.exec("fill_from_profile", "person_id" to "p1", "role" to "subject") // Me, an adult: nobody is their guardian

        assertThat(h.allowed()).containsExactly("ask_user", "skip_field").inOrder()
    }

    @Test
    fun `with every role settled the open fields expose ask_user, fill_field, skip_field and show_on_page`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        settleRoles(h)

        assertThat(h.allowed()).containsExactly("ask_user", "fill_field", "skip_field", "show_on_page").inOrder()
    }

    @Test
    fun `a typed answer that may be the yes to a remember question adds remember_detail`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        settleRoles(h)
        h.exec("fill_field", "field_id" to h.alias("Allergien / Hinweise zur Gesundheit"), "value" to "Nussallergie", "source" to "user", replies = listOf("Nussallergie"))

        val remember = askCall("Ja", "Nein")
        assertThat(h.allowed(remember, AgentEntry.UserText("Ja"))).containsExactly("ask_user", "fill_field", "skip_field", "show_on_page", "remember_detail").inOrder()
        // Without an answer to a question in this turn there is nothing to remember yet.
        assertThat(h.allowed()).doesNotContain("remember_detail")
    }

    @Test
    fun `with nothing open the card, the page chip and finish are exposed`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        settleRoles(h)
        h.fields().filter { it.value == null }.forEach { h.fills.setSkipped(it.id, true, h.nowMs) }

        assertThat(h.allowed()).containsExactly("show_fill_card", "show_on_page", "finish").inOrder()
    }

    private suspend fun settleRoles(h: AgentHarness) {
        h.exec("read_form")
        h.exec("fill_from_profile", "person_id" to "p2", "role" to "subject")
        h.exec("fill_from_profile", "person_id" to "p1", "role" to "guardian")
        h.exec("fill_from_profile", "person_id" to "p1", "role" to "payer")
    }

    // ── The grammar of a subset (golden) ──

    @Test
    fun `the grammar of the subset for an open role names only ask_user and skip_field (golden)`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        val spec = h.tools.specFor("doc")
        val grammar = QwenToolCallFormat().grammar(spec.tools.specs(listOf("ask_user", "skip_field")))

        assertThat(grammar).isEqualTo(
            """
            root ::= "<tool_call>\n" call "\n</tool_call>"
            call ::= call-ask-user | call-skip-field
            param-ask-user-question ::= "<parameter=question>\n" xtext "\n</parameter>\n"
            param-ask-user-chips ::= "<parameter=chips>\n" jarray "\n</parameter>\n"
            call-ask-user ::= "<function=ask_user>\n" param-ask-user-question param-ask-user-chips? "</function>"
            param-skip-field-field-id ::= "<parameter=field_id>\n" xtext "\n</parameter>\n"
            call-skip-field ::= "<function=skip_field>\n" param-skip-field-field-id "</function>"
            xtext ::= [^<\x00-\x1F]+
            """.trimIndent() + "\n" + jsonRules(grammar),
        )
        assertThat(grammar).doesNotContain("fill_field")
    }

    /** The JSON value rules every grammar ends with (shared, pinned by the format's own golden test). */
    private fun jsonRules(grammar: String): String = grammar.lines().dropWhile { !it.startsWith("jstring ::=") }.joinToString("\n")

    @Test
    fun `each state's subset has its own grammar and the loop asks for it step by step`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        h.agent.start("doc")

        val functions = h.model.requests.map { request -> Regex("<function=([a-z_]+)>").findAll(request.grammar!!).map { it.groupValues[1] }.toList() }
        assertThat(functions[0]).containsExactly("read_form")
        assertThat(functions[1]).containsExactly("list_people", "ask_user")
        assertThat(functions[2]).containsExactly("list_people", "ask_user")
        // The model read which tools its step may use, at the end of each result.
        assertThat(h.model.sent[1]).contains("\"tools_now\":\"list_people, ask_user\"")
    }

    // ── The loop of the device: a payer who is somebody else, typed ──

    @Test
    fun `a role answered with Someone else and then a typed name is filled with the typed name and the run completes`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        // Turn 1: read, list, ask for whom the form is. The user taps Me.
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("list_people"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        h.agent.start("doc")

        // Turn 2: the subject is filled; the guardian is nobody known, so the model asks, offering the chip for somebody else.
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "subject"))
        h.model.reply(h.ask("Wer ist die erziehungsberechtigte Person?", "Ahmad", "Someone else"))
        h.agent.chip("doc", chip("Me"), "Me")

        // Turn 3: the user taps "Someone else": the suggestion is to ask for the name, not the same question again.
        h.model.reply { message ->
            assertThat(message).contains("ask_user for the full name of")
            assertThat(message).doesNotContain("ask_user who is")
            h.ask("Wie lautet der volle Name der erziehungsberechtigten Person?")
        }
        h.agent.chip("doc", chip("Someone else"), "Someone else")

        // Turn 4: the user types the name. Only fill_field, skip_field and ask_user are exposed, and the suggestion is the fill.
        val aliasGuardian = h.alias("Name der Erziehungsberechtigten")
        h.model.reply { message ->
            assertThat(message).contains("\"answer\":\"Erika Test\"")
            assertThat(message).contains("fill_field(field_id=<one of $aliasGuardian")
            assertThat(message).contains("value=Erika Test, source=user")
            assertThat(message).doesNotContain("suggested next: ask_user")
            h.call("fill_field", "field_id" to aliasGuardian, "value" to "Erika Test", "source" to "user")
        }
        h.model.reply(h.ask("Wer ist der Kontoinhaber?", "Me", "Ahmad", "Someone else"))
        h.agent.route("doc", "Erika Test")

        assertThat(h.field("Name der Erziehungsberechtigten").value).isEqualTo("Erika Test")
        assertThat(h.field("Name der Erziehungsberechtigten").valueSource).isEqualTo(FormValueSource.USER)
        val typedStep = h.model.requests.size - 2
        assertThat(h.model.requests[typedStep].grammar).contains("<function=fill_field>")
        assertThat(h.model.requests[typedStep].grammar).doesNotContain("<function=fill_from_profile>")

        // Turn 5: the next role, the payer, is a stored person: filled from them, then the run goes on with the open fields.
        h.model.reply(h.call("fill_from_profile", "person_id" to "p1", "role" to "payer"))
        // A German question that uses none of the form's words is not English, so the language check lets it through at once.
        h.model.reply(h.ask("Möchten Sie noch etwas ändern?"))
        h.agent.chip("doc", chip("Me"), "Me")

        assertThat(h.field("IBAN").value).isNotNull()
        assertThat(h.shown().last { it.first.kind == FormMessageKind.QUESTION }.second).isEqualTo("Möchten Sie noch etwas ändern?")
        assertThat(h.results().none { it.contains("already answered") }).isTrue()
    }

    // ── Start over: a fresh session ──

    @Test
    fun `Start over resets the engine session, so the new run has a fresh history`() = runTest {
        val h = AgentHarness()
        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        h.agent.start("doc")
        assertThat(h.model.sessions).hasSize(1)
        assertThat(h.model.resets).isEqualTo(0)

        h.model.reply(h.call("read_form"))
        h.model.reply(h.ask("Für wen ist das Formular?", "Ahmad", "Me"))
        h.agent.chip("doc", FormChip(FormChipAction.START_OVER, labelCode = com.postsaimanager.core.model.FormChipLabel.START_OVER), "Start over")

        assertThat(h.model.resets).isEqualTo(1)
        assertThat(h.model.sessions).hasSize(2)
        assertThat(h.model.sessions.last().history).isEmpty()
    }

    // ── Role names ──

    private fun field(id: String, label: String, section: String?, role: FormRole) = FormField(
        id = id, formFillId = "fill", documentId = "doc", page = 1, labelText = label, labelBox = null, fillBox = null,
        kind = FormFieldKind.TEXT, section = section, role = role, orderIndex = id.removePrefix("x").toInt(),
    )

    @Test
    fun `a long section heading does not name the role while the role's field has a label`() = runTest {
        val h = AgentHarness()
        val env = h.envFor("doc")
        val long = "Ich ermächtige den Verein, den Beitrag von meinem Konto einzuziehen"
        val fields = listOf(field("x1", "Kontoinhaber/in", long, FormRole.PAYER), field("x2", "IBAN", long, FormRole.PAYER))

        assertThat(env.roles.nameIn(fields, FormRole.PAYER)).isEqualTo("Kontoinhaber/in")
    }

    @Test
    fun `a short section heading still names the role with the label`() = runTest {
        val h = AgentHarness()
        val env = h.envFor("doc")
        val fields = listOf(field("x1", "Kontoinhaber/in", "Zahlung per Lastschrift", FormRole.PAYER), field("x2", "IBAN", "Zahlung per Lastschrift", FormRole.PAYER))

        assertThat(env.roles.nameIn(fields, FormRole.PAYER)).isEqualTo("Zahlung per Lastschrift (Kontoinhaber/in)")
    }

    // ── The few-shot example ──

    @Test
    fun `the prompt carries one worked example and stays inside its size`() {
        val prompt = FormAgentSpec.instructions("German")

        assertThat(prompt).contains("Example (invented pool-pass form")
        assertThat(prompt).contains("fill_from_profile(person_id=p2, role=subject)")
        assertThat(prompt).contains("tools_now")
    }
}
