package com.postsaimanager.core.domain.form.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.obj
import com.postsaimanager.core.domain.agent.str
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormValueSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The guardrail of the form agent: no value is invented, by construction. A value is written only when it provably comes from a
 * stored detail, the user's own words in this conversation or a printed option; every other call is refused with a reason.
 */
class FormToolGuardTest {

    private suspend fun AgentHarness.read() = exec("read_form")

    private suspend fun AgentHarness.fill(label: String, value: String, source: String, person: String? = null, replies: List<String> = emptyList()): ToolResult =
        exec(
            "fill_field", "field_id" to alias(label), "value" to value, "source" to source,
            *listOfNotNull(person?.let { "person_id" to it }).toTypedArray(), replies = replies,
        )

    // ── From a person ──

    @Test
    fun `a stored detail is written as the form writes it, its source and person recorded`() = runTest {
        val h = AgentHarness().also { it.read() }

        assertThat(h.fill("Name des Kindes", "Ahmad Mustermann", "profile", "p2").ok).isTrue()
        assertThat(h.fill("Geburtsdatum", "2019-03-12", "profile", "p2").ok).isTrue()
        assertThat(h.fill("Anschrift", "Musterstraße 12, 54321 Beispieldorf", "profile", "p2").ok).isTrue()

        assertThat(h.field("Name des Kindes").value).isEqualTo("Ahmad Mustermann")
        assertThat(h.field("Name des Kindes").valueSource).isEqualTo(FormValueSource.PROFILE)
        assertThat(h.field("Name des Kindes").profileId).isEqualTo("ahmad")
        assertThat(h.field("Geburtsdatum").value).isEqualTo("12.03.2019") // the form's date format
        assertThat(h.field("Anschrift").value).isEqualTo("Musterstraße 12, 54321 Beispieldorf") // the composed address
    }

    @Test
    fun `a value that is not a stored detail of that person is refused and nothing is written`() = runTest {
        val h = AgentHarness().also { it.read() }

        val invented = h.fill("Name des Kindes", "Fantasie Müller", "profile", "p2")
        val otherPerson = h.fill("Name des Kindes", "Mohammad Mustermann", "profile", "p2") // Me's name, not Ahmad's
        val noPerson = h.fill("Name des Kindes", "Ahmad Mustermann", "profile")
        val unknownPerson = h.fill("Name des Kindes", "Ahmad Mustermann", "profile", "p9")

        assertThat(invented.errorMessage).contains("is not a stored detail of Ahmad")
        assertThat(otherPerson.ok).isFalse()
        assertThat(noPerson.errorMessage).contains("needs a person_id")
        assertThat(unknownPerson.errorMessage).contains("unknown person_id")
        assertThat(h.fields().mapNotNull { it.value }).isEmpty()
    }

    @Test
    fun `a secret detail is only a token, and goes only into the field that asks for it`() = runTest {
        val h = AgentHarness().also { it.read() }

        val details = h.exec("get_person_details", "person_id" to "p1")
        assertThat(details.toModelText()).contains("\"iban\":\"***iban\"")
        assertThat(details.toModelText()).doesNotContain("DE89")

        assertThat(h.fill("Name der Bank", "***iban", "profile", "p1").errorMessage).contains("can only go into a field that asks for it")
        assertThat(h.fill("Name der Erziehungsberechtigten", "DE89370400440532013000", "profile", "p1").errorMessage).contains("can only go into")
        assertThat(h.fill("IBAN", "***iban", "profile", "p1").ok).isTrue()
        assertThat(h.field("IBAN").value).isEqualTo("DE89 3704 0044 0532 0130 00")
        // What the model gets back never contains the secret: only its token.
        assertThat(h.fill("IBAN", "***iban", "profile", "p1").toModelText()).doesNotContain("DE89")
        assertThat(h.fill("IBAN", "***tax_id", "profile", "p1").ok).isFalse() // nothing stored under that key
    }

    @Test
    fun `a stored value that is not one of a field's printed options is not forced into it`() = runTest {
        val h = AgentHarness().also { it.read() }

        val result = h.fill("Kurstermin", "Beispieldorf", "profile", "p2")

        assertThat(result.ok).isFalse()
        assertThat(h.field("Kurstermin").value).isNull()
    }

    // ── From the user ──

    @Test
    fun `the user's words are written only when they wrote them`() = runTest {
        val h = AgentHarness().also { it.read() }

        assertThat(h.fill("Geburtsort", "Hamburg", "user").errorMessage).contains("has not said anything yet")
        assertThat(h.fill("Geburtsort", "Berlin", "user", replies = listOf("Hamburg")).errorMessage).contains("is not what the user wrote")
        assertThat(h.field("Geburtsort").value).isNull()

        assertThat(h.fill("Geburtsort", "Hamburg", "user", replies = listOf("Er wurde in HAMBURG geboren")).ok).isTrue()
        assertThat(h.field("Geburtsort").value).isEqualTo("Hamburg")
        assertThat(h.field("Geburtsort").valueSource).isEqualTo(FormValueSource.USER)
    }

