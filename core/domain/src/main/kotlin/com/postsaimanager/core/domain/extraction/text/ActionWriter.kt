package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.v2.AnswerReader
import com.postsaimanager.core.domain.extraction.v2.QuestionGrammars
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier

/**
 * Writes the action lines: what the reader has to do and by when, at most [MAX_LINES] short lines in the letter's language. The model
 * decides what the actions are (guided by the family's hint, which says what matters in this kind of document) and may answer that there
 * are none; code only verifies. Nothing about a kind of letter or a language is written here, and no sentence is a template.
 *
 * Every line goes through [SummaryGate], the summary's own check: each number, date and name in it must occur in the letter's text or in
 * the verified facts, and a line that copies one line of the letter is refused. A refused line is dropped alone. At most [MAX_ASKS] asks
 * (the second one only when the first gave no line and did not say there is nothing to do, with an instruction not to copy the letter).
 *
 * Runs on the session the reading already opened, so the letter is the prefix and is not read again.
 */
class ActionWriter(
    private val session: PromptSession,
    private val gate: SummaryGate = SummaryGate(),
    /** Structure only (counts and the gate's reason names, never a word of the letter or of an answer), for the reading's trace. */
    private val trace: (String) -> Unit = {},
) {

    /**
     * @param facts the verified (label, value) pairs the lines may rest on: the summary's facts and the key information picked
     * @param hint the family's guidance for the model; null or blank when it has none
     * @param ocrText the letter's text, the gate's reference
     * @param languageCode the document's language as a BCP-47 code, or null when unknown
     * @return the accepted lines (empty when the letter asks nothing or no line passed), or null when the engine failed every ask
     */
    suspend fun write(facts: List<Pair<String, String>>, hint: String?, ocrText: String, languageCode: String?): List<String>? {
        val values = facts.map { it.second }
        var answered = false
        for (attempt in 0 until MAX_ASKS) {
            val r = session.ask(prompt(facts, hint, languageCode, antiCopy = attempt > 0), QuestionGrammars.actionLines(), ACTION_TOKENS)
            val answer = (r as? PamResult.Success)?.data ?: continue
            answered = true
            if (isNone(answer.trim())) {
                trace("actions ask=${attempt + 1} answer=none")
                return emptyList()
            }
            val quoted = AnswerReader.lines(answer)
            // The grammar lets the sentinel be written as a quoted line ("NONE"): that is still "nothing to do", never an action.
            if (quoted.isNotEmpty() && quoted.all(::isNone)) {
                trace("actions ask=${attempt + 1} answer=none")
                return emptyList()
            }
            val kept = accepted(quoted.filterNot(::isNone), ocrText, values, attempt + 1)
            if (kept.isNotEmpty()) return kept
        }
        return if (answered) emptyList() else null
    }

    /** The no-action sentinel in any case, with or without quotes or punctuation around it. */
    private fun isNone(text: String): Boolean = text.trim { !it.isLetterOrDigit() }.equals(NONE_ANSWER, ignoreCase = true)

    private fun accepted(lines: List<String>, ocrText: String, values: List<String>, ask: Int): List<String> {
        val kept = ArrayList<String>()
        val refused = ArrayList<String>()
        for (line in lines) {
            val text = line.trim().replace(WHITESPACE, " ")
            // The ask says MAX_WORDS; a model that runs a few words over with a line the gate would accept is not refused for it (a
            // car-insurance letter's only action was dropped for being 27 words), only a runaway is.
            if (text.split(' ').size > ACCEPT_WORDS) { refused += "TOO_LONG"; continue }
            val verdict = gate.check(text, ocrText, values)
            if (verdict !is SummaryGate.Verdict.Accepted) { refused += (verdict as SummaryGate.Verdict.Rejected).reason.name; continue }
            if (kept.any { QuoteVerifier.fold(it) == QuoteVerifier.fold(text) }) continue
            kept += text
            if (kept.size == MAX_LINES) break
        }
        trace("actions ask=$ask lines=${lines.size} kept=${kept.size} refused=$refused")
        return kept
    }

    internal fun prompt(facts: List<Pair<String, String>>, hint: String?, languageCode: String?, antiCopy: Boolean): String = buildString {
        append("FACTS (verified; use only these and the letter):\n")
        facts.forEach { (label, value) -> append("- ").append(label).append(": ").append(value).append('\n') }
        append("\nQUESTION: What must or may the reader do, and by when? An option the letter offers with a deadline (to object, to cancel, ")
        append("to reply) counts, even when doing nothing is also allowed. Write at most ").append(MAX_LINES).append(" short lines, one action each, at most ")
        append(MAX_WORDS).append(" words per line, using only the facts above and the letter. ")
        hint?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append(' ') }
        append(languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { "Write in the language with the code \"$it\"." } ?: "Write in the letter's own language.")
        append(" If the letter asks nothing and offers no option or deadline, answer ").append(NONE_ANSWER).append('.')
        if (antiCopy) append(' ').append(ANTI_COPY)
        append("\nANSWER FORMAT: ").append(NONE_ANSWER).append(", or one to ").append(MAX_LINES).append(" lines, each in double quotes, separated by spaces")
    }

    companion object {
        const val MAX_ASKS = 2
        const val MAX_LINES = 3
        const val MAX_WORDS = 25

        /** The longest line kept: a little over what the ask says, under the gate's own limit. */
        const val ACCEPT_WORDS = 40

        /** Enough for three lines of [MAX_WORDS] words in any of the supported scripts. */
        const val ACTION_TOKENS = 160

        private const val NONE_ANSWER = "NONE"
        private const val ANTI_COPY = "Do not copy any line of the letter; put it in your own words."
        private val WHITESPACE = Regex("\\s+")
    }
}
