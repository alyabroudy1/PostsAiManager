package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import kotlinx.coroutines.CancellationException

/**
 * The shared half of writing a session's notes, for a document's chat ([WriteSessionNotesUseCase]) and for the all-documents chat
 * ([WriteHouseholdNotesUseCase]): the one generation of candidate notes and their verification. Where the notes are stored, and for
 * whom, is the caller's.
 *
 * The model decides what is worth keeping ([SessionNotesFormat]); code only verifies ([SessionNoteVerifier]). Quiet background work:
 * it answers nothing when the generator is not available, and never throws over a failed generation.
 */
internal class SessionNoteDrafter(
    private val generator: SessionNoteGenerator,
    private val verifier: SessionNoteVerifier,
    private val appLanguage: com.postsaimanager.core.domain.settings.AppLanguageProvider? = null,
) {

    /**
     * @param sessionTurns the messages of the session that ended, oldest first
     * @param existing the notes already kept (not repeated, and counted against the cap)
     * @param actionNotes the notes of confirmed actions (part of [existing]): the model is told not to restate them, and the verifier
     *   drops a note that does
     * @param cardText the read values the notes must not repeat
     * @param about what the notes are about, as the question words it
     * @param deferIfUnavailable throw [SessionNotesDeferred] instead of answering null when the model could not be used (not resident,
     *   busy, no answer): the queued write of a session's notes then runs again later instead of losing them
     * @return the verified notes, in order, plus the message id they are grounded in (the last thing the user said), or null when there
     *   is nothing to write
     */
    suspend fun draft(
        sessionTurns: List<AiMessage>,
        existing: List<String>,
        cardText: String,
        about: String,
        actionNotes: List<String> = emptyList(),
        deferIfUnavailable: Boolean = false,
    ): Draft? {
        val userTurns = sessionTurns.filter { it.role == MessageRole.USER && it.content.isNotBlank() }
        // Nothing the user said: nothing to remember, and no reason to wake the model.
        if (userTurns.isEmpty()) return null
        if (!generator.isAvailable()) return if (deferIfUnavailable) throw SessionNotesDeferred() else null

        val turns = sessionTurns.mapNotNull { message ->
            when (message.role) {
                MessageRole.USER -> SessionNotesFormat.Turn(fromUser = true, text = message.content)
                MessageRole.ASSISTANT -> SessionNotesFormat.Turn(fromUser = false, text = message.content)
                else -> null
            }
        }
        val answer = try {
            generator.generate(SessionNotesFormat.SYSTEM, SessionNotesFormat.prompt(turns, existing, about, actionNotes, appLanguage?.aiLanguageCode()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return if (deferIfUnavailable) throw SessionNotesDeferred() else null

        val grounding = userTurns.map { it.content } +
            sessionTurns.flatMap { message -> message.toolTrace.map { it.resultJson } + listOfNotNull(message.toolResult) }
        // A user message the assistant answered with a tool call is a request, carried out and recorded as an action: not a fact to keep.
        val commands = sessionTurns.zipWithNext()
            .filter { (user, reply) ->
                user.role == MessageRole.USER && reply.role == MessageRole.ASSISTANT && (reply.toolTrace.isNotEmpty() || reply.toolResult != null)
            }
            .map { (user, _) -> user.content }
        val kept = verifier.verify(
            SessionNotesFormat.parse(answer), grounding, cardText, existing, actionNotes,
            userMessages = userTurns.map { it.content }, commands = commands,
        )
        return if (kept.isEmpty()) null else Draft(kept, userTurns.last().id)
    }

    /** The verified [notes] and the id of the message they are grounded in. */
    class Draft(val notes: List<String>, val sourceRef: String)
}

/** The model could not write a session's notes now (not resident, busy, no answer): they are written later, not dropped. */
class SessionNotesDeferred : Exception("The chat model is not available for the session's notes yet")