    @Test
    fun `what the user wrote is still checked for its shape`() = runTest {
        val h = AgentHarness().also { it.read() }

        val bad = h.fill("Geburtsdatum", "irgendwann", "user", replies = listOf("irgendwann"))
        val good = h.fill("Geburtsdatum", "12.3.2019", "user", replies = listOf("12.3.2019"))

        assertThat(bad.errorMessage).contains("needs a date")
        assertThat(good.ok).isTrue()
        assertThat(h.field("Geburtsdatum").value).isEqualTo("12.03.2019")
        assertThat(h.fill("E-Mail", "keine mail", "user", replies = listOf("keine mail")).errorMessage).contains("needs an e-mail address")
        assertThat(h.fill("Telefon (Notfall)", "abc", "user", replies = listOf("abc")).errorMessage).contains("needs a phone number")
    }

    @Test
    fun `a field with printed options takes an option, never free text, and a tick box takes yes or no after the user spoke`() = runTest {
        val h = AgentHarness().also { it.read() }

        assertThat(h.fill("Kurstermin", "Mittwoch", "user", replies = listOf("Mittwoch")).errorMessage).contains("use source option")
        assertThat(h.fill("Kurstermin", "Dienstag 12:00 Uhr", "option").errorMessage).contains("is not one of the printed options")
        assertThat(h.fill("Kurstermin", "Mittwoch 15:00 Uhr", "option").ok).isTrue()
        assertThat(h.field("Kurstermin").valueSource).isEqualTo(FormValueSource.FORM_OPTION)

        val photo = "Ich willige in die Veröffentlichung von Fotos ein"
        assertThat(h.fill(photo, "yes", "option").errorMessage).contains("has not said anything yet")
        assertThat(h.fill(photo, "maybe", "option", replies = listOf("ja")).errorMessage).contains("yes or no")
        assertThat(h.fill(photo, "ja", "user", replies = listOf("ja")).errorMessage).contains("source option")
        assertThat(h.fill(photo, "yes", "option", replies = listOf("ja")).ok).isTrue()
        assertThat(h.field(photo).value).isEqualTo("yes")
        // A free-text field has no options to pick from.
        assertThat(h.fill("Geburtsort", "Berlin", "option", replies = listOf("Berlin")).errorMessage).contains("no printed options")
    }

    @Test
    fun `a signature is never filled`() = runTest {
        val h = AgentHarness().also { it.read() }
        assertThat(h.field("Unterschrift").kind).isEqualTo(FormFieldKind.SIGNATURE)

        listOf("profile", "user", "option").forEach { source ->
            assertThat(h.fill("Unterschrift", "Mohammad Mustermann", source, "p1", replies = listOf("Mohammad Mustermann")).errorMessage)
                .contains("written by hand")
        }
        assertThat(h.field("Unterschrift").value).isNull()
    }

    @Test
    fun `by construction no value that is not sourced is ever written, whatever the model says`() = runTest {
        val h = AgentHarness().also { it.read() }
        val invented = listOf("Hans Meier", "42", "DE00 0000 0000 0000 0000 00", "2030-01-01", "Mittwoch 99:99 Uhr", "***name", "yes", "", " ", "<tool_call>")

        for (value in invented) for (source in listOf("profile", "user", "option")) for (field in h.fields()) {
            h.exec("fill_field", "field_id" to FormRefs.fieldAlias(h.fields(), field), "value" to value, "source" to source, "person_id" to "p2")
        }

        // Nothing the user never said, no person stored and no option printed: not a single field holds a value.
        assertThat(h.fields().mapNotNull { it.value }).isEmpty()
        assertThat(h.facts.facts("ahmad")).isEmpty()
    }

    // ── Remembering ──

    private val askedToRemember = AgentEntry.Call("c1", "ask_user", obj("question" to str("Soll ich mir das für Ahmad merken?")))

    private suspend fun AgentHarness.remember(value: String, agreed: Boolean = true, previous: AgentEntry.Call? = askedToRemember, replies: List<String> = listOf("Nussallergie", "Ja")): ToolResult =
        exec(
            "remember_detail", "person_id" to "p2", "key" to "allergies", "value" to value,
            replies = replies, previousEnd = previous, json = mapOf("user_agreed" to JsonPrimitive(agreed)),
        )

