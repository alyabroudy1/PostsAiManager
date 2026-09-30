package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.v2.AnswerReader
import com.postsaimanager.core.domain.extraction.v2.QuestionGrammars
import com.postsaimanager.core.domain.extraction.v2.QuestionnairePrompt

/**
 * Writes the summary: at most [MAX_ASKS] asks, and a summary always exists.
 *
 * Plan-then-write. The prompt first lists the verified FACTS (the plan), then asks for one or two sentences of at most
 * [MAX_WORDS] words in the document's language, using only those facts. [SummaryGate] checks the answer; a rejected answer
 * is asked again once with an instruction not to copy the letter; a second rejection (or an engine failure) falls back to the
 * template summary, which the UI renders from the verified fields.
 *
 * Runs on the session the reading already opened, so the letter is the prefix and is not read again.
 */
class SummaryWriter(
    private val session: PromptSession,
    private val gate: SummaryGate = SummaryGate(),
) {

    /**
     * @param languageCode the document's language as a BCP-47 code (`de`, `en`, `ar`), or null when unknown
     * @param ocrText the letter's text, the gate's reference
     */
    suspend fun write(facts: SummaryFacts, ocrText: String, languageCode: String?): SummaryResult {
        for (attempt in 0 until MAX_ASKS) {
            val answer = when (val r = session.ask(prompt(facts, languageCode, antiCopy = attempt > 0), QuestionGrammars.line(), QuestionnairePrompt.SUMMARY_TOKENS)) {
                is PamResult.Success -> AnswerReader.line(r.data)
                is PamResult.Error -> null
            }
            if (answer == null) continue
            val verdict = gate.check(answer, ocrText, facts.values())
            if (verdict is SummaryGate.Verdict.Accepted) return SummaryResult(verdict.text, SummaryOrigin.MODEL, null, emptyList())
        }
        return template(facts)
    }

    /** The summary rendered from the verified fields; nothing was asked. */
    fun template(facts: SummaryFacts): SummaryResult =
        SummaryResult(null, SummaryOrigin.TEMPLATE, TEMPLATE_CODE, facts.templateArgs())

    internal fun prompt(facts: SummaryFacts, languageCode: String?, antiCopy: Boolean): String = buildString {
        append("FACTS (verified; use only these):\n")
        facts.entries().forEach { (role, value) -> append("- ").append(role).append(": ").append(value).append('\n') }
        append("\nQUESTION: Write one or two sentences, at most ").append(MAX_WORDS).append(" words, saying what the reader must know or do. ")
        append("Use only the facts above. ")
        append(languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { "Write in the language with the code \"$it\"." } ?: "Write it in the letter's own language.")
        if (antiCopy) append(' ').append(ANTI_COPY)
        append("\nANSWER FORMAT: one or two sentences in double quotes")
    }

    companion object {
        /** The `summaryCode` of a summary rendered from the verified fields. */
        const val TEMPLATE_CODE = "template"

        const val MAX_ASKS = 2
        const val MAX_WORDS = 30

        private const val ANTI_COPY = "Do not copy any line of the letter; put it in your own words."
    }
}
