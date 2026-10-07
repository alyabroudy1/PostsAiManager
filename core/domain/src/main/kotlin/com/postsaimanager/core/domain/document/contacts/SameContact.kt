package com.postsaimanager.core.domain.document.contacts

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.domain.form.BaselineYesNo
import com.postsaimanager.core.domain.form.PromptFraming
import java.time.Instant
import javax.inject.Inject

/** A contact person as a letter printed them: the name as written and whatever else was read. */
data class ReadContact(
    val name: String,
    val title: String? = null,
    val phone: String? = null,
    val email: String? = null,
)

/**
 * An existing contact of the same organisation, as plain values (the phase-1 `ContactPerson` maps onto it).
 *
 * @property lastSeenAt epoch millis of the newest letter that named them, or null.
 */
data class ContactCandidate(
    val id: String,
    val name: String,
    val title: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val lastSeenAt: Long? = null,
)

/** What is asked once: the new contact, where it was read, and the (already narrowed) candidates, in the order they are scored. */
data class SameContactQuestion(
    val contact: ReadContact,
    val organisation: String,
    val excerpt: String?,
    val candidates: List<ContactCandidate>,
)

/**
 * The model's reading of "is the contact in this letter the same person as one of these?": the one place that question is asked.
 * A port so the decision is testable without a model; [ModelSameContact] binds it to the loaded one. It returns scores only: the margin
 * and the choice belong to [DecideSameContactUseCase].
 */
interface SameContact {

    /** One score per [SameContactQuestion.candidates] entry (same order) and the made-up distractor's. An error when no model can answer. */
    suspend fun score(question: SameContactQuestion): PamResult<BaselineScores>
}

/**
 * How the question is decided, as data.
 *
 * @property margin a candidate counts only when its score beats the made-up distractor's by at least this much. 0.7 is the value
 * the concerned-people question ([com.postsaimanager.core.domain.document.people.ConcernedPeopleProfile]) was recorded on (a small
 * model leans Yes on everything, so only the distance to the baseline says anything); the same model and the same shape of question
 * are used here, and no recording of this question exists yet to justify another value.
 * @property baselineName the made-up contact name scored beside the candidates (it must not be a name that could be in a letter).
 */
data class SameContactProfile(
    val margin: Double = DEFAULT_MARGIN,
    val maxCandidates: Int = 6,
    val maxExcerptChars: Int = 1500,
    val baselineName: String = "Zoltan Quillfeather",
) {
    companion object {
        const val DEFAULT_MARGIN = 0.7
    }
}

/**
 * [SameContact] over the standing [PromptSession]: the organisation, the contact as read and the excerpt are the prefix (decoded once),
 * the candidates are listed as shared context, and then each candidate, and a made-up contact, is scored separately by
 * `logit(Yes) - logit(No)` ([BaselineYesNo]). A phone number or email equal to the new contact's is shown to the model as a hint in
 * that candidate's question; it never decides. The wording is English; the letter and the names may be in any language.
 */
class ModelSameContact @Inject constructor(
    private val session: PromptSession,
    private val framing: PromptFraming,
    private val profile: SameContactProfile,
) : SameContact {

    override suspend fun score(question: SameContactQuestion): PamResult<BaselineScores> {
        val listed = question.candidates.take(profile.maxCandidates)
        return BaselineYesNo(session, framing).score(
            system = SYSTEM,
            user = user(question),
            statements = listed.map { statement(question.contact, it) },
            baselineStatement = statement(question.contact, ContactCandidate("baseline", profile.baselineName)),
            shared = shared(listed),
        )
    }

    private fun user(q: SameContactQuestion): String = buildString {
        append("ORGANISATION: ").append(q.organisation)
        append("\nCONTACT IN THIS LETTER\n").append(details(q.contact.name, q.contact.title, q.contact.phone, q.contact.email, null))
        q.excerpt?.takeIf { it.isNotBlank() }?.let { append("\nLETTER EXCERPT\n").append(it.take(profile.maxExcerptChars)) }
    }

    private fun shared(listed: List<ContactCandidate>): String = buildString {
        append("\n\nKnown contacts of this organisation:")
        listed.forEachIndexed { i, c -> append("\nC${i + 1}: ").append(details(c.name, c.title, c.phone, c.email, c.lastSeenAt)) }
    }

    private fun statement(contact: ReadContact, candidate: ContactCandidate): String = buildString {
        append("Is the contact in this letter the same person as «").append(candidate.name).append('»')
        val what = listOfNotNull(candidate.title?.takeIf { it.isNotBlank() }, candidate.lastSeenAt?.let { "last seen ${date(it)}" })
        if (what.isNotEmpty()) append(" (").append(what.joinToString(", ")).append(')')
        append('?')
        if (sameDigits(contact.phone, candidate.phone)) append(" The phone number is the same.")
        if (sameText(contact.email, candidate.email)) append(" The email address is the same.")
        append(" Answer:")
    }

    private fun details(name: String, title: String?, phone: String?, email: String?, lastSeenAt: Long?): String =
        listOfNotNull(
            name,
            title?.takeIf { it.isNotBlank() },
            phone?.takeIf { it.isNotBlank() }?.let { "phone $it" },
            email?.takeIf { it.isNotBlank() }?.let { "email $it" },
            lastSeenAt?.let { "last seen ${date(it)}" },
        ).joinToString("; ")

    private fun date(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString().take(DATE_CHARS)

    private fun sameDigits(a: String?, b: String?): Boolean {
        val x = a?.filter(Char::isDigit).orEmpty()
        return x.length >= MIN_DIGITS && x == b?.filter(Char::isDigit)
    }

    private fun sameText(a: String?, b: String?): Boolean = !a.isNullOrBlank() && a.trim().equals(b?.trim(), ignoreCase = true)

    private companion object {
        const val SYSTEM = "You read a letter and answer questions about the people it names. Say two contacts are the same person only when the details agree."
        const val DATE_CHARS = 10
        const val MIN_DIGITS = 5
    }
}