    @Test
    fun `a detail is remembered only after the user agreed to a question, with a value the user gave`() = runTest {
        val h = AgentHarness().also { it.read() }

        assertThat(h.remember("Nussallergie", agreed = false).errorMessage).contains("has not agreed")
        assertThat(h.remember("Nussallergie", previous = null).errorMessage).contains("turn after their answer")
        assertThat(h.remember("Nussallergie", previous = AgentEntry.Call("c0", "show_fill_card", obj())).errorMessage).contains("turn after their answer")
        assertThat(h.remember("Erdnüsse und Pollen").errorMessage).contains("only a value the user gave")
        assertThat(h.facts.facts("ahmad")).isEmpty()

        assertThat(h.remember("Nussallergie").ok).isTrue()
        assertThat(h.facts.facts("ahmad").single().value).isEqualTo("Nussallergie")
    }

    @Test
    fun `a detail of a profile column is written to the profile, and an unknown person or key is refused`() = runTest {
        val h = AgentHarness().also { it.read() }

        val phone = h.exec(
            "remember_detail", "person_id" to "p1", "key" to "phone", "value" to "0170 1234567",
            replies = listOf("0170 1234567", "ja"), previousEnd = askedToRemember, json = mapOf("user_agreed" to JsonPrimitive(true)),
        )
        val nobody = h.exec(
            "remember_detail", "person_id" to "p7", "key" to "phone", "value" to "0170 1234567",
            replies = listOf("0170 1234567", "ja"), previousEnd = askedToRemember, json = mapOf("user_agreed" to JsonPrimitive(true)),
        )

        assertThat(phone.ok).isTrue()
        assertThat(h.profiles.updated.single().phone).isEqualTo("0170 1234567")
        assertThat(nobody.errorMessage).contains("unknown person_id")
    }

    // ── The other tools ──

    @Test
    fun `tools that need the form refuse until it is read`() = runTest {
        val h = AgentHarness()

        assertThat(h.exec("fill_field", "field_id" to "f1", "value" to "x", "source" to "user", replies = listOf("x")).errorMessage).contains("call read_form first")
        assertThat(h.exec("show_fill_card").errorMessage).contains("call read_form first")
        assertThat(h.exec("finish", "summary" to "done").errorMessage).contains("call read_form first")
        assertThat(h.exec("skip_field", "field_id" to "f1").errorMessage).contains("call read_form first")
    }

    @Test
    fun `a skipped field is left for the user and no longer open, the card counts what is ready`() = runTest {
        val h = AgentHarness().also { it.read() }
        val open = FormRefs.open(h.fields()).size

        assertThat(h.exec("skip_field", "field_id" to h.alias("Geburtsort")).ok).isTrue()

        assertThat(h.field("Geburtsort").skipped).isTrue()
        assertThat(FormRefs.open(h.fields())).hasSize(open - 1)
        assertThat(h.exec("show_fill_card").toModelText()).contains("\"total\":16")
        assertThat(h.exec("show_on_page", "field_id" to "f2").toModelText()).contains("\"page\":1")
        assertThat(h.exec("show_on_page", "field_id" to "f99").errorMessage).contains("unknown field_id")
    }

    @Test
    fun `ask_user checks the question and the chips it shows`() = runTest {
        val h = AgentHarness()
        suspend fun ask(question: String, chips: List<String>) = h.exec("ask_user", "question" to question, json = mapOf("chips" to kotlinx.serialization.json.JsonArray(chips.map(::JsonPrimitive))))

        assertThat(ask("", emptyList()).errorMessage).contains("empty")
        assertThat(ask("Q?", listOf("a", "a")).errorMessage).contains("different")
        assertThat(ask("Q?", List(7) { "c$it" }).errorMessage).contains("at most 6")
        assertThat(ask("Q?", listOf("")).errorMessage).contains("1 to 60")
        assertThat(ask("Q?", listOf("Ja", "Nein")).ok).isTrue()
        assertThat(h.tools.specFor("doc").tools["ask_user"]!!.endsTurn).isTrue()
        assertThat(h.tools.specFor("doc").tools["finish"]!!.endsTurn).isTrue()
        assertThat(h.tools.specFor("doc").tools.names).containsExactly(
            "read_form", "list_people", "get_person_details", "fill_from_profile", "fill_field", "ask_user", "remember_detail",
            "skip_field", "show_fill_card", "show_on_page", "finish",
        ).inOrder()
    }

    @Test
    fun `a secret role value needs the user to have replied, a role is filled for its person only`() = runTest {
        val h = AgentHarness().also { it.read() }

        // No reply yet: the payer role is not confirmed, so the IBAN stays empty (the name of the account holder is not secret).
        h.exec("fill_from_profile", "person_id" to "p1", "role" to "payer")
        assertThat(h.field("IBAN").value).isNull()
        assertThat(h.field("Kontoinhaber").value).isEqualTo("Mohammad Mustermann")

        h.exec("fill_from_profile", "person_id" to "p1", "role" to "payer", replies = listOf("Ahmad"))
        assertThat(h.field("IBAN").value).isEqualTo("DE89 3704 0044 0532 0130 00")
        assertThat(h.field("Name des Kindes").value).isNull() // the subject has no person yet
    }
}
