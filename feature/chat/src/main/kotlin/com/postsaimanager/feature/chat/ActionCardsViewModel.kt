package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.skills.ActionField
import com.postsaimanager.core.domain.skills.ActionForm
import com.postsaimanager.core.domain.skills.ActionResult
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.ConfirmActionUseCase
import com.postsaimanager.core.domain.skills.ConfirmOutcome
import com.postsaimanager.core.domain.skills.FieldCheck
import com.postsaimanager.core.domain.skills.FieldStatus
import com.postsaimanager.core.domain.skills.InvalidReason
import com.postsaimanager.core.domain.skills.ProposeActionUseCase
import com.postsaimanager.core.domain.skills.ProposedAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/** Where a card stands. Open and Cancel are final. */
internal enum class ActionCardStatus { PENDING, OPENED, CANCELLED }

/**
 * One proposed action on a card: the fields as texts (what the user sees and may edit), the grounding verdict of each, and where
 * the card stands. Nothing has run while it is [ActionCardStatus.PENDING].
 */
internal data class ActionCardState(
    val id: String,
    val proposed: ProposedAction,
    val values: Map<ActionField, String>,
    /** The fields the user typed over: theirs, so no longer flagged against the letter. */
    val edited: Set<ActionField> = emptySet(),
    val editing: Boolean = false,
    val errors: Map<ActionField, InvalidReason> = emptyMap(),
    val status: ActionCardStatus = ActionCardStatus.PENDING,
    /** The last Open did not work (no app to open, reminder refused): the card stays open to retry or edit. */
    val openFailed: Boolean = false,
) {
    val action: AgentAction get() = proposed.action

    /** What the card shows next to [field]: the grounding verdict, or "yours" once the user changed it. */
    fun checkOf(field: ActionField): FieldCheck? =
        if (field in edited) FieldCheck.USER_ENTERED else proposed.checks[field]

    /** Whether any field carries a flag the user should read before opening. */
    val hasFlags: Boolean
        get() = values.keys.any { field -> checkOf(field)?.status.let { it == FieldStatus.NOT_FOUND || it == FieldStatus.INVALID } }
}

/**
 * The action cards of a chat. A skill's `run_intent` call becomes a [ProposedAction] (checked against the letter by
 * [ProposeActionUseCase]) and a card here; the user's Open runs it through [ConfirmActionUseCase], Cancel drops it. Nothing runs
 * without Open. The cards are kept while the chat screen lives; they are not stored.
 */
@HiltViewModel
class ActionCardsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val proposeAction: ProposeActionUseCase,
    private val confirmAction: ConfirmActionUseCase,
) : ViewModel() {

    private val documentId: String? = savedStateHandle["documentId"]

    private val _cards = MutableStateFlow<List<ActionCardState>>(emptyList())
    internal val cards: StateFlow<List<ActionCardState>> = _cards.asStateFlow()

    /**
     * An action the model proposed: checks its values and shows the card. [userMessages] are the user's own words in this chat
     * (a value they wrote is theirs). The entry point phase 2b's action channel calls, and the debug menu.
     */
    fun propose(action: AgentAction, userMessages: List<String>, now: LocalDateTime = LocalDateTime.now()) {
        if (!action.requiresConfirmation) return
        viewModelScope.launch {
            val proposed = proposeAction(action, documentId, userMessages, now)
            val card = ActionCardState(id = UUID.randomUUID().toString(), proposed = proposed, values = ActionForm.entries(action).toMap())
            _cards.update { it + card }
        }
    }

    fun startEditing(id: String) = update(id) { if (it.status == ActionCardStatus.PENDING) it.copy(editing = true) else it }

    fun changeField(id: String, field: ActionField, text: String) = update(id) {
        if (it.status != ActionCardStatus.PENDING) {
            it
        } else {
            val changedFromProposal = ActionForm.entries(it.action).toMap()[field] != text
            it.copy(
                values = it.values + (field to text),
                edited = if (changedFromProposal) it.edited + field else it.edited - field,
                errors = it.errors - field,
            )
        }
    }

    /** Open: the fields as they stand go to [ConfirmActionUseCase]; the card turns final on success, or shows what is wrong. */
    fun open(id: String, now: LocalDateTime = LocalDateTime.now()) {
        val card = _cards.value.firstOrNull { it.id == id }?.takeIf { it.status == ActionCardStatus.PENDING } ?: return
        viewModelScope.launch {
            when (val outcome = confirmAction(card.proposed, card.values, now)) {
                is ConfirmOutcome.Invalid -> update(id) { it.copy(errors = outcome.errors, editing = true, openFailed = false) }
                is ConfirmOutcome.Executed -> update(id) {
                    when (outcome.result) {
                        is ActionResult.Succeeded -> it.copy(status = ActionCardStatus.OPENED, editing = false, errors = emptyMap(), openFailed = false)
                        is ActionResult.Failed -> it.copy(openFailed = true, errors = emptyMap())
                    }
                }
            }
        }
    }

    fun cancel(id: String) = update(id) {
        if (it.status == ActionCardStatus.PENDING) it.copy(status = ActionCardStatus.CANCELLED, editing = false, openFailed = false) else it
    }

    private fun update(id: String, change: (ActionCardState) -> ActionCardState) {
        _cards.update { cards -> cards.map { if (it.id == id) change(it) else it } }
    }
}
