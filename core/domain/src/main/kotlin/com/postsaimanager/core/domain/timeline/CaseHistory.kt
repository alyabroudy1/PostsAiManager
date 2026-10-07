package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.model.ProfileEvent
import java.time.Instant
import java.time.ZoneId

/**
 * "Earlier in this case": the events of the matter a letter is part of, other than the letter's own, as the lines the document card
 * shows the chat model, so it understands the letter in its history ("10 Sep approved; 3 Nov documents requested"). Pure.
 *
 * At most [MAX_EVENTS] (the most recent ones), oldest first, one compact line each: the date, the kind's label (the registry's
 * English one; the title is already in the letter's own language) and the title.
 *
 * ```
 * - 2026-09-10 Approval: Bürgergeld approved from 1 Sep
 * - 2026-11-03 Documents requested: Proof of income requested
 * ```
 * Every line is the stored event: nothing here is decided, ranked or reworded.
 */
object CaseHistory {

    const val MAX_EVENTS = 4

    /** A title longer than this is cut: the card is read by the model before every first message. */
    const val MAX_TITLE = 70

    fun lines(
        case: CaseTimeline?,
        documentId: String,
        kinds: EventKinds = EventKinds.DEFAULT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        if (case == null) return emptyList()
        val earlier = case.events.filter { it.documentId != documentId }
            .sortedWith(compareBy<ProfileEvent> { it.eventDate }.thenBy { it.recordedAt })
            .takeLast(MAX_EVENTS)
        return earlier.map { event ->
            val date = Instant.ofEpochMilli(event.eventDate).atZone(zone).toLocalDate()
            val label = kinds.byId(event.kind).label(EventKind.ENGLISH)
            val title = event.title.replace(WHITESPACE, " ").trim().let { if (it.length > MAX_TITLE) it.take(MAX_TITLE - 1).trimEnd() + "…" else it }
            "- $date $label" + if (title.isEmpty()) "" else ": $title"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
