package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * One thing the reader has to do, as the second stage of a reading chose it: the id of an action kind (see `ActionKinds` in the domain
 * module) and the stored fields the action rests on. No sentence is stored: the line is rendered when it is shown, in the app's language,
 * from the fields as they are now, so a value a person corrected is the value the line states.
 *
 * @property kind the action kind's id (`pay`, `reply` ...); an id this build does not know is not shown
 * @property bindings the stored field behind each part of the action, by the part's name (`date`, `amount`, `party`, `reference`,
 *   `iban`): the field's slot key (`due_date`, `total`, `sender` ...), the identity a field keeps across re-reads. A part with no entry is
 *   not stated.
 * @property source who wrote the action: the second stage ([ActionSource.MODEL], replaced by every re-read) or a person ([ActionSource.USER]:
 *   an action they added, edited or removed; kept by every re-read, see `ActionItemsPolicy`)
 * @property text the person's own wording; shown instead of the sentence rendered from the kind. Null: the rendered sentence.
 * @property dueDate the person's own date, ISO `yyyy-MM-dd`; shown instead of the date of the bound field. Null: the bound field's.
 * @property origin the kind of the model's action this one replaces (a person changed its kind); a re-read that chooses it again is
 *   not added a second time. Null for a model action and for one the person added.
 * @property removed a tombstone: the person deleted the action of this [kind]; a re-read never brings it back, and it is not shown.
 */
@Serializable
data class ActionItem(
    val kind: String,
    val bindings: Map<String, String> = emptyMap(),
    val source: ActionSource = ActionSource.MODEL,
    val text: String? = null,
    val dueDate: String? = null,
    val origin: String? = null,
    val removed: Boolean = false,
)

/** Who wrote an [ActionItem]. */
@Serializable
enum class ActionSource { MODEL, USER }
