package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.model.Relationship
import java.time.LocalDate
import java.time.Period

/** A managed profile the form may be for. [relationship] is to the user; [isSelf] marks the user's own profile. */
data class SubjectCandidate(
    val profileId: String,
    val name: String,
    val relationship: Relationship? = null,
    val isSelf: Boolean = false,
    val birthDate: LocalDate? = null,
)

/**
 * One profile's chance of being the form's subject, best first.
 *
 * @property reasonLine on the best profile only: the form line that supports the suggestion, a verified substring of the form's text.
 */
data class SubjectSuggestion(
    val profileId: String,
    val score: Double,
    val confidence: Float,
    val plausible: Boolean,
    val reasonLine: String? = null,
)

/**
 * Suggests who the form is for: for each managed profile (at most [FormScoringProfile.maxSubjects]) the model scores "Is this form
 * for <relationship> <name>, aged <n>?" with the form's title and intro lines as the shared context. The age comes from the
 * birth date; whether it fits the form ("children 6 to 10") is the model's reading, with no age-range pattern in code.
 * For the best profile the intro lines are then scored as its reason and the best is returned quoted, after a verification
 * that it is really in the form's text.
 */
class SuggestSubject(
    private val scorer: FormScorer,
    private val profile: FormScoringProfile = FormScoringProfile(),
) {

    /** [introLines] are the form's first lines in reading order. Throws [FormScoringException] when the engine fails. */
    suspend fun suggest(introLines: List<String>, candidates: List<SubjectCandidate>, today: LocalDate): List<SubjectSuggestion> {
        val subjects = candidates.take(profile.maxSubjects)
        if (subjects.isEmpty()) return emptyList()
        val intro = introLines.joinToString("\n")
        val shared = "\n\nFORM\n$intro"
        val scores = scorer.yesNo(subjects.map { "Is this form for ${describe(it, today)}? Answer:" }, shared)

        val order = subjects.indices.sortedByDescending { scores[it] }
        val bestScore = scores[order.first()]
        val reason = if (bestScore > profile.subjectThreshold) reasonFor(subjects[order.first()], today, introLines, intro, shared) else null
        return order.mapIndexed { rank, i ->
            val margin = if (order.size > 1) scores[i] - scores[order[if (rank == 0) 1 else 0]] else scores[i]
            SubjectSuggestion(subjects[i].profileId, scores[i], profile.confidence(margin), scores[i] > profile.subjectThreshold, if (rank == 0) reason else null)
        }
    }

    private suspend fun reasonFor(subject: SubjectCandidate, today: LocalDate, lines: List<String>, intro: String, shared: String): String? {
        val candidates = lines.filter { it.any(Char::isLetterOrDigit) }.take(profile.reasonLines)
        if (candidates.isEmpty()) return null
        val who = describe(subject, today)
        val scores = scorer.yesNo(candidates.map { "Does the line «$it» support that this form is for $who? Answer:" }, shared)
        val best = candidates.indices.maxByOrNull { scores[it] } ?: return null
        if (scores[best] <= profile.subjectThreshold) return null
        return candidates[best].takeIf { QuoteVerifier.verify(it, intro) != null }
    }

    private fun describe(c: SubjectCandidate, today: LocalDate): String {
        val age = c.birthDate?.let { ", aged ${Period.between(it, today).years}" }.orEmpty()
        return "${relation(c)} ${c.name}$age"
    }

    companion object {
        /** How the model is told who [c] is to the user; the one wording every question about a managed person uses. */
        fun relation(c: SubjectCandidate): String = if (c.isSelf) "the user" else PHRASES[c.relationship] ?: "a person"

        /** How the model is told the relationship (English content descriptions, never shown to users). */
        private val PHRASES: Map<Relationship?, String> = mapOf(
            Relationship.CHILD to "the user's child",
            Relationship.PARTNER to "the user's partner",
            Relationship.PARENT to "the user's parent",
            Relationship.RELATIVE to "a relative of the user",
            Relationship.FRIEND to "a friend of the user",
            Relationship.OTHER to "a person",
        )
    }
}
