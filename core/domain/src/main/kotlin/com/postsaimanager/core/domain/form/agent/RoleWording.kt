package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.Profile

/**
 * What a role is called and who can have it, for the guidance and the question guards (one owner of the role words).
 *
 * The name of a role is the form's own wording when it has some: the heading of the section the role's fields sit in when every field
 * of that section has the role ("Erziehungsberechtigte/r"), else the label of the role's first field ("Kontoinhaber"). Only a role the
 * form gives no text for gets a name from [FormWording] (string resources in the form's language). The English enum word is never used.
 */
class RoleWording(private val env: FormToolEnv) {

    /** The name of [role] in the form's words. */
    suspend fun name(role: FormRole): String = nameIn(env.fields(), role)

    suspend fun nameIn(fields: List<FormField>, role: FormRole): String {
        val own = fields.filter { it.role == role }.let(FormRefs::ordered)
        val first = own.firstOrNull()
        val section = first?.section?.takeIf { it.isNotBlank() }
        val wholeSection = section != null && fields.filter { it.section == section }.all { it.role == role }
        val label = first?.labelText?.trim().orEmpty()
        return when {
            // A long heading is often a sentence fragment, not a person: the role field's own label ("Kontoinhaber/in") names the role.
            wholeSection && label.isNotEmpty() && wordCount(section!!) > MAX_HEADING_WORDS -> label
            // The heading says what the part is about, the first label names the person ("Zahlung per Lastschrift (Kontoinhaber)").
            wholeSection && label.isNotEmpty() && FormRefs.flat(label) != FormRefs.flat(section!!) -> "${section!!.trim()} ($label)"
            wholeSection -> section!!.trim()
            label.isNotEmpty() -> label
            else -> env.wording.roleName(role, env.formLanguage())
        }
    }

    /** Every text the form uses for [role] (its name, its first field's label and section), flattened: what a question about it may contain. */
    suspend fun phrases(fields: List<FormField>, role: FormRole): List<String> {
        val first = fields.filter { it.role == role }.let(FormRefs::ordered).firstOrNull()
        return listOfNotNull(nameIn(fields, role), first?.labelText, first?.section).map(FormRefs::flat).filter { it.isNotEmpty() }.distinct()
    }

    /** The chip for somebody who is not stored, in the form's language. */
    suspend fun someoneElse(): String = env.wording.someoneElse(env.formLanguage())

    /** Whether [person] is a minor (younger than 18) on the day of the fill; false when the birth date is unknown. */
    fun isMinor(person: Profile): Boolean = FormRefs.ageOf(person, env.today())?.let { it < ADULT_AGE } ?: false

    /**
     * The people who can be asked about for [role] once [subject] is known: for a guardian, payer or signer never a minor subject (and for a
     * guardian never the subject at all). The subject role itself can be anybody.
     */
    fun candidates(role: FormRole, subject: Profile?, people: List<Profile>): List<Profile> {
        if (subject == null || role !in PRESENTED_BY_ADULT) return people
        val excluded = role == FormRole.GUARDIAN || isMinor(subject)
        return if (excluded) people.filter { it.id != subject.id } else people
    }

    private fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    companion object {
        /** A section heading longer than this many words is not used as the name of a role while the role's field has a label. */
        const val MAX_HEADING_WORDS = 4

        const val ADULT_AGE = 18

        /** The roles of a person who acts for the subject (the form's subject can be a child, who cannot hold them). */
        val PRESENTED_BY_ADULT = setOf(FormRole.GUARDIAN, FormRole.PAYER, FormRole.SIGNER)
    }
}
