package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.ExternalFlowToken
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.letterContactsFor
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.ProfileFact
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileFactRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ProposeAndConfirmActionTest {

    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)
    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val facts = FakeProfileFactRepository()

    private val contacts = FakeContactRepository()

    private val propose = ProposeActionUseCase(LoadGroundingSourcesUseCase(documents, profiles, facts, letterContactsFor(profiles, contacts)))

    private class RecordingExecutor(var result: ActionResult = ActionResult.Succeeded()) : AgentActionExecutor {
        val executed = mutableListOf<AgentAction>()

        override suspend fun execute(action: AgentAction): ActionResult {
            executed += action
            return result
        }
    }

    private class RecordingGuard : ExternalFlowGuard {
        val expected = mutableListOf<String>()
        var finished = 0

        override fun expect(reason: String): ExternalFlowToken {
            expected += reason
            return ExternalFlowToken(expected.size.toLong(), reason)
        }

        override fun finish(token: ExternalFlowToken?) {
            finished++
        }
    }

    private val executor = RecordingExecutor()
    private val guard = RecordingGuard()
    private val confirm = ConfirmActionUseCase(executor, guard)

    private fun seedLetter() {
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = "Kontakt: info@amt.de\nAktenzeichen AZ-1/2026\nFrist 05.11.2026"))
        documents.seedExtracted("d1", ExtractedData("e1", "d1", "Sender e-mail", "verified@amt.de", ExtractedFieldType.TEXT, 0.9f))
    }

    @Test
    fun `proposing checks the values against the letter, its verified fields and the profile, and runs nothing`() = runTest {
        seedLetter()
        profiles.seed(testProfile(id = "me", email = "me@example.org"))
        facts.seed(ProfileFact("f1", "me", "phone", "me2@example.org", FactSource.USER, sensitive = false, createdAt = 0, updatedAt = 0))

        val letter = propose(AgentAction.SendEmail("info@amt.de", "Re: AZ-1/2026", "Danke"), "d1", emptyList(), now)
        val verified = propose(AgentAction.SendEmail("verified@amt.de", "x", "y"), "d1", emptyList(), now)
        val profile = propose(AgentAction.SendEmail("me@example.org", "x", "y"), "d1", emptyList(), now)
        val fact = propose(AgentAction.SendEmail("me2@example.org", "x", "y"), "d1", emptyList(), now)
        val unknown = propose(AgentAction.SendEmail("who@else.de", "x", "y"), "d1", emptyList(), now)

        assertThat(letter.checks[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(letter.checks[ActionField.SUBJECT]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(verified.checks[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(profile.checks[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(fact.checks[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(unknown.checks[ActionField.TO]).isEqualTo(FieldCheck.NOT_FOUND)
        assertThat(executor.executed).isEmpty()
    }

    @Test
    fun `the current contact's e-mail is offered as a recipient when the letter has none, and a stranger's is still not found`() = runTest {
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = "Ihr Schreiben vom 01.10.2026"))
        profiles.seed(testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt", type = ProfileType.AUTHORITY))
        profiles.linkProfileToDocument("jc", "d1", ProfileRole.SENDER)
        contacts.seed(
            ContactPerson("c1", "jc", "Nadine Beispiel", email = "nadine.beispiel@jobcenter-musterstadt.example", firstSeen = 1, lastSeen = 10),
            ContactPerson("c2", "jc", "Frau Müller", email = "mueller@jobcenter-musterstadt.example", firstSeen = 20, lastSeen = 30),
        )
        contacts.linkContactToDocument("c1", "d1")

        val letterContact = propose(AgentAction.SendEmail("nadine.beispiel@jobcenter-musterstadt.example", "x", "y"), "d1", emptyList(), now)
        val currentContact = propose(AgentAction.SendEmail("mueller@jobcenter-musterstadt.example", "x", "y"), "d1", emptyList(), now)
        val stranger = propose(AgentAction.SendEmail("someone@else.example", "x", "y"), "d1", emptyList(), now)

        assertThat(letterContact.checks[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(currentContact.checks[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(stranger.checks[ActionField.TO]).isEqualTo(FieldCheck.NOT_FOUND)
        // The values are only offered: the proposal still holds exactly what the model chose.
        assertThat(currentContact.action).isEqualTo(AgentAction.SendEmail("mueller@jobcenter-musterstadt.example", "x", "y"))
    }

    @Test
    fun `a secret profile detail is not a source`() = runTest {
        profiles.seed(testProfile(id = "me"))
        facts.seed(ProfileFact("f1", "me", "iban", "secret@bank.de", FactSource.USER, sensitive = true, createdAt = 0, updatedAt = 0))

        val proposed = propose(AgentAction.SendEmail("secret@bank.de", "x", "y"), null, emptyList(), now)

        assertThat(proposed.checks[ActionField.TO]).isEqualTo(FieldCheck.NOT_FOUND)
    }

    @Test
    fun `a value is never changed by proposing, only flagged`() = runTest {
        val action = AgentAction.SendEmail("who@else.de", "AZ-0/0000", "99,99 EUR")

        val proposed = propose(action, "missing-document", listOf("hello"), now)

        assertThat(proposed.action).isEqualTo(action)
        assertThat(proposed.checks[ActionField.BODY]!!.status).isEqualTo(FieldStatus.NOT_FOUND)
    }

    @Test
    fun `the user's own words ground a value`() = runTest {
        val proposed = propose(AgentAction.SendEmail("boss@firma.de", "x", "y"), null, listOf("mail boss@firma.de"), now)

        assertThat(proposed.checks[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
    }

    // ── confirm ──

    private fun proposed(action: AgentAction) = ProposedAction(action, null, emptyMap())

    @Test
    fun `nothing executes until confirm is called`() = runTest {
        propose(AgentAction.SendEmail("a@b.de", "x", "y"), null, emptyList(), now)

        assertThat(executor.executed).isEmpty()
        assertThat(guard.expected).isEmpty()
    }

    @Test
    fun `confirming runs the action once through the executor`() = runTest {
        val email = AgentAction.SendEmail("a@b.de", "x", "y")

        val outcome = confirm(proposed(email), emptyMap(), now)

        assertThat(outcome).isEqualTo(ConfirmOutcome.Executed(ActionResult.Succeeded()))
        assertThat(executor.executed).containsExactly(email)
    }

    @Test
    fun `confirming runs what the user edited, not what the model proposed`() = runTest {
        val outcome = confirm(proposed(AgentAction.SendEmail("a@b.de", "x", "y")), mapOf(ActionField.TO to "other@b.de", ActionField.BODY to "my text"), now)

        assertThat(outcome).isInstanceOf(ConfirmOutcome.Executed::class.java)
        assertThat(executor.executed).containsExactly(AgentAction.SendEmail("other@b.de", "x", "my text"))
    }

    @Test
    fun `fields that cannot make an action stop it before the executor`() = runTest {
        val outcome = confirm(proposed(AgentAction.SendEmail("a@b.de", "x", "y")), mapOf(ActionField.TO to "nope"), now)

        assertThat(outcome).isEqualTo(ConfirmOutcome.Invalid(mapOf(ActionField.TO to InvalidReason.NOT_AN_EMAIL)))
        assertThat(executor.executed).isEmpty()
        assertThat(guard.expected).isEmpty()
    }

    @Test
    fun `a reminder that has become past is stopped`() = runTest {
        val outcome = confirm(proposed(AgentAction.ScheduleReminder(now.minusHours(1), "Pay", null)), emptyMap(), now)

        assertThat(outcome).isEqualTo(ConfirmOutcome.Invalid(mapOf(ActionField.AT to InvalidReason.IN_THE_PAST)))
        assertThat(executor.executed).isEmpty()
    }

    @Test
    fun `opening another app is announced to the app lock, a reminder is not`() = runTest {
        confirm(proposed(AgentAction.SendEmail("a@b.de", "x", "y")), emptyMap(), now)
        assertThat(guard.expected).containsExactly("agent-action")

        confirm(proposed(AgentAction.ScheduleReminder(now.plusDays(1), "Pay", null)), emptyMap(), now)
        assertThat(guard.expected).hasSize(1)
    }

    @Test
    fun `when nothing could be opened the app lock flow ends again and the failure is passed on`() = runTest {
        executor.result = ActionResult.Failed("No app")

        val outcome = confirm(proposed(AgentAction.SendEmail("a@b.de", "x", "y")), emptyMap(), now)

        assertThat(outcome).isEqualTo(ConfirmOutcome.Executed(ActionResult.Failed("No app")))
        assertThat(guard.finished).isEqualTo(1)
    }
}
