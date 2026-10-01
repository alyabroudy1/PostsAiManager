package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.v2.AnswerReader
import com.postsaimanager.core.domain.extraction.v2.Question
import com.postsaimanager.core.domain.extraction.v2.QuestionnairePrompt
import com.postsaimanager.core.domain.extraction.v2.RawText
import com.postsaimanager.core.domain.extraction.v2.TextGrammar
import com.postsaimanager.core.domain.extraction.v2.TextOutcome

/**
 * The free text (subject line, summary, suggested questions) is written the same way by both zone interpreters, from the open
 * body session: the body text is already the prefix, so nothing is read twice. [ask] answers one question and returns null when the
 * engine failed it. There is no title and no name for an unlisted kind of document: the title is composed from verified fields
 * (`TitleComposer`).
 */
internal object ZoneFreeText {

    /**
     * @param includeSummary false when the summary is written by `SummaryWriter` instead (the scoring interpreter's second stage).
     */
    suspend fun write(includeSummary: Boolean = true, ask: suspend (Question) -> String?): TextOutcome {
        val subject = AnswerReader.line(ask(QuestionnairePrompt.subjectLine()).orEmpty())
        val summary = if (includeSummary) AnswerReader.line(ask(QuestionnairePrompt.summary()).orEmpty()) else null
        val questions = AnswerReader.lines(ask(QuestionnairePrompt.suggestedQuestions()).orEmpty())
        if (subject == null && summary == null && questions.isEmpty()) return TextOutcome.Failed("the model wrote no text")
        val text = RawText(
            otherLabel = null,
            title = null,
            subject = subject?.take(TextGrammar.MAX_SUBJECT_CHARS),
            summary = summary?.take(TextGrammar.MAX_SUMMARY_CHARS),
            questions = questions.map { it.take(TextGrammar.MAX_QUESTION_CHARS) }.take(TextGrammar.MAX_QUESTIONS),
        )
        return TextOutcome.Written(text, "")
    }
}
