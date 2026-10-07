package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.skills.ActionCardKey
import com.postsaimanager.core.domain.skills.ActionCardRecord
import com.postsaimanager.core.domain.skills.ActionCardTrace
import com.postsaimanager.core.domain.skills.ActionField
import com.postsaimanager.core.domain.skills.ActionForm
import com.postsaimanager.core.domain.skills.ActionParse
import com.postsaimanager.core.domain.skills.ActionResult
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.AgentActionParser
import com.postsaimanager.core.domain.skills.AgentIntent
import com.postsaimanager.core.domain.skills.ConfirmActionUseCase
import com.postsaimanager.core.domain.skills.ConfirmOutcome
import com.postsaimanager.core.domain.skills.ConversationCards
import com.postsaimanager.core.domain.skills.FieldCheck
import com.postsaimanager.core.domain.skills.FieldStatus
import com.postsaimanager.core.domain.skills.FormResult
import com.postsaimanager.core.domain.skills.InvalidReason
import com.postsaimanager.core.domain.skills.ObserveStoredActionCardsUseCase
import com.postsaimanager.core.domain.skills.ObserveToolActionsUseCase
import com.postsaimanager.core.domain.skills.ProposeActionUseCase
import com.postsaimanager.core.domain.skills.ProposedAction
import com.postsaimanager.core.domain.skills.SaveActionCardStateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/** Where a card stands: Open and Cancel end it, until the user restores (a cancelled card) or repeats (an opened one) it. */
internal typealias ActionCardStatus = com.postsaimanager.core.domain.skills.ActionCardStatus

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
    /** The texts the model proposed: a field that differs from them is the user's own. */
    val original: Map<ActionField, String> = ActionForm.entries(proposed.action).toMap(),
    /** When Open worked, for an opened card. */
    val doneAt: LocalDateTime? = null,
    /** Where the card's state is kept with its reply; null for a card whose reply is not stored yet (or never will be). */
    val storedKey: ActionCardKey? = null,
) {
    val action: AgentAction get() = proposed.action

    /** What the card shows next to [field]: the grounding verdict, or "yours" once the user changed it. */
    fun checkOf(field: ActionField): FieldCheck? =
        if (field in edited) FieldCheck.USER_ENTERED else proposed.checks[field]

    /** Whether any field carries a flag the user should read before opening. */
    val hasFlags: Boolean
        get() = values.keys.any { field -> checkOf(field)?.status.let { it == FieldStatus.NOT_FOUND || it == FieldStatus.INVALID } }

    /** What is kept of the card so that it survives a restart. */
    fun toRecord() = ActionCardRecord(
        status = status,
        values = values.mapKeys { it.key.name },
        original = original.mapKeys { it.key.name },
        documentId = proposed.documentId,
        doneAt = doneAt?.let(ActionCardTrace::timeText),
    )
}

/**
 * The action cards of a chat. A skill's `run_intent` call becomes a [ProposedAction] (checked against the letter by
 * [ProposeActionUseCase]) and a card here; the user's Open runs it through [ConfirmActionUseCase], Cancel ends it. Nothing runs
 * without Open.
 *
 * The state of every card is kept with the reply that proposed it ([SaveActionCardStateUseCase], in the reply's tool trace), and
 * the cards are rebuilt from the stored replies when the chat opens ([ObserveStoredActionCardsUseCase]), so a new process loses
 * none. A cancelled card can be restored (pending again, with its last values) and an opened one repeated (a fresh pending copy);
 * neither asks the model anything, it is the same proposal, and the grounding flags are recomputed.
 *
 * The model's `run_intent` calls reach it through [ObserveToolActionsUseCase] (the action channel from the `:inference` process,
 * rebuilt into an [AgentAction] by the pure parser) and become cards the same way the debug menu's samples do. Such a card has no
 * stored reply yet; when its reply is stored it adopts it (the call of the same intent) and its state is saved from then on.
 */
