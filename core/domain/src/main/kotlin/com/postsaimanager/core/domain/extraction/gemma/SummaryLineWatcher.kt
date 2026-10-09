package com.postsaimanager.core.domain.extraction.gemma

/**
 * Finds the summary in the answer while it is still being written: the answer of the "Questions" reader starts with a `SUMMARY:` line,
 * and the document can show it the moment that line ends, seconds before the rest is decoded.
 *
 * Fed the answer so far (the engine hands it over whenever a line is complete), it answers the summary once, the first time a
 * `SUMMARY:` line is followed by a line break. Nothing is checked here (the summary gate does that downstream); a summary line the
 * model wrapped onto the next line is cut at its first line, which the prompt asks to be the whole summary.
 */
class SummaryLineWatcher {

    private var delivered = false

    /** The summary text when [answerSoFar] holds a finished `SUMMARY:` line that was not handed out before; else null. */
    fun feed(answerSoFar: String): String? {
        if (delivered) return null
        val summary = summaryIn(answerSoFar, complete = true) ?: return null
        delivered = true
        return summary
    }

    /** True once a summary was handed out by [feed]. */
    val hasDelivered: Boolean get() = delivered

    /** The summary of the final [answer], for a run whose stream never showed a finished line (nothing handed out yet); else null. */
    fun finish(answer: String): String? {
        if (delivered) return null
        val summary = summaryIn(answer, complete = false) ?: return null
        delivered = true
        return summary
    }

    private fun summaryIn(text: String, complete: Boolean): String? {
        val lines = text.split('\n')
        // The last piece has no line break after it yet: only a final answer may use it.
        val usable = if (complete) lines.dropLast(1) else lines
        return usable.firstNotNullOfOrNull { line ->
            QuestionAnswerParser.labelled(line)?.takeIf { it.first == QaLabel.SUMMARY }?.second?.trim('*', ' ')?.takeIf { !QaText.isNone(it) }
        }
    }
}
