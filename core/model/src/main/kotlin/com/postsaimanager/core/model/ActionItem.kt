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
 */
@Serializable
data class ActionItem(val kind: String, val bindings: Map<String, String> = emptyMap())
