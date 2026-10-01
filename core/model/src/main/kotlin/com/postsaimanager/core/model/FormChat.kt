package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/*
 * The messages the form-filling conversation adds to a document's chat. They are stored as ordinary messages (role
 * TOOL_RESULT, tool name [FORM_MESSAGE_TOOL], this payload in the tool arguments), so the conversation survives a restart and
 * the model never sees them as history. The domain writes CODES, never UI text: the chat renders a [FormText] from string
 * resources in the user's language, with the message's [FormMessage.args].
 */

/** The `toolName` of a stored form message. */
const val FORM_MESSAGE_TOOL = "form"

/** The stored value of a tick box that has no printed options: the user ticked it ([YES]) or not ([NO]). Shown from resources. */
object CheckboxValue {
    const val YES = "yes"
    const val NO = "no"
}

@Serializable
enum class FormMessageKind {
    /** A line about what the assistant is doing or has done; [FormMessage.text] says which. */
    STATUS,

    /** A question with answer chips. When the model wrote it, it is the message content; otherwise [FormMessage.text] is a template. */
    QUESTION,

    /** The fill card: rendered live from the form's fields; [FormMessage.text] is the line above it. */
    CARD,
}

/** Every line the form conversation says that is not written by the model. */
@Serializable
enum class FormText {
    /** args: step done, steps total. */
    UNDERSTANDING,
    NO_MODEL,
    UNDERSTANDING_FAILED,

    /** args: fields, pages. */
    FORM_FOUND_ASK_SUBJECT,

    /** args: fields, pages, the form line, the person it fits. */
    FORM_FOUND_ASK_SUBJECT_REASON,
    ASK_SUBJECT,

    /** args: none. The guardian could not be settled from the profiles. */
    ASK_GUARDIAN,
    ASK_PAYER,

    /** args: ready, total. The line above the first fill card. */
    FILLED_INTRO,

    /** Template questions when the model could not write one. args: the field's label. */
    ASK_TEXT,
    ASK_DATE,
    ASK_CHOICE,
    ASK_YES_NO,

    /** args: the label, the stored value. */
    STILL_RIGHT,

    /** The answer was refused; asked again with a hint. args: the label. */
    HINT_NOT_AN_OPTION,
    HINT_NOT_A_DATE,
    HINT_NOT_A_PHONE,
    HINT_NOT_AN_EMAIL,
    HINT_NOT_AN_IBAN,
    HINT_NOT_A_POSTCODE,
    HINT_EMPTY,

    /** Refused twice: the field is left for the user. args: the label. */
    LEFT_FOR_YOU,

    /** args: the person's name. */
    REMEMBER,
    REMEMBERED,
    REMEMBER_FAILED,

    /** args: how many questions are left. */
    MORE_QUESTIONS,

    /** The rest was left to the user. args: how many fields. */
    BY_HAND,

    /** args: ready, total, signatures, first signature page or empty. */
    ALL_SET,
    STOPPED,
    SUBJECT_CHANGED,

    /** The name of the "someone else" behind a role is asked (typed in the chat). */
    ASK_ROLE_NAME,

    /** The user has no profile of their own yet: where to make one. */
    ME_SETUP_HINT,

    /** Reading the form was stopped; a chip continues it from the last finished step. */
    READING_PAUSED,
}

/** The labels of chips that are not data (a name, an option, a value are shown as they are). */
@Serializable
enum class FormChipLabel { YES, NO, SKIP, CONTINUE, BY_HAND, SOMEONE_ELSE, ME_SETUP, CONTINUE_READING }

@Serializable
enum class FormChipAction {
    /** Answer the open question with [FormChip.arg]. */
    ANSWER,

    /** Choose the person [FormChip.arg] (blank: someone else) for the open subject or role question. */
    PERSON,
    REMEMBER_YES,
    REMEMBER_NO,
    SKIP,
    CONTINUE,
    BY_HAND,

    /** Continue reading a form whose reading was stopped. */
    CONTINUE_READING,
}

/** One tappable answer. [label] is shown verbatim; when null, [labelCode] is rendered from resources. */
@Serializable
data class FormChip(
    val action: FormChipAction,
    val label: String? = null,
    val labelCode: FormChipLabel? = null,
    val arg: String? = null,
    /** The field an ANSWER or SKIP chip belongs to: a chip of an earlier question no longer applies. */
    val fieldId: String? = null,
)

/** The payload of a stored form message. */
@Serializable
data class FormMessage(
    val kind: FormMessageKind,
    val text: FormText? = null,
    val args: List<String> = emptyList(),
    val chips: List<FormChip> = emptyList(),
    /** The field a QUESTION is about. */
    val fieldId: String? = null,
    /** The fill a CARD renders. */
    val fillId: String? = null,
)
