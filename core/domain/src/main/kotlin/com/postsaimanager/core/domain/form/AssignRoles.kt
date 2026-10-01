package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormRole

/** What [AssignRoles] reads of a field. */
data class RoleInput(val label: String, val section: String?, val dataKey: String?)

/** The role a form section asks for (null when no role scored above the threshold). */
data class SectionRole(val section: String?, val role: FormRole?)

/** [sections] in first-appearance order, and one role per input (the section's, or the field's own when it clearly differs). */
data class RoleAssignment(val sections: List<SectionRole>, val fieldRoles: List<FormRole?>)

/**
 * Decides whose data each part of a form asks for, scored per SECTION so it stays cheap: for each section the model scores
 * the [FormRoles] descriptions with the heading and the field labels as context ("Does the part «Angaben zum Kind» with the
 * fields «…» ask for details about <role>?"), and the section's role goes to its fields.
 *
 * A field may take a role of its own: only fields whose key can belong to someone else than the section's person
 * ([FormRoles.roleBearingKeys]: the account holder, the IBAN, the signature) are scored on their own label, and the field's
 * role replaces the section's when it beats it by [FormScoringProfile.roleOverrideMargin] (a "Kontoinhaber" in a child's
 * section becomes PAYER).
 */
class AssignRoles(
    private val scorer: FormScorer,
    private val profile: FormScoringProfile = FormScoringProfile(),
) {

    private val roles = FormRoles.descriptions.keys.toList()

    /** Throws [FormScoringException] when the engine fails. */
    suspend fun assign(inputs: List<RoleInput>): RoleAssignment {
        val groups = inputs.indices.groupBy { inputs[it].section }
        val sectionRole = scoreSections(inputs, groups)

        val spent = sectionRole.size * roles.size
        val bearing = inputs.indices.filter { inputs[it].dataKey in FormRoles.roleBearingKeys }
            .take(((profile.maxRoleScores - spent) / roles.size).coerceAtLeast(0))
        val ownScores = scorer.yesNo(bearing.flatMap { fieldQuestions(inputs[it].label) })

        val fieldRoles = inputs.map { sectionRole[it.section]?.first }.toMutableList()
        bearing.forEachIndexed { n, i ->
            val row = ownScores.subList(n * roles.size, (n + 1) * roles.size)
            val best = row.indices.maxByOrNull { row[it] }!!
            if (row[best] <= profile.roleThreshold) return@forEachIndexed
            val current = fieldRoles[i]
            val beats = current == null || (roles[best] != current && row[best] - row[roles.indexOf(current)] >= profile.roleOverrideMargin)
            if (beats) fieldRoles[i] = roles[best]
        }
        return RoleAssignment(groups.keys.map { SectionRole(it, sectionRole[it]?.first) }, fieldRoles)
    }

    /** The role (or null) and its score of every section that fits the budget. */
    private suspend fun scoreSections(inputs: List<RoleInput>, groups: Map<String?, List<Int>>): Map<String?, Pair<FormRole?, Double>> {
        val scored = groups.keys.take((profile.maxRoleScores / roles.size).coerceAtLeast(0))
        val scores = scorer.yesNo(scored.flatMap { s -> sectionQuestions(s, groups.getValue(s).map { inputs[it].label }) })
        return scored.mapIndexed { i, s ->
            val row = scores.subList(i * roles.size, (i + 1) * roles.size)
            val best = row.indices.maxByOrNull { row[it] }!!
            s to ((if (row[best] > profile.roleThreshold) roles[best] else null) to row[best])
        }.toMap()
    }

    private fun sectionQuestions(section: String?, labels: List<String>): List<String> {
        val fields = labels.take(MAX_LABELS).joinToString("; ") { it.take(MAX_LABEL_CHARS) }
        val part = if (section == null) "the part of the form with the fields «$fields»" else "the part of the form «$section» with the fields «$fields»"
        return roles.map { "Does $part ask for details about ${FormRoles.description(it)}? Answer:" }
    }

    private fun fieldQuestions(label: String): List<String> =
        roles.map { "Does the field «$label» ask for details about ${FormRoles.description(it)}? Answer:" }

    private companion object {
        const val MAX_LABELS = 8
        const val MAX_LABEL_CHARS = 40
    }
}
