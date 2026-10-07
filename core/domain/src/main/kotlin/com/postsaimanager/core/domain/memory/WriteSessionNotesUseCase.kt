package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.domain.repository.DocumentNoteRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Writes the AI notes of a finished chat session: the facts and decisions of the conversation that matter for the document later
 * ("the user already paid on 5 Oct"). The model decides what is worth keeping ([SessionNotesFormat]); code only verifies
 * ([SessionNoteVerifier]: every number is in the user's own words or a tool result, no repeat of the card or an existing note, the
 * cap).
 *
 * Called by the session code when a session ends (the user leaves the chat, or 10 minutes idle). Quiet background work: it does
 * nothing when the generator is not available (no chat model resident, or the chat busy), never reloads a model for notes, and
 * never throws over a failed generation.
 */
class WriteSessionNotesUseCase @Inject constructor(
    private val generator: SessionNoteGenerator,
    private val notes: DocumentNoteRepository,
    private val documents: DocumentRepository,
    private val verifier: SessionNoteVerifier,
) {

    /**
     * @param sessionTurns the messages of the session that ended, oldest first (user, assistant and tool results as they are stored)
     * @return how many notes were written
     */
    suspend operator fun invoke(documentId: String, sessionTurns: List<AiMessage>): Int {
        val userTurns = sessionTurns.filter { it.role == MessageRole.USER && it.content.isNotBlank() }
        // Nothing the user said: nothing to remember, and no reason to wake the model.
        if (userTurns.isEmpty() || !generator.isAvailable()) return 0

        val existing = notes.notes(documentId).map { it.text }
        val turns = sessionTurns.mapNotNull { message ->
            when (message.role) {
                MessageRole.USER -> SessionNotesFormat.Turn(fromUser = true, text = message.content)
                MessageRole.ASSISTANT -> SessionNotesFormat.Turn(fromUser = false, text = message.content)
                else -> null
            }
        }
        val answer = try {
            generator.generate(SessionNotesFormat.SYSTEM, SessionNotesFormat.prompt(turns, existing))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return 0

        val grounding = userTurns.map { it.content } +
            sessionTurns.flatMap { message -> message.toolTrace.map { it.resultJson } + listOfNotNull(message.toolResult) }
        val card = documents.observeExtractedData(documentId).first().filter { !it.deletedByUser }
            .joinToString("\n") { it.fieldName + " " + it.fieldValue }
        val kept = verifier.verify(SessionNotesFormat.parse(answer), grounding, card, existing)

        val source = userTurns.last().id
        kept.forEach { notes.add(documentId, it, NoteSource.AI, sourceRef = source) }
        return kept.size
    }
}
