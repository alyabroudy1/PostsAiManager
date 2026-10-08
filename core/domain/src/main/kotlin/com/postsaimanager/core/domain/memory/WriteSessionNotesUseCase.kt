package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.domain.repository.DocumentNoteRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Writes the AI notes of a finished chat session: the facts and decisions of the conversation that matter for the document later
 * ("the user already paid on 5 Oct"). The model decides what is worth keeping ([SessionNotesFormat]); code only verifies
 * ([SessionNoteVerifier]: every number is in the user's own words or a tool result, no repeat of the card or an existing note, the
 * cap). The generation and the verification are [SessionNoteDrafter]'s, shared with the all-documents chat
 * ([WriteHouseholdNotesUseCase]).
 *
 * Called by the session code when a session ends (the user leaves the chat, or 10 minutes idle). Quiet background work: it does
 * nothing when the generator is not available (no chat model resident, or the chat busy), never reloads a model for notes, and
 * never throws over a failed generation.
 */
class WriteSessionNotesUseCase @Inject constructor(
    generator: SessionNoteGenerator,
    private val notes: DocumentNoteRepository,
    private val documents: DocumentRepository,
    verifier: SessionNoteVerifier,
) {

    private val drafter = SessionNoteDrafter(generator, verifier)

    /**
     * @param sessionTurns the messages of the session that ended, oldest first (user, assistant and tool results as they are stored)
     * @return how many notes were written
     */
    suspend operator fun invoke(documentId: String, sessionTurns: List<AiMessage>): Int {
        val all = notes.notes(documentId)
        val existing = all.map { it.text }
        val actionNotes = all.filter { it.source == NoteSource.ACTION }.map { it.text }
        val card = documents.observeExtractedData(documentId).first().filter { !it.deletedByUser }
            .joinToString("\n") { it.fieldName + " " + it.fieldValue }
        val draft = drafter.draft(sessionTurns, existing, card, about = SessionNotesFormat.ABOUT_DOCUMENT, actionNotes = actionNotes) ?: return 0
        draft.notes.forEach { notes.add(documentId, it, NoteSource.AI, sourceRef = draft.sourceRef) }
        return draft.notes.size
    }
}
