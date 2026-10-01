package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/*
 * Form assist: the shared model of filling a form for a person (see plans/FORM-ASSIST.md).
 * The AI decides what a blank asks for and whose data it needs; every value comes from a profile, a saved fact,
 * the user's answer or an option printed on the form, never from the model.
 */

/** How a person relates to the user ("Me"); a child's guardians are the user and the user's partner. */
@Serializable
enum class Relationship { PARTNER, CHILD, PARENT, RELATIVE, FRIEND, OTHER }

/** A remembered detail of a person, keyed by a [com.postsaimanager.core.model.FormDataKey] id. */
@Serializable
data class ProfileFact(
    val id: String,
    val profileId: String,
    val key: String,
    val value: String,
    val source: FactSource,
    val sourceDocumentId: String? = null,
    val sensitive: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
enum class FactSource { USER, FORM_ANSWER, CONFIRMED_DOC }

/** What kind of blank a form field is. */
@Serializable
enum class FormFieldKind { TEXT, DATE, CHECKBOX, CHOICE, SIGNATURE, TABLE_CELL }

/** Whose data a field needs. The person behind each role is chosen per fill. */
@Serializable
enum class FormRole { SUBJECT, GUARDIAN, PAYER, SIGNER, EMERGENCY_CONTACT, OTHER }

/** Where a field's value came from. */
@Serializable
enum class FormValueSource { PROFILE, FACT, USER, FORM_OPTION, TODAY, NONE }

/** Where a fill conversation stands. */
@Serializable
enum class FormFillStatus { UNDERSTANDING, ASK_SUBJECT, ASK_ROLE, ASKING, DONE, STOPPED }

/** A normalised box on a page (0..1 of the page's width and height). */
@Serializable
data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One blank of a form, as understood and (possibly) filled. */
@Serializable
data class FormField(
    val id: String,
    val formFillId: String,
    val documentId: String,
    /** 1-based page number. */
    val page: Int,
    /** The printed label, as OCR read it. */
    val labelText: String,
    val labelBox: NormBox?,
    /** Where the value goes on the page. */
    val fillBox: NormBox?,
    val kind: FormFieldKind,
    /** The nearest heading above the field, as printed, if any. */
    val section: String? = null,
    /** Printed options of a CHOICE/CHECKBOX group, quote-verified against the OCR. */
    val options: List<String> = emptyList(),
    /** A [FormDataKey] id, or null when the AI could not tell what the field asks for. */
    val dataKey: String? = null,
    val role: FormRole? = null,
    /** 0..1 confidence in [dataKey]/[role]. */
    val confidence: Float = 0f,
    val value: String? = null,
    val valueSource: FormValueSource = FormValueSource.NONE,
    val profileId: String? = null,
    val reviewState: ReviewState = ReviewState.UNREVIEWED,
    val required: Boolean = false,
    /** Text OCR found inside [fillBox]: the field was already filled by hand. */
    val alreadyFilled: String? = null,
    /** [value] came from a stored detail old enough to be asked about again ("still right?"). */
    val reconfirm: Boolean = false,
    /** The user skipped the question: the field is left for the user to write by hand. */
    val skipped: Boolean = false,
    val orderIndex: Int = 0,
    val updatedAt: Long = 0L,
)

/** One fill of a form for a person: the conversation's state. */
@Serializable
data class FormFill(
    val id: String,
    val documentId: String,
    val status: FormFillStatus,
    /** The person chosen for each role, e.g. SUBJECT → Ahmad, GUARDIAN → the user. */
    val roleProfiles: Map<FormRole, String> = emptyMap(),
    val conversationId: String? = null,
    val currentFieldId: String? = null,
    /** The roles the user confirmed (the subject in "Who is this form for?", a chosen guardian): sensitive values need one. */
    val confirmedRoles: Set<FormRole> = emptySet(),
    /** The form's language tag (what understanding detected), so values are written the same way after a restart. */
    val localeTag: String? = null,
    /** What the last message of the conversation waits for; null when nothing does (DONE, STOPPED, UNDERSTANDING). */
    val awaiting: FormAwaiting? = null,
    /** How many questions were asked in the current round (at most five, then the user decides to continue). */
    val roundAsked: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** What a fill conversation waits for. [fieldId], [role] and [value] say which field, which role and which verified value. */
@Serializable
data class FormAwaiting(
    val kind: FormAwaitKind,
    val fieldId: String? = null,
    val role: FormRole? = null,
    val value: String? = null,
)

@Serializable
enum class FormAwaitKind {
    /** "Who is this form for?" */
    SUBJECT,

    /** "Who is the guardian / payer?" ([FormAwaiting.role]). */
    ROLE,

    /** The answer to the question about [FormAwaiting.fieldId]. */
    ANSWER,

    /** "Remember [FormAwaiting.value] for the person?" for [FormAwaiting.fieldId]. */
    REMEMBER,

    /** "N more questions: continue or do the rest by hand?" */
    CONTINUE,

    /** Reading the form was stopped (Stop, or the chat was left): it continues only when the user says so. */
    READING,
}

/** The kind of value a data key holds; drives verification and formatting. */
@Serializable
enum class FormValueKind { TEXT, NAME, DATE, PHONE, EMAIL, IBAN, ADDRESS, POSTCODE, BOOLEAN, NUMBER }

/**
 * One kind of personal detail a form can ask for (data, see `FormDataKeys` in core/domain).
 * [description] is an English content description for the model and the embedding ranker, never shown to users.
 * [profileColumn] names the [Profile] property that holds it, or null when it is a [ProfileFact].
 */
@Serializable
data class FormDataKey(
    val id: String,
    val valueKind: FormValueKind,
    val description: String,
    val sensitive: Boolean = false,
    /** After how many months a remembered value should be re-confirmed; null = stable (e.g. a birth date). */
    val reconfirmAfterMonths: Int? = null,
    val profileColumn: String? = null,
)
