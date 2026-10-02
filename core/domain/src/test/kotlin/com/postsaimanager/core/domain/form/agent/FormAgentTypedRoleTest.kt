package com.postsaimanager.core.domain.form.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
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
 * The third device loop: the user typed the name of the person who has a role ("Erika Test") and the model kept calling ask_user
 * ("already answered") until the step limit. In ROLE_TYPED only fill_field and skip_field are exposed, the suggestion names the exact
 * call for the first open name field, a refused fill repeats it, and the second refusal leaves the field to the user.
 */
class FormAgentTypedRoleTest {

    private fun chip(text: String) = FormChip(FormChipAction.ANSWER, label = text, arg = text)

    private fun askCall(question: String, vararg chips: String) = AgentEntry.Call(
        "ask1", "ask_user",
        JsonObject(mapOf("question" to JsonPrimitive(question), "chips" to JsonArray(chips.map(::JsonPrimitive)))),
    )

    private val roleQuestion = "Wer ist die erziehungsberechtigte Person?"
    private val nameQuestion = "Wie lautet der volle Name der erziehungsberechtigten Person?"

    /** The run up to the name question: the user is the subject, chose "someone else" for the guardian and was asked the name. */
    private suspend fun AgentHarness.untilNameAsked() {
        model.reply(call("read_form"))
        model.reply(call("list_people"))
        model.reply(ask("Für wen ist das Formular?", "Ahmad", "Me"))
        agent.start("doc")
        model.reply(call("fill_from_profile", "person_id" to "p1", "role" to "subject"))
        model.reply(ask(roleQuestion, "Ahmad", "Someone else"))
        agent.chip("doc", chip("Me"), "Me")
        model.reply(ask(nameQuestion))
        agent.chip("doc", chip("Someone else"), "Someone else")
    }

    @Test
    fun `the table exposes only fill_field and skip_field once the user typed who has the role`() {
        assertThat(ToolPolicy().allowed(FormStage.ROLE_TYPED, rememberPending = true)).containsExactly("fill_field", "skip_field").inOrder()
    }

    @Test
    fun `the device loop ask_user is refused, the fill with the typed name succeeds and the run moves on to the next open field`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.untilNameAsked()
        val alias = h.alias("Name der Erziehungsberechtigten")

        // The user types the name. The model asks again, as on the device...
        h.model.reply(h.ask("Wie ist der Kontoinhaber/in?"))
        // ...and is told ask_user is not available, with the exact call in the state.
        h.model.reply { message ->
            assertThat(message).contains("ask_user is not available now. Available now: fill_field, skip_field")
            assertThat(message).contains("suggested next: the user typed who is")
            assertThat(message).contains("fill_field(field_id=$alias, value=Erika Test, source=user)")
            assertThat(message).doesNotContain("already answered")
            h.call("fill_field", "field_id" to alias, "value" to "Erika Test", "source" to "user")
        }
        // The role is settled: the next open field is asked like any field.
        h.model.reply { message ->
            assertThat(message).contains("\"filled\":\"$alias|Name der Erziehungsberechtigten|Erika Test\"")
            h.ask("Wie lautet die IBAN?")
        }
        h.agent.route("doc", "Erika Test")

        assertThat(h.field("Name der Erziehungsberechtigten").value).isEqualTo("Erika Test")
        assertThat(h.field("Name der Erziehungsberechtigten").valueSource).isEqualTo(FormValueSource.USER)
        assertThat(h.results().none { it.contains("already answered") }).isTrue()
        assertThat(h.shown().last { it.first.kind == FormMessageKind.QUESTION }.second).isEqualTo("Wie lautet die IBAN?")
    }

    @Test
    fun `a refused fill repeats the exact call and the second refusal leaves the field to the user with a status line`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.untilNameAsked()
        val alias = h.alias("Name der Erziehungsberechtigten")

        h.model.reply(h.call("fill_field", "field_id" to "f99", "value" to "Erika Test", "source" to "user"))
        h.model.reply { message ->
            assertThat(message).contains("unknown field_id")
            assertThat(message).contains("Call exactly: fill_field(field_id=$alias, value=Erika Test, source=user)")
            h.call("fill_field", "field_id" to "f98", "value" to "Erika Test", "source" to "user")
        }
        h.model.reply { message ->
            assertThat(message).contains("\"skipped\":\"Name der Erziehungsberechtigten\"")
            h.ask("Wie lautet die IBAN?")
        }
        h.agent.route("doc", "Erika Test")

        assertThat(h.field("Name der Erziehungsberechtigten").skipped).isTrue()
        assertThat(h.field("Name der Erziehungsberechtigten").value).isNull()
        assertThat(h.shown().any { it.first.text == FormText.FIELD_LEFT_TO_USER && it.first.args == listOf("Name der Erziehungsberechtigten") }).isTrue()
        assertThat(h.shown().last { it.first.kind == FormMessageKind.QUESTION }.second).isEqualTo("Wie lautet die IBAN?")
    }

    @Test
    fun `with several name fields the suggestion covers the first and the stage moves on after it is filled`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.exec("read_form")
        h.exec("fill_from_profile", "person_id" to "p1", "role" to "subject")
        val guardianName = h.field("Name der Erziehungsberechtigten")
        h.fills.saveFields("fill-doc", h.fields() + guardianName.copy(id = "given", labelText = "Vorname", orderIndex = guardianName.orderIndex + 1000))
        val env = h.envFor("doc")
        val guidance = FormGuidance(env)
        val reply = UserReply("Erika Test", askCall(nameQuestion))

        val before = guidance.roleSituation(h.fields(), reply) as RoleSituation.Typed
        assertThat(before.nameFields.map { it.labelText }).containsExactly("Name der Erziehungsberechtigten", "Vorname").inOrder()
        val firstAlias = h.alias("Name der Erziehungsberechtigten")
        assertThat(guidance.state(reply)).contains("fill_field(field_id=$firstAlias, value=Erika Test, source=user)")

        // Once the first is filled by the user the role is settled: the other name field is an ordinary open field.
        h.exec("fill_field", "field_id" to firstAlias, "value" to "Erika Test", "source" to "user", replies = listOf("Erika Test"), previousEnd = askCall(nameQuestion))
        assertThat((guidance.roleSituation(h.fields(), reply) as? RoleSituation.Typed)?.role).isNotEqualTo(FormRole.GUARDIAN)
    }

    @Test
    fun `a role without an open name field never enters ROLE_TYPED`() = runTest {
        val h = AgentHarness(dynamicTools = true)
        h.exec("read_form")
        h.exec("fill_from_profile", "person_id" to "p1", "role" to "subject")
        // The guardian's name field is something other than a name (a phone number): the typed text has nowhere to go.
        h.fills.saveFields("fill-doc", h.fields().map { if (it.labelText == "Name der Erziehungsberechtigten") it.copy(dataKey = "phone") else it })
        val guidance = FormGuidance(h.envFor("doc"))
        val reply = UserReply("Erika Test", askCall(nameQuestion))

        assertThat(guidance.roleSituation(h.fields(), reply)).isNotInstanceOf(RoleSituation.Typed::class.java)
        assertThat(guidance.stage(h.fields(), reply)).isEqualTo(FormStage.OPEN_FIELDS)
        assertThat(FormRole.GUARDIAN).isNotNull()
    }
}
