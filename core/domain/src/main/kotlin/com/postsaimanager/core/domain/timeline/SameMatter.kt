package com.postsaimanager.core.domain.timeline

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
import java.time.Instant
import javax.inject.Inject

/** An existing matter of the organisation, as plain values: its title and its latest events, newest first, each as one short line. */
data class MatterCandidate(val id: String, val title: String, val latestEvents: List<String> = emptyList())

/** What the new letter is: the kind of event it reports (English label), the day it happened and its title. */
data class NewMatterEvent(val kindLabel: String, val eventDate: Long, val title: String)

/** What is asked once: the new event and the (already narrowed) matters, in the order they are scored. */
data class SameMatterQuestion(val organisation: String, val event: NewMatterEvent, val candidates: List<MatterCandidate>)

/**
 * The model's reading of "is this new letter part of the same matter as one of these?": the one place that question is asked. A port so the
 * decision is testable without a model; [ModelSameMatter] binds it to the loaded one. It returns scores only: the margin and the choice
 * belong to [DecideSameMatterUseCase].
 */
interface SameMatter {

    /** One score per [SameMatterQuestion.candidates] entry (same order) and the made-up distractor's. An error when no model can answer. */
    suspend fun score(question: SameMatterQuestion): PamResult<BaselineScores>
}

/**
 * How the question is decided, as data.
 *
 * @property margin a matter counts only when its score beats the made-up distractor's by at least this much. 0.7 is the value the
 *   same-contact question ([com.postsaimanager.core.domain.document.contacts.SameContactProfile]) uses, which has the same shape; no
 *   recording of this question exists yet, so it is unfitted.
 * @property baselineTitle the made-up matter scored beside the candidates (it must not be a matter that could exist).
 * @property maxCandidates the most recent matters asked about; the model's context is small.
 */
data class SameMatterProfile(
    val margin: Double = DEFAULT_MARGIN,
    val maxCandidates: Int = 6,
    val maxEventsPerMatter: Int = 3,
    val baselineTitle: String = "Zoltan Quillfeather's registration",
) {
    companion object {
        const val DEFAULT_MARGIN = 0.7
    }
}

/** The decision: [matchedId] is the existing matter the letter belongs to, or null for a new one. [asked] and [baseline] are for the debug log. */
data class SameMatterDecision(
    val matchedId: String?,
    val asked: List<Pair<String, Double>>,
    val baseline: Double?,
    val margin: Double,
)

/**
 * Decides whether a letter with no reference in common with a matter is nevertheless part of one of the organisation's matters. The model
 * decides ([SameMatter]); code only verifies: each matter (the most recent ones) is scored against a made-up distractor, and one wins only
 * when it beats the distractor by the margin, the best one winning. Nothing to ask, or nobody beating the margin, is a new matter. An
 * error (nothing decided) when no model can answer.
 */
class DecideSameMatterUseCase @Inject constructor(
    private val sameMatter: SameMatter,
    private val profile: SameMatterProfile,
) {

    suspend operator fun invoke(
        organisation: String,
        event: NewMatterEvent,
        candidates: List<MatterCandidate>,
    ): PamResult<SameMatterDecision> {
        val asked = candidates.take(profile.maxCandidates)
        if (asked.isEmpty()) return PamResult.Success(SameMatterDecision(null, emptyList(), null, profile.margin))
        val scores = when (val answer = sameMatter.score(SameMatterQuestion(organisation, event, asked))) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data
        }
        val best = scores.beating(profile.margin).maxByOrNull { scores.candidates[it] }
        return PamResult.Success(
            SameMatterDecision(
                matchedId = best?.let { asked[it].id },
                asked = asked.mapIndexed { i, c -> c.id to scores.candidates[i] },
                baseline = scores.baseline,
                margin = profile.margin,
            ),
        )
    }
}

/**
 * [SameMatter] over the standing [PromptSession]: the organisation and the new event are the prefix (decoded once), the matters are listed
 * as shared context (title and latest events), and each matter, and a made-up one, is scored separately by `logit(Yes) - logit(No)`
 * ([BaselineYesNo]). The wording is English; the letter and the titles may be in any language.
 */
class ModelSameMatter @Inject constructor(
    private val session: PromptSession,
    private val framing: PromptFraming,
    private val profile: SameMatterProfile,
) : SameMatter {

    override suspend fun score(question: SameMatterQuestion): PamResult<BaselineScores> {
        val listed = question.candidates.take(profile.maxCandidates)
        return BaselineYesNo(session, framing).score(
            system = SYSTEM,
            user = user(question),
            statements = listed.map { statement(it.title) },
            baselineStatement = statement(profile.baselineTitle),
            shared = shared(listed),
        )
    }

    private fun user(q: SameMatterQuestion): String = buildString {
        append("ORGANISATION: ").append(q.organisation)
        append("\nNEW LETTER: ").append(q.event.kindLabel).append(", ").append(date(q.event.eventDate)).append(": ").append(q.event.title)
    }

    private fun shared(listed: List<MatterCandidate>): String = buildString {
        append("\n\nMatters of this organisation so far:")
        listed.forEachIndexed { i, c ->
            append("\nM${i + 1}: ").append(c.title)
            val events = c.latestEvents.take(profile.maxEventsPerMatter)
            if (events.isNotEmpty()) append(" (").append(events.joinToString("; ")).append(')')
        }
    }

    private fun statement(title: String): String = "Is the new letter part of the same matter as «$title»? Answer:"

    private fun date(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString().take(DATE_CHARS)

    private companion object {
        const val SYSTEM = "You read a list of letters from one organisation and answer questions about them. Say two letters are the same matter only when they are about the same application, claim, contract or case."
        const val DATE_CHARS = 10
    }
}

/** Binds the same-matter question to the loaded model, with the way it is asked as data. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SameMatterModule {

    @Binds
    abstract fun bindSameMatter(impl: ModelSameMatter): SameMatter

    companion object {
        @Provides
        fun provideSameMatterProfile(): SameMatterProfile = SameMatterProfile()
    }
}
