package com.postsaimanager.core.domain.organisation

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.domain.form.BaselineYesNo
import com.postsaimanager.core.domain.form.PromptFraming
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Whose a value of a letter is. */
enum class DetailOwner { ORGANISATION, CONTACT, NEITHER }

/**
 * What is asked once for one value: the value as printed, the organisation that sent the letter, the contact person the letter names
 * (null when it names none) and a short excerpt of the letter around the value.
 */
data class DetailQuestion(
    val kind: DetailKind,
    val value: String,
    val organisation: String,
    val contactName: String?,
    val excerpt: String?,
)

/**
 * The model's reading of "whose is this phone number, e-mail address, website or account: the organisation's own (a switchboard, a
 * service line, a central mailbox, its bank account) or the contact person's direct one?": the one place that question is asked. A port
 * so the decision is testable without a model; [ModelDetailOwnerQuestion] binds it to the loaded one. It returns scores only; the margin
 * and the choice belong to [DecideDetailOwnerUseCase].
 */
interface DetailOwnerQuestion {

    /** The score for "this value belongs to the organisation" and the made-up organisation's. An error when no model can answer. */
    suspend fun scoreOrganisation(question: DetailQuestion): PamResult<BaselineScores>

    /** The score for "this value is the contact person's direct one" and the made-up person's. */
    suspend fun scoreContact(question: DetailQuestion): PamResult<BaselineScores>
}

/**
 * How the question is decided, as data. [margin] is how far a statement must beat the made-up party's statement; 0.7 is the value of the
 * same-contact and concerned-people questions (the same model and the same shape of question; no recording of this one exists yet).
 * The made-up names must not be names that could be in a letter.
 */
data class DetailOwnerProfile(
    val margin: Double = 0.7,
    val maxExcerptChars: Int = 600,
    val baselineOrganisation: String = "Quillfeather Holdings",
    val baselineContact: String = "Zoltan Quillfeather",
)

/**
 * [DetailOwnerQuestion] over the standing [PromptSession] ([BaselineYesNo]): the organisation, the contact and the excerpt are the
 * prefix, and the value's statement and a made-up party's are scored by `logit(Yes) - logit(No)`. The wording is English; the letter may
 * be in any language.
 */
class ModelDetailOwnerQuestion @Inject constructor(
    private val session: PromptSession,
    private val framing: PromptFraming,
    private val profile: DetailOwnerProfile,
) : DetailOwnerQuestion {

    override suspend fun scoreOrganisation(question: DetailQuestion): PamResult<BaselineScores> =
        BaselineYesNo(session, framing).score(
            system = SYSTEM,
            user = user(question),
            statements = listOf(organisationStatement(question, question.organisation)),
            baselineStatement = organisationStatement(question, profile.baselineOrganisation),
            shared = "",
        )

    override suspend fun scoreContact(question: DetailQuestion): PamResult<BaselineScores> {
        val contact = question.contactName ?: return PamResult.Success(BaselineScores(listOf(Double.NEGATIVE_INFINITY), 0.0))
        return BaselineYesNo(session, framing).score(
            system = SYSTEM,
            user = user(question),
            statements = listOf(contactStatement(question, contact)),
            baselineStatement = contactStatement(question, profile.baselineContact),
            shared = "",
        )
    }

    private fun user(q: DetailQuestion): String = buildString {
        append("ORGANISATION THAT SENT THE LETTER: ").append(q.organisation)
        q.contactName?.let { append("\nCONTACT PERSON NAMED IN THE LETTER: ").append(it) }
        append("\nVALUE: ").append(q.value)
        q.excerpt?.takeIf { it.isNotBlank() }?.let { append("\nLETTER EXCERPT\n").append(it.take(profile.maxExcerptChars)) }
    }

    private fun organisationStatement(q: DetailQuestion, who: String): String = when (q.kind) {
        DetailKind.PHONE -> "Is «${q.value}» a general telephone number of «$who» (a switchboard, service line or hotline), not one person's direct line? Answer:"
        DetailKind.EMAIL -> "Is «${q.value}» a general e-mail address of «$who» (a central or service mailbox), not one person's own? Answer:"
        DetailKind.WEBSITE -> "Is «${q.value}» the website of «$who»? Answer:"
        DetailKind.IBAN -> "Is «${q.value}» a bank account of «$who», the account payments to them go to? Answer:"
    }

    private fun contactStatement(q: DetailQuestion, who: String): String = when (q.kind) {
        DetailKind.PHONE -> "Is «${q.value}» the direct telephone number of «$who»? Answer:"
        DetailKind.EMAIL -> "Is «${q.value}» the own e-mail address of «$who»? Answer:"
        DetailKind.WEBSITE, DetailKind.IBAN -> "Does «${q.value}» belong to «$who» personally? Answer:"
    }

    private companion object {
        const val SYSTEM = "You read a letter and decide whose a telephone number, e-mail address, website or bank account in it is. " +
            "Answer Yes only when the letter shows it."
    }
}

/** Binds the detail-owner question to the loaded model, with the way it is asked as data. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DetailOwnerModule {

    @Binds
    abstract fun bindDetailOwnerQuestion(impl: ModelDetailOwnerQuestion): DetailOwnerQuestion

    companion object {
        @Provides
        fun provideDetailOwnerProfile(): DetailOwnerProfile = DetailOwnerProfile()
    }
}

/**
 * Decides whose a value is. The model decides ([DetailOwnerQuestion]); the code only verifies: the organisation's statement and the
 * contact's each have to beat their made-up party by the margin, and when both do, the contact's wins for a phone number or an e-mail
 * address (a direct one is more specific than a general one). A website and a bank account are only ever the organisation's. No model to answer
 * is an error (nothing decided), never a guess.
 */
class DecideDetailOwnerUseCase @Inject constructor(
    private val question: DetailOwnerQuestion,
    private val profile: DetailOwnerProfile,
) {

    suspend operator fun invoke(candidate: DetailCandidate, organisation: String, contactName: String?, excerpt: String?): PamResult<DetailOwner> {
        val asked = DetailQuestion(candidate.kind, candidate.value, organisation, contactName, excerpt)
        val organisationBeats = when (val answer = question.scoreOrganisation(asked)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data.beating(profile.margin).isNotEmpty()
        }
        val contactCanOwn = contactName != null && (candidate.kind == DetailKind.PHONE || candidate.kind == DetailKind.EMAIL)
        val contactBeats = contactCanOwn && when (val answer = question.scoreContact(asked)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data.beating(profile.margin).isNotEmpty()
        }
        return PamResult.Success(
            when {
                contactBeats -> DetailOwner.CONTACT
                organisationBeats -> DetailOwner.ORGANISATION
                else -> DetailOwner.NEITHER
            },
        )
    }
}
