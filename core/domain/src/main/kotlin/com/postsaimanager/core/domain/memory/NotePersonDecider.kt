package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.people.ConcernedPeopleProfile
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.form.BaselineYesNo
import com.postsaimanager.core.domain.form.PromptFraming
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.form.SuggestSubject
import com.postsaimanager.core.model.Relationship
import javax.inject.Inject

/**
 * The model's reading of "which household person is this note about?": the one place that question is asked for a note of the
 * all-documents chat. A port, so [WriteHouseholdNotesUseCase] is tested without a model; [ModelNotePersonDecider] binds it to the
 * loaded one.
 */
interface NotePersonDecider {

    /**
     * The person [note] is about, as a profile id from [persons]; null when it is about none of them in particular (a household-wide
     * note). An error when no model can answer.
     */
    suspend fun decide(note: String, persons: List<SubjectCandidate>): PamResult<String?>
}

/**
 * [NotePersonDecider] by the person-chip pattern ([BaselineYesNo]): the note is the prefix, decoded once; each household person, and a
 * made-up name that cannot be in the note, is scored separately by `logit(Yes) - logit(No)`. A small model leans Yes on everything, so a
 * person counts only when the score beats the made-up name's by the margin; of those that do the best scores. Nobody beats it: the note
 * is household-wide. The model can never name anyone who was not listed.
 *
 * The question and the margin are the concerned-people ones ([ConcernedPeopleProfile], the one owner of how the household is asked
 * about); there is no separate benchmark for notes yet.
 */
class ModelNotePersonDecider @Inject constructor(
    private val session: PromptSession,
    private val framing: PromptFraming,
    private val profile: ConcernedPeopleProfile,
) : NotePersonDecider {

    override suspend fun decide(note: String, persons: List<SubjectCandidate>): PamResult<String?> {
        val listed = persons.take(profile.maxMembers)
        if (listed.isEmpty()) return PamResult.Success(null)
        val baseline = SubjectCandidate("baseline", profile.baselineName, Relationship.RELATIVE)
        return when (
            val scored = BaselineYesNo(session, framing).score(
                system = SYSTEM,
                user = "NOTE\n" + note.take(MAX_NOTE_CHARS),
                statements = listed.map(::statement),
                baselineStatement = statement(baseline),
                shared = shared(listed),
            )
        ) {
            is PamResult.Error -> scored
            is PamResult.Success -> {
                val scores = scored.data
                val best = scores.beating(profile.margin).maxByOrNull { scores.candidates[it] }
                PamResult.Success(best?.let { listed[it].profileId })
            }
        }
    }

    /** The household as context, decoded once after the note and shared by every score. */
    private fun shared(listed: List<SubjectCandidate>): String = buildString {
        append("\n\nIs this note about any one of the following people?")
        listed.forEachIndexed { i, p -> append("\nP${i + 1}: ${p.name} (${SuggestSubject.relation(p)})") }
    }

    private fun statement(person: SubjectCandidate) = "Is it about «${person.name}» (${SuggestSubject.relation(person)})? Answer:"

    private companion object {
        const val SYSTEM = "You read a short note and answer questions about it. Say who it is about only when the note itself says so."
        const val MAX_NOTE_CHARS = 400
    }
}
