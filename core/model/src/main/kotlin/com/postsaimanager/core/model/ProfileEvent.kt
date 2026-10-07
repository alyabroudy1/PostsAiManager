package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/** Where a [ProfileEvent] came from. A re-read of a document replaces only its [DOCUMENT] events. */
@Serializable
enum class EventSource {
    /** What a letter says happened, read by the model (kind and title decided by it, verified by code). */
    DOCUMENT,

    /** Something the user did through the app (a confirmed reminder, e-mail or calendar entry). Written by code, no model. */
    ACTION,

    /** Something the user wrote on the timeline. Never replaced by a re-read. */
    USER,

    /** A fact the app derived by itself (a due date passed with nothing done). Written by code, no model. */
    SYSTEM,
}

/**
 * One dated thing that happened in a profile's life: a letter that approved or rejected something, a payment demand, a reminder the
 * user set. A temporal record in the sense of "when it happened" ([eventDate]) against "when we learned it" ([recordedAt]).
 *
 * Not the processing log of a document (`TimelineEvent`, "text extracted"): that one is about the app's work, this one about the
 * user's matters.
 *
 * @property kind an id of the event-kind registry (`EventKinds`); an id this build does not know is shown as information.
 * @property eventDate epoch millis of the start of the day the letter says it happened (an action: the moment it was done).
 * @property recordedAt epoch millis when the app wrote this event.
 * @property title one line in the document's own language, grounded in the letter ([source] [EventSource.DOCUMENT]); for the other
 *   sources the app's own wording is rendered from [kind] in the user's language, and this holds a fallback line.
 * @property personProfileIds the household persons the event concerns (the document's person chips); an event has one row with all of
 *   them, so an organisation's timeline shows it once.
 * @property organisationProfileId the sender organisation; null while the sender is not resolved.
 * @property contactId the organisation's contact who handled the letter.
 * @property caseId the matter the event belongs to; null while it belongs to none.
 */
@Serializable
data class ProfileEvent(
    val id: String,
    val documentId: String,
    val kind: String,
    val eventDate: Long,
    val recordedAt: Long,
    val title: String,
    val personProfileIds: List<String> = emptyList(),
    val organisationProfileId: String? = null,
    val contactId: String? = null,
    val caseId: String? = null,
    val source: EventSource = EventSource.DOCUMENT,
)

/** Where a matter stands, derived from its events (see `CaseStatusDeriver`), never typed by hand. */
@Serializable
enum class CaseStatus { OPEN, APPROVED, REJECTED, CLOSED }

/**
 * A matter ("Vorgang"): the events of one organisation that belong together, such as an application and what came of it.
 *
 * @property title generated from the matter's first event when it was created; the user may rename it.
 * @property referenceKeys the normalised reference values (file, customer, contract, policy numbers) that tie letters to this matter,
 *   matched exactly by code; empty for a matter grouped by the model's same-matter decision only.
 * @property status derived from the latest event whose kind says where the matter stands; stored so lists can show it.
 */
@Serializable
data class Case(
    val id: String,
    val organisationProfileId: String,
    val title: String,
    val referenceKeys: Set<String> = emptySet(),
    val status: CaseStatus = CaseStatus.OPEN,
    val createdAt: Long,
)

/**
 * What the second stage decided a letter's event is: the [kindId] (an id of the event-kind registry) and a grounded [title], or null
 * when none could be written (the document's own title stands in).
 */
@Serializable
data class EventReading(val kindId: String, val title: String? = null)
