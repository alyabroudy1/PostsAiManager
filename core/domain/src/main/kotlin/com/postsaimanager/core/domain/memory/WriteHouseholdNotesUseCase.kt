package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.repository.DocumentNoteRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Writes the AI notes of a finished all-documents chat session: the variant of [WriteSessionNotesUseCase] for a chat that has no
 * document. The notes are drafted and verified exactly as a document's are ([SessionNoteDrafter]); what differs is whom a note is
 * about. The model decides that per note ([NotePersonDecider], scored against the household's names with a made-up name as the
 * baseline); a note that passes the margin for nobody is household-wide (no person, no document). Code only verifies: a decided
 * person must be one of the household persons that were asked about.
 *
 * Quiet background work, like the document variant: nothing happens when no chat model is available, and nothing throws. A person
 * decision that fails (no model to ask) leaves the note household-wide rather than losing it.
 */
class WriteHouseholdNotesUseCase @Inject constructor(
    generator: SessionNoteGenerator,
    private val notes: DocumentNoteRepository,
    private val profiles: ProfileRepository,
    verifier: SessionNoteVerifier,
    private val personOf: NotePersonDecider,
) {

    private val drafter = SessionNoteDrafter(generator, verifier)

    /**
     * @param sessionTurns the messages of the session that ended, oldest first
     * @return how many notes were written
     */
    suspend operator fun invoke(sessionTurns: List<AiMessage>): Int {
        val existing = notes.notesOutsideDocuments().map { it.text }
        // No read values to compare with: the all-documents card is an overview, and a note repeating it is caught as a repeat of a note.
        val draft = drafter.draft(sessionTurns, existing, cardText = "", about = SessionNotesFormat.ABOUT_HOUSEHOLD) ?: return 0
        val household = profiles.getProfiles().first().filter { it.isManaged }
            .map { SubjectCandidate(it.id, it.name, it.relationship, it.isSelf) }
        draft.notes.forEach { text ->
            notes.addOutsideDocument(decide(text, household), text, NoteSource.AI, sourceRef = draft.sourceRef)
        }
        return draft.notes.size
    }

    private suspend fun decide(note: String, household: List<SubjectCandidate>): String? {
        if (household.isEmpty()) return null
        val answer = try {
            personOf.decide(note, household)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return when (answer) {
            is PamResult.Error -> null
            is PamResult.Success -> answer.data?.takeIf { id -> household.any { it.profileId == id } }
        }
    }
}
