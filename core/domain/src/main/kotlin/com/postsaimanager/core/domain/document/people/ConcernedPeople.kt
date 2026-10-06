package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.v2.AnswerReader
import com.postsaimanager.core.domain.extraction.v2.QuestionGrammars
import com.postsaimanager.core.domain.form.FormScorer
import com.postsaimanager.core.domain.form.FormScoringException
import com.postsaimanager.core.domain.form.PromptFraming
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.form.SuggestSubject
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
 * How the question is asked, as data.
 *
 * @property reversedCheck ask a second time with the members in the opposite order and keep only the ones named both times: a small
 *   model favours a position in a list. Off until the evaluation says it pays.
 */
data class ConcernedPeopleProfile(
    val maxMembers: Int = 6,
    val maxLetterChars: Int = 6000,
    val maxAnswerTokens: Int = 24,
    val reversedCheck: Boolean = false,
)

/** The answers one letter got in the evaluation, with the per-member scores of the other question form beside them. */
data class ConcernedComparison(val forward: Set<String>, val reversed: Set<String>, val scores: List<Double>)

/**
 * [ConcernedPeople] over the standing [PromptSession]: the letter is the prefix, decoded once; the question lists the members as
 * `P1`, `P2`, ... and the answer is grammar-constrained to those ids joined by `; `, or NONE ([QuestionGrammars.members],
 * [AnswerReader.members]), so the model cannot name anyone who was not listed. Each question is rolled back to the prefix.
 */
class ModelConcernedPeople @Inject constructor(
    private val session: PromptSession,
    private val framing: PromptFraming,
    private val profile: ConcernedPeopleProfile,
) : ConcernedPeople {

    override suspend fun decide(letter: String, members: List<SubjectCandidate>): PamResult<Set<String>> {
        val listed = members.take(profile.maxMembers)
        if (listed.isEmpty()) return PamResult.Success(emptySet())
        return inSession(letter) { tail ->
            val forward = answer(listed, tail)
            when {
                forward is PamResult.Error -> forward
                !profile.reversedCheck -> forward
                else -> when (val back = answer(listed.reversed(), tail)) {
                    is PamResult.Error -> back
                    is PamResult.Success -> PamResult.Success((forward as PamResult.Success).data intersect back.data)
                }
            }
        }
    }

    /**
     * For the evaluation only: in one session over [letter], the list answer in both orders and the per-member log-odds of
     * "Is this letter for or about <member>?" (the other question form), so the two can be compared on the same letters.
     */
    suspend fun compare(letter: String, members: List<SubjectCandidate>): PamResult<ConcernedComparison> {
        val listed = members.take(profile.maxMembers)
        return inSession(letter) { tail ->
            val forward = answer(listed, tail)
            val back = answer(listed.reversed(), tail)
            val scores = try {
                FormScorer(session, tail).yesNo(listed.map { "Is this letter for or about ${SuggestSubject.relation(it)} ${it.name}? Answer:" })
            } catch (e: FormScoringException) {
                return@inSession PamResult.Error(e.error)
            }
            when {
                forward is PamResult.Error -> forward
                back is PamResult.Error -> back
                else -> PamResult.Success(ConcernedComparison((forward as PamResult.Success).data, (back as PamResult.Success).data, scores))
            }
        }
    }

    private suspend fun <T> inSession(letter: String, block: suspend (tail: String) -> PamResult<T>): PamResult<T> {
        val (head, tail) = framing.frame(SYSTEM, "LETTER\n" + letter.take(profile.maxLetterChars))
        when (val opened = session.open(head)) {
            is PamResult.Error -> return opened
            is PamResult.Success -> Unit
        }
        return try {
            block(tail)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private suspend fun answer(order: List<SubjectCandidate>, tail: String): PamResult<Set<String>> {
        val ids = order.indices.map { "P${it + 1}" }
        val question = buildString {
            append("Is this letter for or about any one of the following people? ")
            append("Answer with the ids of those it is for or about, joined by \"; \", or NONE if it is about none of them.")
            order.forEachIndexed { i, member -> append("\n${ids[i]}: ${member.name} (${SuggestSubject.relation(member)})") }
        }
        return when (val r = session.ask("\n\n$question$tail", QuestionGrammars.members(ids), profile.maxAnswerTokens)) {
            is PamResult.Error -> r
            is PamResult.Success -> {
                val byId = ids.zip(order).toMap()
                PamResult.Success(AnswerReader.members(r.data).mapNotNull { byId[it]?.profileId }.toSet())
            }
        }
    }

    private companion object {
        const val SYSTEM = "You read a letter and answer questions about it. Say who it is for or about only when the letter itself says so."
    }
}
