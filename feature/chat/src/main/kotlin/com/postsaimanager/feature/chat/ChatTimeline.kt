package com.postsaimanager.feature.chat

/** One row of the chat list. The list is reversed (index 0 is the bottom), so [ChatTimeline.build] returns the newest row first. */
internal sealed interface TimelineEntry {
    /** Stable across recomposition and across a card moving from the live bubble to its stored reply. */
    val key: Any

    data object Error : TimelineEntry {
        override val key: Any = "error"
    }

    /** The live reply: its text so far, or the status line / typing indicator before the first token. */
    data object Live : TimelineEntry {
        override val key: Any = "live"
    }

    data object Thinking : TimelineEntry {
        override val key: Any = "thinking"
    }

    data class Card(val card: ActionCardState) : TimelineEntry {
        override val key: Any = cardKey(card.id)
    }

    data class Message(val message: ChatMessage) : TimelineEntry {
        override val key: Any = message.id.ifEmpty { message.timestamp.toString() }
    }

    /** The quiet line above the first message the model reads: what is above it is in the transcript only, the assistant keeps notes. */
    data object ContextDivider : TimelineEntry {
        override val key: Any = "context-divider"
    }

    companion object {
        fun cardKey(cardId: String): String = "action-$cardId"
    }
}

/**
 * Where everything of the chat sits, in one place: an action card is a row of the conversation like a message. It sits right
 * under the reply that proposed it ([ActionCardState.storedKey]'s message) and scrolls with it; a card whose reply is not
 * stored yet sits under the live reply, and moves under the stored reply once that is persisted and the card adopts it.
 */
internal object ChatTimeline {

    /**
     * The rows, newest first. [messages] are in conversation order (oldest first), [cards] in the order they came. The flags say
     * which live rows exist: an [error], the live reply or its status ([live]) and the reasoning trace ([thinking]). With
     * [contextStartId] (the first message of the continuity tail) a [TimelineEntry.ContextDivider] sits above that message, when
     * there are older messages.
     */
    fun build(
        messages: List<ChatMessage>,
        cards: List<ActionCardState>,
        error: Boolean,
        live: Boolean,
        thinking: Boolean,
        contextStartId: String? = null,
    ): List<TimelineEntry> {
        // Only when something is above it: with nothing older, the whole chat is what the model reads.
        val dividerAbove = contextStartId?.takeIf { id -> messages.indexOfFirst { it.id == id } > 0 }
        val shown = messages.filter { it.id.isNotEmpty() }.map { it.id }.toSet()
        val byReply = cards.filter { it.storedKey?.messageId in shown }.groupBy { it.storedKey?.messageId }
        val unplaced = cards.filter { it.storedKey?.messageId !in shown }
        return buildList {
            if (error) add(TimelineEntry.Error)
            unplaced.asReversed().forEach { add(TimelineEntry.Card(it)) }
            if (live) add(TimelineEntry.Live)
            if (thinking) add(TimelineEntry.Thinking)
            for (message in messages.asReversed()) {
                val own = byReply[message.id].orEmpty()
                // An action-only reply has no words to show, but its steps (the skill it used) and its cards are still shown.
                if (message.isEmptyReply && message.toolSteps.isEmpty() && own.isEmpty()) continue
                own.asReversed().forEach { add(TimelineEntry.Card(it)) }
                add(TimelineEntry.Message(message))
                if (message.id == dividerAbove) add(TimelineEntry.ContextDivider)
            }
        }
    }

    /** The row of the newest pending card of [entries] that is not among the [visible] keys, or null: where the pill leads. */
    fun newestWaitingOffScreen(entries: List<TimelineEntry>, visible: Set<Any>): Int? =
        entries.indexOfFirst { it is TimelineEntry.Card && it.card.status == ActionCardStatus.PENDING && it.key !in visible }
            .takeIf { it >= 0 }

    /** How many pending cards are scrolled out of view. */
    fun waitingOffScreen(entries: List<TimelineEntry>, visible: Set<Any>): Int =
        entries.count { it is TimelineEntry.Card && it.card.status == ActionCardStatus.PENDING && it.key !in visible }
}
