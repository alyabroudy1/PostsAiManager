package com.postsaimanager.core.domain.form

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.form.fill.FormFillTrace
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.OcrBlock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * A document's stored OCR (`document_pages.ocrBlocks`, one list per page in page order) and who the form may be for.
 *
 * @property subjects the managed profiles, the likeliest first (at most [FormScoringProfile.maxSubjects] are scored); may be empty.
 */
data class UnderstandFormRequest(
    val documentId: String,
    val formFillId: String,
    val pages: List<List<OcrBlock>>,
    val subjects: List<SubjectCandidate>,
    val today: LocalDate,
    val nowMs: Long,
    val fallbackLocale: Locale = Locale.getDefault(),
)

enum class FormStep { FIND, CONFIRM, CLASSIFY, ROLES, SUBJECT }

/** [done] of [total] steps are finished; [step] is the one that just finished. */
data class FormProgress(val step: FormStep, val done: Int, val total: Int = FormStep.entries.size)

/**
 * What the form is, ready for filling.
 *
 * @property fields in page and reading order, with key, role and confidence set where the model could tell; no value yet.
 * @property sectionRoles the role of each section (null when unclear).
 * @property subjectRanking the managed profiles, likeliest first, the best with its quoted reason line.
 * @property locale the form's language (from the OCR's language tags).
 * @property scoresSpent how many model scores the understanding cost.
 */
data class FormUnderstanding(
    val fields: List<FormField>,
    val sectionRoles: List<SectionRole>,
    val subjectRanking: List<SubjectSuggestion>,
    val locale: Locale,
    val scoresSpent: Int,
)

/**
 * Understands a scanned form on demand: finds the blanks ([FindFillableFields]), keeps the real ones ([ConfirmFields]), says what
 * each asks for ([ClassifyFields]), whose data it is ([AssignRoles]) and who the form is for ([SuggestSubject]). The model reads the
 * form once (a short prefix: its first lines); every question is a label-free yes/no score. It never produces a value.
 *
 * Scoring budget (typical 2-page, 20-field form, about 130 scores; the caps are in [FormScoringProfile]): confirm about 6 (weak
 * candidates only), classify 4 per field (3 keys + none), roles 6 per section and 6 per bank/signature field, subject one per
 * profile plus up to 6 reason lines.
 */
class UnderstandFormUseCase(
    private val session: PromptSession,
    private val framing: PromptFraming,
    private val embedder: EmbeddingService,
    private val profile: FormScoringProfile = FormScoringProfile(),
    private val finder: FindFillableFields = FindFillableFields(),
    private val trace: FormFillTrace = FormFillTrace.NONE,
) {

    suspend operator fun invoke(request: UnderstandFormRequest, onProgress: (FormProgress) -> Unit = {}): PamResult<FormUnderstanding> {
        val lines = FindFillableFields.pageLines(request.pages)
        val intro = introLines(lines)
        val (head, tail) = framing.frame(SYSTEM, "FORM\n" + intro.joinToString("\n").take(INTRO_CHARS))
        when (val opened = session.open(head)) {
            is PamResult.Error -> return opened
            is PamResult.Success -> Unit
        }
        val scorer = FormScorer(session, tail)
        return try {
            PamResult.Success(understand(request, lines, intro, scorer, onProgress))
        } catch (e: FormScoringException) {
            PamResult.Error(e.error)
        } finally {
            // Also when the user left the chat: the engine's session must be dropped even from a cancelled coroutine.
            withContext(NonCancellable) { session.close() }
        }
    }

    private suspend fun understand(
        request: UnderstandFormRequest,
        lines: List<List<LayoutLine>>,
        intro: List<String>,
        scorer: FormScorer,
        onProgress: (FormProgress) -> Unit,
    ): FormUnderstanding {
        fun step(s: FormStep) = onProgress(FormProgress(s, s.ordinal + 1))

        val found = finder.findInLines(lines)
        step(FormStep.FIND)
        val candidates = ConfirmFields(scorer, profile).confirm(found).mapIndexed { i, c -> c.copy(orderIndex = i) }
        trace.event("confirm", "found=${found.size} kept=${candidates.size} strong=${candidates.count { it.strong }}")
        step(FormStep.CONFIRM)
        val keys = ClassifyFields(scorer, embedder, profile, trace = trace).classify(candidates)
        step(FormStep.CLASSIFY)
        val roles = AssignRoles(scorer, profile).assign(candidates.mapIndexed { i, c -> RoleInput(c.labelText, c.section, keys[i].dataKey) })
        trace.event("roles", "sections=${roles.sections.size} withRole=${roles.fieldRoles.count { it != null }}")
        step(FormStep.ROLES)
        val ranking = SuggestSubject(scorer, profile).suggest(intro, request.subjects, request.today)
        step(FormStep.SUBJECT)

        val fields = candidates.mapIndexed { i, c ->
            FormField(
                id = UUID.nameUUIDFromBytes("${request.formFillId}|${c.page}|${c.orderIndex}|${c.labelText}".toByteArray()).toString(),
                formFillId = request.formFillId,
                documentId = request.documentId,
                page = c.page,
                labelText = c.labelText,
                labelBox = c.labelBox,
                fillBox = c.fillBox,
                kind = keys[i].kind,
                section = c.section,
                options = c.options,
                dataKey = keys[i].dataKey,
                role = roles.fieldRoles[i],
                confidence = keys[i].confidence,
                alreadyFilled = c.alreadyFilled,
                orderIndex = c.orderIndex,
                updatedAt = request.nowMs,
            )
        }
        return FormUnderstanding(fields, roles.sections, ranking, FormLocales.detect(request.pages, request.fallbackLocale), scorer.scoreCount)
    }

    /** The first rows of page 1 in reading order: the form's title and introduction. */
    private fun introLines(lines: List<List<LayoutLine>>): List<String> {
        val first = lines.firstOrNull() ?: return emptyList()
        return FormPage.build(1, first).rows.map { r -> r.toks.joinToString(" ") { it.text } }.filter { it.isNotBlank() }.take(INTRO_LINES)
    }

    private companion object {
        const val INTRO_LINES = 14
        const val INTRO_CHARS = 900
        const val SYSTEM = "You read one scanned form that a person has to fill in. The form can be in any language. " +
            "For each question, answer Yes if the statement is true for the form, otherwise No. " +
            "Answer with the single word Yes or No."
    }
}
