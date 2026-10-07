package com.postsaimanager.core.model

/** Who a note of the document memory came from. */
enum class NoteSource {
    /** Written by code when the user confirmed an action card (no AI): the card's state change is the fact. */
    ACTION,

    /** Distilled by the model at the end of a chat session and checked by code against the user's own words. */
    AI,

    /** Typed by the user. */
    USER,
}

/**
 * One durable note about a document: a short fact or decision that should outlive the chat it came from ("Reminder set for 8 Oct
 * 09:00", "Already paid on 5 Oct, says the user"). The assistant reads the notes with the document card when a chat is built; the
 * user sees, edits, pins and deletes them ("What the assistant remembers").
 *
 * A note belongs to a document ([documentId]), to a household person ([profileId], written by the all-documents chat), or to neither:
 * a household-wide note of that chat. Never to both.
 *
 * @param sourceRef what the note came from: the action card's id for an [NoteSource.ACTION] note (one note per card), a message id
 *   for an AI note; null for a note the user typed.
 * @param pinned a pinned note is always in the assistant's context, ahead of the newer ones.
 */
data class DocumentNote(
    val id: String,
    val documentId: String?,
    val text: String,
    val source: NoteSource,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val sourceRef: String? = null,
    val profileId: String? = null,
)
