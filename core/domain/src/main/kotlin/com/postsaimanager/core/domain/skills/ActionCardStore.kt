package com.postsaimanager.core.domain.skills

import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime
import javax.inject.Inject

/** Where an action card stands. Open and Cancel end it, until the user restores it ([ActionCardRecord]). */
enum class ActionCardStatus { PENDING, OPENED, CANCELLED }

/**
 * What the app keeps of one action card so that it survives a restart: where it stands and the texts of its fields as they were
 * left (edits included). Texts are keyed by [ActionField] names. Stored beside the `run_intent` call that proposed the card
 * ([ActionCardTrace]), never sent to the model.
 *
 * @param original the texts the model proposed: a field whose text differs from it is one the user typed over.
 * @param documentId the letter the values are checked against when the card is rebuilt.
 * @param doneAt when Open worked (an ISO local date and time), for an opened card.
 */
@Serializable
data class ActionCardRecord(
    val status: ActionCardStatus = ActionCardStatus.PENDING,
    val values: Map<String, String> = emptyMap(),
    val original: Map<String, String> = emptyMap(),
    val documentId: String? = null,
    val doneAt: String? = null,
)

/** One card of one stored reply: the reply, which of its tool calls, and which copy of that call's card ("Do again" makes copies). */
data class ActionCardKey(val conversationId: String, val messageId: String, val exchangeIndex: Int, val copy: Int = 0)

/** A card as the conversation holds it: the `run_intent` call that proposed it and, once it was touched, its [record]. */
data class StoredActionCard(
    val key: ActionCardKey,
    val intent: String,
    val parametersJson: String,
    val record: ActionCardRecord?,
)

/**
 * Reads and writes the card state kept with a reply's tool trace: the `shownJson` of its `run_intent` exchange holds
 * `{"cards":[record, ...]}`, the first record for the card the call proposed and the others for the copies "Do again" made.
 * Pure; no schema change (the trace is the existing JSON of the message).
 */
object ActionCardTrace {

    private const val RUN_INTENT = "run_intent"
    private const val CARDS = "cards"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** The cards of [message]: one per record of each `run_intent` call, or one without a record for a call never touched. */
    fun cardsOf(message: AiMessage): List<StoredActionCard> {
        if (message.role != MessageRole.ASSISTANT) return emptyList()
        return message.toolTrace.withIndex().filter { it.value.name == RUN_INTENT }.flatMap { (index, exchange) ->
            val arguments = runCatching { json.parseToJsonElement(exchange.argumentsJson).jsonObject }.getOrNull() ?: return@flatMap emptyList()
            val intent = (arguments["intent"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
            val parameters = (arguments["parameters"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val records = recordsOf(exchange.shownJson)
            val copies = if (records.isEmpty()) listOf<ActionCardRecord?>(null) else records
            copies.mapIndexed { copy, record ->
                StoredActionCard(ActionCardKey(message.conversationId, message.id, index, copy), intent, parameters, record)
            }
        }
    }

    /** [message] with the record of the card at [exchangeIndex] / [copy] set (earlier copies not stored yet repeat it). */
    fun withRecord(message: AiMessage, exchangeIndex: Int, copy: Int, record: ActionCardRecord): AiMessage {
        val exchange = message.toolTrace.getOrNull(exchangeIndex) ?: return message
        val shown = runCatching { json.parseToJsonElement(exchange.shownJson).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val element = json.encodeToJsonElement(ActionCardRecord.serializer(), record)
        val cards = ((shown[CARDS] as? JsonArray)?.toMutableList() ?: mutableListOf())
        while (cards.size <= copy) cards.add(element)
        cards[copy] = element
        val updated = exchange.copy(shownJson = JsonObject(shown + (CARDS to JsonArray(cards))).toString())
        return message.copy(toolTrace = message.toolTrace.mapIndexed { i, e -> if (i == exchangeIndex) updated else e })
    }

    private fun recordsOf(shownJson: String): List<ActionCardRecord?> {
        if (shownJson.isBlank()) return emptyList()
        val shown = runCatching { json.parseToJsonElement(shownJson).jsonObject }.getOrNull() ?: return emptyList()
        val cards = shown[CARDS] as? JsonArray ?: return emptyList()
        return cards.map { runCatching { json.decodeFromJsonElement(ActionCardRecord.serializer(), it) }.getOrNull() }
    }

    /** The text of [at] as stored in [ActionCardRecord.doneAt]. */
    fun timeText(at: LocalDateTime): String = at.toString()

    fun timeOf(text: String?): LocalDateTime? = text?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
}

/** The cards the conversation holds, in the order of its replies: every change of the conversation's messages gives the list again. */
class ObserveStoredActionCardsUseCase @Inject constructor(
    private val conversations: ConversationRepository,
) {
    operator fun invoke(conversationId: String): Flow<ConversationCards> =
        conversations.getMessages(conversationId).map { messages ->
            ConversationCards(
                cards = messages.flatMap(ActionCardTrace::cardsOf),
                userMessages = messages.filter { it.role == MessageRole.USER }.map { it.content },
            )
        }
}

/** The stored [cards] of a conversation and the user's own words in it (a value they wrote is theirs when a card is checked). */
data class ConversationCards(val cards: List<StoredActionCard>, val userMessages: List<String>)

/** Keeps a card's state with its reply. Saves are one at a time, so two quick changes never overwrite each other. */
class SaveActionCardStateUseCase @Inject constructor(
    private val conversations: ConversationRepository,
) {
    private val lock = Mutex()

    suspend operator fun invoke(key: ActionCardKey, record: ActionCardRecord) = lock.withLock {
        val message = conversations.getMessages(key.conversationId).first().firstOrNull { it.id == key.messageId } ?: return@withLock
        conversations.updateMessage(ActionCardTrace.withRecord(message, key.exchangeIndex, key.copy, record))
        Unit
    }
}
