package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/*
 * What the form-filling conversation shows in a document's chat besides the model's own words. The conversation is run by a
 * tool-calling agent (see `core/domain/agent`): the agent's tool calls and results are stored as TOOL_CALL / TOOL_RESULT
 * messages with their tool id, name, arguments and result in the message's tool columns, and the chat renders the calls that
 * have something to show (a question with chips, the fill card, a page chip, the closing message) as a [FormMessage]. Lines the
 * assistant says that no model wrote (progress, a pause, an error) are stored as TOOL_RESULT messages with the tool name
 * [FORM_MESSAGE_TOOL] and this payload in the tool arguments. The domain writes CODES, never UI text: the chat renders a
 * [FormText] from string resources in the user's language, with the message's [FormMessage.args].
 */

/** The `toolName` of a stored form status message (a line the conversation says that is not a tool call). */
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

    /** A question or a message the model wrote (the message content), with answer chips. */
    QUESTION,

    /** The fill card: rendered live from the form's fields. */
    CARD,

    /** A chip that opens the page of a field ([FormMessage.fieldId] is the field's short id, see `FormRefs`). */
    PAGE,
}

/** Every line the form conversation says that is not written by the model. */
@Serializable
enum class FormText {
    /** args: step done, steps total, the reading's key. */
    UNDERSTANDING,
    NO_MODEL,
    UNDERSTANDING_FAILED,

    /** The search (embedding) model is not on the device, so reading the form is slower; a chip opens the model download. */
    SEARCH_MODEL_MISSING,

    /** The first line of a new fill: form filling is a beta feature. It also marks where a run of the agent begins. */
    BETA_NOTICE,

    /** The run was stopped (or the chat was left): a chip continues where it stopped. */
    AGENT_PAUSED,

    /** The agent took its step limit without reaching the user: a chip lets it go on. */
    AGENT_STUCK,

    /** The model failed or kept answering with something unusable. */
    AGENT_FAILED,
}

/** The labels of chips that are not data (a name, an option, a value are shown as they are). */
@Serializable
enum class FormChipLabel { CONTINUE, DOWNLOAD }

@Serializable
enum class FormChipAction {
    /** Answer the open question with [FormChip.arg]: it is sent as the user's message. */
    ANSWER,

    /** Let the agent go on from where it stopped. */
    CONTINUE,

    /** Open the model download screen. Handled by the UI; never an answer to a question. */
    OPEN_MODELS,
}

/** One tappable answer. [label] is shown verbatim; when null, [labelCode] is rendered from resources. */
@Serializable
data class FormChip(
    val action: FormChipAction,
    val label: String? = null,
    val labelCode: FormChipLabel? = null,
    val arg: String? = null,
    /** The field an ANSWER chip belongs to, when it answers a question about one field. */
    val fieldId: String? = null,
)

/** The payload of a form message. */
@Serializable
data class FormMessage(
    val kind: FormMessageKind,
    val text: FormText? = null,
    val args: List<String> = emptyList(),
    val chips: List<FormChip> = emptyList(),
    /** The field a QUESTION is about, or the field a PAGE chip opens. */
    val fieldId: String? = null,
    /** The fill a CARD renders. */
    val fillId: String? = null,
)
