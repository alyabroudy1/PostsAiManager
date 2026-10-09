package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.ai.SamplingPurpose

/**
 * Whether the "Questions" reader shows the model the page picture. On the CPU the vision encoder and the picture's tokens cost seconds,
 * and a letter whose text the OCR read well gains nothing from them: the picture goes along only when the text is weak. A mechanical
 * threshold on what the OCR returned, never a judgement about the letter's content.
 *
 * Weak is: few lines or few characters (a photo of an object, a screenshot, a page the OCR barely read), or a large share of the lines
 * dropped for low recognition confidence. A letter with no text at all never gets here (the JSON reader takes it with the picture).
 */
object QaImageDecision {

    /** A page of a letter has dozens of lines; fewer than this is a note, a screenshot or a failed read. */
    const val MIN_LINES = 12

    const val MIN_CHARS = 300

    /** More than this share of the recognised lines dropped as unreliable: the text is not to be trusted alone. */
    const val MAX_UNREADABLE_SHARE = 0.25

    class Decision(val send: Boolean, val reason: String)

    fun decide(letter: GemmaLetter, alwaysSend: Boolean): Decision {
        val lines = letter.lines.size
        val chars = letter.lines.sumOf { it.text.length }
        val unreadable = letter.unreadableLines
        val share = if (lines + unreadable == 0) 0.0 else unreadable.toDouble() / (lines + unreadable)
        return when {
            alwaysSend -> Decision(true, "debug override: always send the image")
            lines < MIN_LINES -> Decision(true, "weak text: $lines lines (under $MIN_LINES)")
            chars < MIN_CHARS -> Decision(true, "weak text: $chars chars (under $MIN_CHARS)")
            share > MAX_UNREADABLE_SHARE -> Decision(true, "weak text: $unreadable of ${lines + unreadable} lines unreadable")
            else -> Decision(false, "text is strong: $lines lines, $chars chars, $unreadable unreadable")
        }
    }
}

/**
 * How the "Questions" reader samples: greedy decoding (the constrained readers' sampling) on every backend, so the same letter gives the
 * same answer (a drawn answer drifted on the GPU and read the same letter differently on the CPU). Only this reading: the chat keeps
 * its own sampling.
 */
object QaSampling {

    val PURPOSE = SamplingPurpose.STRUCTURED
}
