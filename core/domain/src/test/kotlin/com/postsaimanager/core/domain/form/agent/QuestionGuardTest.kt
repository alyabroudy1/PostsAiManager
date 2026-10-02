package com.postsaimanager.core.domain.form.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The checks that turn an `ask_user` that is no question for the user back to the model (a bare label, the child as the guardian,
 * a person's name as the chip of a field about something else), each with a scripted model that recovers after the error result, and the
 * role wording (the form's own words, else the string resources of the form's language, never the English enum word).
 */
class QuestionGuardTest {

    private val german = object : FormWording {
        override fun roleName(role: FormRole, language: Locale): String = when (role) {
            FormRole.GUARDIAN -> "Erziehungsberechtigte/r"
            FormRole.PAYER -> "Kontoinhaber/in"
            FormRole.SIGNER -> "Unterzeichnende/r"
            else -> "Weitere Person"
        }

        override fun someoneElse(language: Locale): String = "Jemand anderes"
    }

    private suspend fun AgentHarness.subject(person: String) {
        exec("read_form")
        exec("fill_from_profile", "person_id" to person, "role" to "subject")
    }

    private suspend fun AgentHarness.tryAsk(question: String, vararg chips: String) =
        exec("ask_user", "question" to question, json = mapOf("chips" to JsonArray(chips.map(::JsonPrimitive))))

    private suspend fun AgentHarness.shownQuestions() = shown().filter { it.first.kind == FormMessageKind.QUESTION }.map { it.second }

    // ── 1. A label is no question ──

    @Test
    fun `a bare section title or label as the question is refused and the model recovers with a real question`() = runTest {
        val h = AgentHarness(wording = german)
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.ask("Angaben zum Kind", "Ahmad"))
        h.model.reply(h.ask("E-Mail:"))
        h.model.reply(h.ask("Hat Ahmad das Seepferdchen schon?", "Ja", "Nein"))
        h.agent.start("doc")

        val errors = h.results().filter { it.contains("only a label or heading") }
        assertThat(errors).hasSize(2)
        assertThat(errors.first()).contains("Angaben zum Kind")
        assertThat(h.shownQuestions()).containsExactly("Hat Ahmad das Seepferdchen schon?")
    }

    @Test
    fun `a label with punctuation added is a label, a longer sentence with the label is a question`() = runTest {
        val h = AgentHarness()
        h.subject("p2")

        assertThat(h.tryAsk("Geburtsort?").errorMessage).contains("only a label")
        assertThat(h.tryAsk("Name des Kindes :").errorMessage).contains("only a label")
        assertThat(h.tryAsk("Wo ist der Geburtsort von Ahmad?").ok).isTrue()
        assertThat(h.tryAsk("Wie lautet der Name des Kindes?").ok).isTrue()
    }

    // ── 2. The child is not the guardian, the payer or the signer ──

    @Test
    fun `the child is refused as the guardian chip and the model recovers with the other people and Someone else`() = runTest {
        val h = AgentHarness(wording = german, withPartner = true)
        h.model.reply(h.call("read_form"))
        h.model.reply(h.call("fill_from_profile", "person_id" to "p2", "role" to "subject"))
        h.model.reply(h.ask("Wer ist der Erziehungsberechtigte?", "Ahmad", "Me"))
        h.model.reply { message ->
            assertThat(message).contains("Ahmad is the person the form is for")
            assertThat(message.replace("\\\"", "\"")).contains("\"Me\", \"Anna\", \"Jemand anderes\"")
            h.ask("Wer ist der Erziehungsberechtigte?", "Me", "Anna", "Jemand anderes")
        }
        h.agent.start("doc")

        assertThat(h.shownQuestions()).containsExactly("Wer ist der Erziehungsberechtigte?")
    }

    @Test
    fun `a minor is not offered as the payer or the signer either, an adult subject is`() = runTest {
        val child = AgentHarness(wording = german)
        child.subject("p2")
        assertThat(child.tryAsk("Wer ist der Kontoinhaber?", "Ahmad", "Me").errorMessage).contains("a minor")
        assertThat(child.tryAsk("Wer ist der Kontoinhaber?", "Me", "Jemand anderes").ok).isTrue()

        // The subject is an adult (Me): Me may be the payer; the guardian role still never repeats the subject.
        val adult = AgentHarness(wording = german)
        adult.subject("p1")
        assertThat(adult.tryAsk("Wer ist der Kontoinhaber?", "Me", "Ahmad").ok).isTrue()
        assertThat(adult.tryAsk("Wer ist die Erziehungsberechtigte?", "Me", "Ahmad").errorMessage).contains("the person the form is for")
    }

    @Test
    fun `before the subject is known the people are fine as chips`() = runTest {
        val h = AgentHarness()
        h.exec("read_form")
        assertThat(h.tryAsk("Für wen ist das Formular?", "Ahmad", "Me").ok).isTrue()
    }

    // ── 3. The chips fit the field ──

    @Test
    fun `a person's name as the chip of a question about another field is refused, the field's options are accepted`() = runTest {
        val h = AgentHarness()
        h.subject("p2")
        val choice = h.fields().first { it.options.size >= 2 }
        val question = "Bitte beantworten: ${choice.labelText} (ja oder nein)"

        assertThat(h.tryAsk(question, "Ahmad", "Me").errorMessage).contains("does not belong to the question about \"${choice.labelText}\"")
        assertThat(h.tryAsk(question, "Ahmad").errorMessage).contains(choice.options.first())
        assertThat(h.tryAsk(question, *choice.options.toTypedArray()).ok).isTrue()
        assertThat(h.tryAsk(question).ok).isTrue()
    }

    @Test
    fun `a field without options takes no chips of other kinds, but its stored value is fine`() = runTest {
        val h = AgentHarness()
        h.subject("p2")

        assertThat(h.tryAsk("Gibt es Allergien / Hinweise zur Gesundheit bei Ahmad?", "Ja", "Nein").errorMessage).contains("use no chips")
        assertThat(h.tryAsk("Gibt es Allergien / Hinweise zur Gesundheit bei Ahmad?").ok).isTrue()
        // A stored value of a person for the field's key is a fine chip.
        assertThat(h.tryAsk("Welche Telefon (Notfall) soll ich eintragen?", "0151 2345678").ok).isTrue()
    }

    @Test
    fun `a question about a filled field, such as remembering it, is not checked against its label`() = runTest {
        val h = AgentHarness()
        h.subject("p2")
        assertThat(h.field("Geburtsort").value).isNotNull()

        assertThat(h.tryAsk("Soll ich den Geburtsort für Ahmad merken?", "Ja", "Nein").ok).isTrue()
    }

    // ── Role wording ──

    @Test
    fun `the roles are named in the form's own words, in the state and in list_people, never by the enum word`() = runTest {
        val h = AgentHarness(wording = german)
        h.exec("read_form")
        h.exec("fill_from_profile", "person_id" to "p2", "role" to "subject")

        val state = h.tools.specFor("doc").stateSummary()!!
        assertThat(state).contains("Angaben zum Kind (Name des Kindes)=Ahmad (p2)")
        assertThat(state).doesNotContain("subject=")

        val people = h.exec("list_people").toModelText()
        assertThat(people).contains("\"called_in_the_form\":\"Erziehungsberechtigte (Name der Erziehungsberechtigten)\"")
        assertThat(people).contains("\"called_in_the_form\":\"Zahlung per Lastschrift (Kontoinhaber)\"")
    }

    @Test
    fun `a role the form has no text for gets its name from the resources in the form's language`() = runTest {
        val h = AgentHarness(wording = german)
        h.exec("read_form")
        val env = h.envFor("doc")

        assertThat(env.roles.nameIn(emptyList(), FormRole.PAYER)).isEqualTo("Kontoinhaber/in")
        assertThat(env.roles.nameIn(emptyList(), FormRole.GUARDIAN)).isEqualTo("Erziehungsberechtigte/r")
        assertThat(env.roles.someoneElse()).isEqualTo("Jemand anderes")
    }

    @Test
    fun `a role without a candidate is asked about in the form's words, with the other people and Someone else as chips`() = runTest {
        val h = AgentHarness(wording = german, withPartner = true)
        h.exec("read_form")
        h.exec("fill_from_profile", "person_id" to "p1", "role" to "subject") // Me, an adult: nobody is "their guardian"

        val state = h.tools.specFor("doc").stateSummary()!!
        assertThat(state).contains("suggested next: ask_user who is \"Erziehungsberechtigte (Name der Erziehungsberechtigten)\"")
        assertThat(state).contains("\"Ahmad\", \"Anna\", \"Jemand anderes\"")
        assertThat(state).doesNotContain("\"Me\"")
        assertThat(state).doesNotContain("role guardian")
    }
}
