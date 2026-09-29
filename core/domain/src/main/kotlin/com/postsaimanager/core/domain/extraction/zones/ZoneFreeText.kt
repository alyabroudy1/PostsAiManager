package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.v2.AnswerReader
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.Question
import com.postsaimanager.core.domain.extraction.v2.QuestionnairePrompt
import com.postsaimanager.core.domain.extraction.v2.RawText
import com.postsaimanager.core.domain.extraction.v2.TextGrammar
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest

/**
 * The free text (title, subject line, summary, suggested questions) is written the same way by both zone
 * interpreters, from the open body session: the body text is already the prefix, so nothing is read twice.
 * [ask] answers one question and returns null when the engine failed it.
 */
internal object ZoneFreeText {

    suspend fun write(request: TextRequest, ask: suspend (Question) -> String?): TextOutcome {
        val other = if (request.documentTypeId == ExtractionSchema.OTHER.id) AnswerReader.line(ask(QuestionnairePrompt.otherLabel()).orEmpty()) else null
        val title = AnswerReader.line(ask(QuestionnairePrompt.title(request.documentTypeId)).orEmpty())
        val subject = AnswerReader.line(ask(QuestionnairePrompt.subjectLine()).orEmpty())
        val summary = AnswerReader.line(ask(QuestionnairePrompt.summary()).orEmpty())
        val questions = AnswerReader.lines(ask(QuestionnairePrompt.suggestedQuestions()).orEmpty())
        if (title == null && subject == null && summary == null && questions.isEmpty()) return TextOutcome.Failed("the model wrote no text")
        val text = RawText(
            otherLabel = other?.take(TextGrammar.MAX_OTHER_CHARS),
            title = title?.take(TextGrammar.MAX_TITLE_CHARS),
            subject = subject?.take(TextGrammar.MAX_SUBJECT_CHARS),
            summary = summary?.take(TextGrammar.MAX_SUMMARY_CHARS),
            questions = questions.map { it.take(TextGrammar.MAX_QUESTION_CHARS) }.take(TextGrammar.MAX_QUESTIONS),
        )
        return TextOutcome.Written(text, "")
    }
}
