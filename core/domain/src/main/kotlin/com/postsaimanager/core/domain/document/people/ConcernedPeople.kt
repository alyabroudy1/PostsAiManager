package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.form.FormScorer
import com.postsaimanager.core.domain.form.FormScoringException
import com.postsaimanager.core.domain.form.PromptFraming
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.form.SuggestSubject
import com.postsaimanager.core.model.Relationship
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The model's reading of "is this letter for or about any one of these managed people?": the one place that question is asked.
 * A port so the decision and the re-check are testable without a model; [ModelConcernedPeople] binds it to the loaded one.
 */
interface ConcernedPeople {

    /**
     * Which of [members] the letter [letter] is for or about, as profile ids; empty when it is about none of them. [members] are
     * the people whose name the letter mentions (see `PartyNames.mentions`), never everybody. An error when no model can answer.
     */
    suspend fun decide(letter: String, members: List<SubjectCandidate>): PamResult<Set<String>>
}

/**
 * How the question is decided, as data.
 *
 * Each member is scored on its own (log-odds of Yes against No) and so is a neutral made-up name that is not in the letter; a small
 * model leans Yes on everything, so a member counts only when it beats that baseline by at least [margin]. On the five recorded
 * letters (`benchmark/concerned/`) every margin from just above 0.52 to 0.85 finds 7 of the 8 expected people with 1 wrong; below that
 * an unexpected family member comes in, above it a real one drops out. The middle of that range is the default.
 *
 * @property baselineName the made-up name scored beside the members (it must not be a name that could be in a letter).
 */
data class ConcernedPeopleProfile(
    val maxMembers: Int = 6,
    val maxLetterChars: Int = 6000,
    val margin: Double = 0.7,
    val baselineName: String = "Zoltan Quillfeather",
) {
    /** The indexes of [scores] that beat [baseline] by at least [margin]. */
    fun select(scores: List<Double>, baseline: Double): List<Int> = scores.indices.filter { scores[it] - baseline >= margin }
}

/** The log-odds of "for or about this member?" per listed member (in the listed order), and of the same question for the made-up name. */
data class ConcernedScores(val members: List<Double>, val baseline: Double)

/**
 * [ConcernedPeople] over the standing [PromptSession]: the letter is the prefix, decoded once. The members are listed as context
 * ("Is this letter for or about any one of the following members? P1: ..."), and then each member, and a made-up name, is scored
 * separately by `logit(Yes) - logit(No)` ([FormScorer]), each rolled back to the prefix. A member is kept when its score beats the
 * made-up name's by the profile's margin, so the model can never name anyone who was not listed.
 *
 * Follow-up: this is a session of its own, so the letter is read once more (about 10 s in the background on the phone); asked inside the
 * reading's own session it would cost only the scores.
 */
class ModelConcernedPeople @Inject constructor(
    private val session: PromptSession,
    private val framing: PromptFraming,
    private val profile: ConcernedPeopleProfile,
) : ConcernedPeople {

    override suspend fun decide(letter: String, members: List<SubjectCandidate>): PamResult<Set<String>> {
        val listed = members.take(profile.maxMembers)
        if (listed.isEmpty()) return PamResult.Success(emptySet())
        return when (val scored = scores(letter, listed)) {
            is PamResult.Error -> scored
            is PamResult.Success -> PamResult.Success(profile.select(scored.data.members, scored.data.baseline).map { listed[it].profileId }.toSet())
        }
    }

    /** The scores [decide] decides from, and what the evaluation records. */
    suspend fun scores(letter: String, members: List<SubjectCandidate>): PamResult<ConcernedScores> {
        val listed = members.take(profile.maxMembers)
        val (head, tail) = framing.frame(SYSTEM, "LETTER\n" + letter.take(profile.maxLetterChars))
        when (val opened = session.open(head)) {
            is PamResult.Error -> return opened
            is PamResult.Success -> Unit
        }
        return try {
            val baseline = SubjectCandidate("baseline", profile.baselineName, Relationship.RELATIVE)
            val all = FormScorer(session, tail).yesNo((listed + baseline).map(::statement), shared(listed))
            PamResult.Success(ConcernedScores(all.dropLast(1), all.last()))
        } catch (e: FormScoringException) {
            PamResult.Error(e.error)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    /** The members as context, decoded once after the letter and shared by every score. */
    private fun shared(listed: List<SubjectCandidate>): String = buildString {
        append("\n\nIs this letter for or about any one of the following members?")
        listed.forEachIndexed { i, m -> append("\nP${i + 1}: ${m.name} (${SuggestSubject.relation(m)})") }
    }

    private fun statement(member: SubjectCandidate) = "Is it for or about «${member.name}» (${SuggestSubject.relation(member)})? Answer:"

    private companion object {
        const val SYSTEM = "You read a letter and answer questions about it. Say who it is for or about only when the letter itself says so."
    }
}