@HiltViewModel
class ActionCardsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val proposeAction: ProposeActionUseCase,
    private val confirmAction: ConfirmActionUseCase,
    observeToolActions: ObserveToolActionsUseCase,
    observeStoredCards: ObserveStoredActionCardsUseCase,
    private val saveCardState: SaveActionCardStateUseCase,
    private val actionNotes: ActionNotes,
) : ViewModel() {

    private val documentId: String? = savedStateHandle["documentId"]

    private val _cards = MutableStateFlow<List<ActionCardState>>(emptyList())
    internal val cards: StateFlow<List<ActionCardState>> = _cards.asStateFlow()

    /** The user's own words in this chat, kept current by the screen: a value they wrote is theirs, not the model's guess. */
    private var userMessages: List<String> = emptyList()

    /** One change of the cards list at a time: a stored reply adopts a live card only after that card was added. */
    private val lock = Mutex()

    /** The pending save of a card whose fields are being typed: one write after a pause, not one per key. */
    private val pendingSaves = HashMap<String, Job>()

    init {
        viewModelScope.launch {
            observeToolActions().collect { proposal ->
                // The chat's own letter, or (a chat over all letters) the one the model's reply was about.
                propose(proposal.action, userMessages, documentId = proposal.documentId ?: documentId)
            }
        }
        viewModelScope.launch {
            observeStoredCards(ChatViewModel.conversationIdFor(documentId)).collect { adopt(it) }
        }
    }

    fun updateUserMessages(messages: List<String>) {
        userMessages = messages
    }

    /**
     * An action the model proposed: checks its values and shows the card. [userMessages] are the user's own words in this chat
     * (a value they wrote is theirs). [documentId] is the letter the values are checked against; null checks none, and the card
     * flags what it cannot find. The entry point of the action channel, and of the debug menu.
     */
    fun propose(
        action: AgentAction,
        userMessages: List<String>,
        now: LocalDateTime = LocalDateTime.now(),
        documentId: String? = this.documentId,
    ) {
        if (!action.requiresConfirmation) return
        viewModelScope.launch {
            lock.withLock {
                val proposed = proposeAction(action, documentId, userMessages, now)
                val card = ActionCardState(id = UUID.randomUUID().toString(), proposed = proposed, values = ActionForm.entries(action).toMap())
                _cards.update { it + card }
            }
        }
    }

    fun startEditing(id: String) = update(id) { if (it.status == ActionCardStatus.PENDING) it.copy(editing = true) else it }

    fun changeField(id: String, field: ActionField, text: String) {
        update(id) {
            if (it.status != ActionCardStatus.PENDING) {
                it
            } else {
                val changedFromProposal = it.original[field] != text
                it.copy(
                    values = it.values + (field to text),
                    edited = if (changedFromProposal) it.edited + field else it.edited - field,
                    errors = it.errors - field,
                )
            }
        }
        persistSoon(id)
    }

    /** Open: the fields as they stand go to [ConfirmActionUseCase]; the card turns final on success, or shows what is wrong. */
    fun open(id: String, now: LocalDateTime = LocalDateTime.now()) {
        val card = _cards.value.firstOrNull { it.id == id }?.takeIf { it.status == ActionCardStatus.PENDING } ?: return
        viewModelScope.launch {
            when (val outcome = confirmAction(card.proposed, card.values, now)) {
                is ConfirmOutcome.Invalid -> {
                    update(id) { it.copy(errors = outcome.errors, editing = true, openFailed = false) }
                    persist(id)
                }
                is ConfirmOutcome.Executed -> {
                    update(id) {
                        when (outcome.result) {
                            is ActionResult.Succeeded ->
                                it.copy(status = ActionCardStatus.OPENED, editing = false, errors = emptyMap(), openFailed = false, doneAt = now)
                            is ActionResult.Failed -> it.copy(openFailed = true, errors = emptyMap())
                        }
                    }
                    persist(id)
                    if (outcome.result is ActionResult.Succeeded) syncNote(id, now)
                }
            }
        }
    }

    fun cancel(id: String) {
        update(id) {
            if (it.status == ActionCardStatus.PENDING) it.copy(status = ActionCardStatus.CANCELLED, editing = false, openFailed = false) else it
        }
        persist(id)
        syncNote(id)
    }

    /** Restore: a cancelled card is pending again with its last values, flags recomputed; Open, Edit and Cancel work again. */
    fun restore(id: String, now: LocalDateTime = LocalDateTime.now()) {
        val card = _cards.value.firstOrNull { it.id == id }?.takeIf { it.status == ActionCardStatus.CANCELLED } ?: return
        viewModelScope.launch {
            val proposed = grounded(card.proposed.action, card.values, card.proposed.documentId, userMessages, now)
            update(id) {
                it.copy(
                    status = ActionCardStatus.PENDING, proposed = proposed, editing = false, errors = emptyMap(), openFailed = false, doneAt = null,
                )
            }
            persist(id)
            syncNote(id, now)
        }
    }

    /** Do again: an opened card stays as it is and a fresh pending copy with the same values appears, flags recomputed. */
    fun doAgain(id: String, now: LocalDateTime = LocalDateTime.now()) {
        val card = _cards.value.firstOrNull { it.id == id }?.takeIf { it.status == ActionCardStatus.OPENED } ?: return
        viewModelScope.launch {
            val proposed = grounded(card.proposed.action, card.values, card.proposed.documentId, userMessages, now)
            val copy = card.copy(
                id = UUID.randomUUID().toString(),
                proposed = proposed,
                status = ActionCardStatus.PENDING,
                editing = false,
                errors = emptyMap(),
                openFailed = false,
                doneAt = null,
                storedKey = card.storedKey?.let { key -> key.copy(copy = nextCopy(key)) },
            )
            _cards.update { cards ->
                val at = cards.indexOfFirst { it.id == id }
                if (at < 0) cards + copy else cards.take(at + 1) + copy + cards.drop(at + 1)
            }
            persist(copy.id)
        }
    }

    private fun nextCopy(key: ActionCardKey): Int =
        1 + (_cards.value.mapNotNull { it.storedKey }.filter { it.messageId == key.messageId && it.exchangeIndex == key.exchangeIndex }.maxOfOrNull { it.copy } ?: key.copy)

    /**
     * The cards of the stored replies: a reply's call that no card shows yet becomes one (the live card of the same intent that
     * waited for its reply, or a card rebuilt from the stored state, flags recomputed); a card whose reply is gone goes too.
     */
    private suspend fun adopt(stored: ConversationCards) = lock.withLock {
        val messageIds = stored.cards.map { it.key.messageId }.toSet()
        _cards.update { cards -> cards.filter { card -> card.storedKey?.let { it.messageId in messageIds } ?: true } }
        for (call in stored.cards) {
            if (_cards.value.any { it.storedKey == call.key }) continue
            val now = LocalDateTime.now()
            val live = if (call.record == null) {
                _cards.value.firstOrNull { it.storedKey == null && AgentIntent.of(it.action).wire == call.intent.trim() }
            } else {
                null
            }
            if (live != null) {
                _cards.update { cards -> cards.map { if (it.id == live.id) it.copy(storedKey = call.key) else it } }
                persist(live.id)
                continue
            }
            val template = (AgentActionParser.parse(call.intent, call.parametersJson, documentId, now) as? ActionParse.Parsed)?.action
                ?.takeIf { it.requiresConfirmation } ?: continue
            val record = call.record
            val original = record?.original?.toFields()?.takeIf { it.isNotEmpty() } ?: ActionForm.entries(template).toMap()
            val values = record?.values?.toFields()?.takeIf { it.isNotEmpty() } ?: original
            val proposed = grounded(template, values, record?.documentId ?: documentId, stored.userMessages, now)
            val card = ActionCardState(
                id = "${call.key.messageId}:${call.key.exchangeIndex}:${call.key.copy}",
                proposed = proposed,
                values = values,
                edited = values.filter { (field, text) -> original[field] != text }.keys,
                status = record?.status ?: ActionCardStatus.PENDING,
                original = original,
                doneAt = ActionCardTrace.timeOf(record?.doneAt),
                storedKey = call.key,
            )
            _cards.update { it + card }
            if (record == null) persist(card.id)
        }
    }

    /** The proposal for [values] of [template], checked again against the letter and the clock (a time that has passed is flagged). */
    private suspend fun grounded(
        template: AgentAction,
        values: Map<ActionField, String>,
        documentId: String?,
        userMessages: List<String>,
        now: LocalDateTime,
    ): ProposedAction {
        val action = (ActionForm.build(template, values, now, requireFuture = false) as? FormResult.Built)?.action ?: template
        return proposeAction(action, documentId, userMessages, now)
    }

    private fun Map<String, String>.toFields(): Map<ActionField, String> =
        entries.mapNotNull { (name, text) -> ActionField.entries.firstOrNull { it.name == name }?.let { it to text } }.toMap()

    private fun update(id: String, change: (ActionCardState) -> ActionCardState) {
        _cards.update { cards -> cards.map { if (it.id == id) change(it) else it } }
    }

    /**
     * Keeps the document memory in step with the card ([ActionNotes], no AI): an opened card has its note (the values as the user
     * confirmed them), a cancelled or restored one has none. Called where the state changes, so a card is one note however often.
     */
    private fun syncNote(id: String, now: LocalDateTime = LocalDateTime.now()) {
        val card = _cards.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val action = (ActionForm.build(card.proposed.action, card.values, now, requireFuture = false) as? FormResult.Built)?.action
                ?: card.proposed.action
            actionNotes.sync(card.proposed.documentId ?: documentId, card.id, action, card.status, card.doneAt ?: now)
        }
    }

    /** Saves the card's state with its reply now (a card without a stored reply has nothing to save to). */
    private fun persist(id: String) {
        pendingSaves.remove(id)?.cancel()
        val card = _cards.value.firstOrNull { it.id == id } ?: return
        val key = card.storedKey ?: return
        viewModelScope.launch { saveCardState(key, card.toRecord()) }
    }

    private fun persistSoon(id: String) {
        pendingSaves.remove(id)?.cancel()
        pendingSaves[id] = viewModelScope.launch {
            delay(EDIT_SAVE_DELAY_MS)
            pendingSaves.remove(id)
            persist(id)
        }
    }

    private companion object {
        const val EDIT_SAVE_DELAY_MS = 600L
    }
}
