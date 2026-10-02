package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormRole

/**
 * Whose data a part of a form asks for: the one owner of that vocabulary (data, not rules).
 *
 * [descriptions] are English content descriptions the model scores a form section or field against
 * ("Does this part ask for details about <description>?"); they are never shown to users and never matched against
 * printed words, so a form in any language is read through the same descriptions.
 */
object FormRoles {

    val descriptions: Map<FormRole, String> = linkedMapOf(
        FormRole.SUBJECT to "the person the form is about: the participant, applicant, pupil or member",
        FormRole.GUARDIAN to "a parent or legal guardian of that person",
        FormRole.PAYER to "the person who pays or whose bank account is used",
        FormRole.SIGNER to "the person who signs the form",
        FormRole.EMERGENCY_CONTACT to "a person to call in an emergency",
        FormRole.OTHER to "some other person or party the form mentions, such as an organiser, a teacher or a doctor",
    )

    fun description(role: FormRole): String = descriptions.getValue(role)

    /**
     * The data keys whose owner can differ from the person of the section they stand in (a bank account holder or a
     * signature in a child's section): only the fields of these keys are scored for a role of their own, which keeps the
     * role scoring per section.
     */
    val roleBearingKeys: Set<String> = setOf(
        FormDataKeys.ACCOUNT_HOLDER.id,
        FormDataKeys.IBAN.id,
        FormDataKeys.BANK_NAME.id,
        FormDataKeys.SIGNATURE.id,
    )
}
