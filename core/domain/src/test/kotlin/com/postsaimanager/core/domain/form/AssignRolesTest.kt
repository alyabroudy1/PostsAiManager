package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AssignRolesTest {

    private suspend fun session(script: FormScript) = FakePromptSession().apply {
        open("prefix")
        scorer = script::score
    }

    private val inputs = listOf(
        RoleInput("Name des Kindes", "Angaben zum Kind", "full_name"),
        RoleInput("Geburtsdatum", "Angaben zum Kind", "birth_date"),
        RoleInput("Kontoinhaber", "Angaben zum Kind", "account_holder"),
        RoleInput("Name der Erziehungsberechtigten", "Erziehungsberechtigte", "full_name"),
        RoleInput("Telefon", "Erziehungsberechtigte", "phone"),
    )

    @Test
    fun `each section is scored against the roles and its fields take its role`() = runTest {
        val script = FormScript(sectionRoles = mapOf("Angaben zum Kind" to FormRole.SUBJECT, "Erziehungsberechtigte" to FormRole.GUARDIAN))
        val session = session(script)

        val result = AssignRoles(FormScorer(session)).assign(inputs)

        assertThat(result.sections).containsExactly(
            SectionRole("Angaben zum Kind", FormRole.SUBJECT), SectionRole("Erziehungsberechtigte", FormRole.GUARDIAN),
        ).inOrder()
        assertThat(result.fieldRoles.map { it }.filterIndexed { i, _ -> i != 2 })
            .containsExactly(FormRole.SUBJECT, FormRole.SUBJECT, FormRole.GUARDIAN, FormRole.GUARDIAN).inOrder()
    }

    @Test
    fun `the context of a section question is its heading and its field labels`() = runTest {
        val session = session(FormScript())
        AssignRoles(FormScorer(session)).assign(inputs)
        val first = session.scored.first().first()
        assertThat(first).contains("«Angaben zum Kind»")
        assertThat(first).contains("Name des Kindes; Geburtsdatum; Kontoinhaber")
    }

    @Test
    fun `a field of its own role overrides the section when it scores clearly`() = runTest {
        val script = FormScript(
            sectionRoles = mapOf("Angaben zum Kind" to FormRole.SUBJECT, "Erziehungsberechtigte" to FormRole.GUARDIAN),
            fieldRoles = mapOf("Kontoinhaber" to FormRole.PAYER),
        )
        val result = AssignRoles(FormScorer(session(script))).assign(inputs)
        assertThat(result.fieldRoles[2]).isEqualTo(FormRole.PAYER)
        assertThat(result.fieldRoles[0]).isEqualTo(FormRole.SUBJECT)
    }

    @Test
    fun `only role-bearing fields are scored on their own`() = runTest {
        val session = session(FormScript())
        AssignRoles(FormScorer(session)).assign(inputs)
        val own = session.scored.flatten().filter { it.contains("Does the field «") }
        assertThat(own).hasSize(FormRoles.descriptions.size)
        assertThat(own.all { it.contains("«Kontoinhaber»") }).isTrue()
    }

    @Test
    fun `a field that is not clearly different keeps the section's role`() = runTest {
        val script = FormScript(
            sectionRoles = mapOf("Angaben zum Kind" to FormRole.SUBJECT, "Erziehungsberechtigte" to FormRole.GUARDIAN),
            fieldRoles = mapOf("Kontoinhaber" to FormRole.SUBJECT),
        )
        val result = AssignRoles(FormScorer(session(script))).assign(inputs)
        assertThat(result.fieldRoles[2]).isEqualTo(FormRole.SUBJECT)
    }

    @Test
    fun `a section no role scores above the threshold for has no role`() = runTest {
        val session = FakePromptSession().apply {
            open("prefix")
            scorer = { -2.0 }
        }
        val result = AssignRoles(FormScorer(session)).assign(inputs)
        assertThat(result.sections.map { it.role }).containsExactly(null, null)
        assertThat(result.fieldRoles.all { it == null }).isTrue()
    }

    @Test
    fun `fields without a heading form one group`() = runTest {
        val session = session(FormScript())
        val result = AssignRoles(FormScorer(session)).assign(listOf(RoleInput("Name", null, null), RoleInput("Telefon", null, null)))
        assertThat(result.sections).hasSize(1)
        assertThat(session.scored.flatten()).hasSize(FormRoles.descriptions.size)
    }

    @Test
    fun `the score budgets cap the sections and the role-bearing fields scored`() = runTest {
        val session = session(FormScript())
        val sections = (1..5).map { RoleInput("Feld $it", "Abschnitt $it", null) }
        val banks = (1..4).map { RoleInput("IBAN $it", "Abschnitt 1", "iban") }
        val profile = FormScoringProfile(maxSectionScores = 12, maxFieldRoleScores = 6)

        val result = AssignRoles(FormScorer(session), profile).assign(sections + banks)

        // Two sections (12 scores) and one bank field (6 scores) are scored; the others are left without a role.
        assertThat(session.scored.flatten()).hasSize(18)
        assertThat(result.sections.drop(2).all { it.role == null }).isTrue()
    }

    @Test
    fun `the registry describes every role`() {
        assertThat(FormRoles.descriptions.keys).containsExactlyElementsIn(FormRole.entries)
        assertThat(FormRoles.description(FormRole.GUARDIAN)).contains("parent or legal guardian")
        assertThat(FormRoles.roleBearingKeys).containsAtLeast("iban", "account_holder", "signature")
    }
}
