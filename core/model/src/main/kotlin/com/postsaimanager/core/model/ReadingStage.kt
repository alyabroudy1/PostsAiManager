package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * How far the reading of a NEW letter (a scan, an import, or a re-read a person asked for) has got, so every screen can show what is
 * already known instead of waiting for the whole reading. Stored as its `name` on the document (`readingStage`), so adding a value is
 * additive.
 *
 * The order is the order of the pipeline. A stage only moves forward within one reading; a new reading starts again from null.
 * Null is also what a letter read before this existed, and a letter that a quiet background re-read (an extractor version bump)
 * updates, carries: both read as finished, and neither is ever announced.
 *
 * Who the letter is for (the people check) is not a stage of its own: it runs in the background beside the second stage and is stored
 * as `Document.concernedProfileIds` (null until it ran).
 */
@Serializable
enum class ReadingStage {
    /** The pages were read (OCR) and their text is stored: it can be selected and searched. */
    TEXT_READY,

    /** The first model stage is stored: the parties, the dates, the amounts and the references. The second stage is still owed. */
    FIELDS_READY,

    /** The second stage is stored too: the type, the name, the key information, the summary, the actions and the events. */
    UNDERSTOOD,
    ;

    companion object {
        /** The stage a stored name stands for; null for null and for a name this build does not know. */
        fun parse(name: String?): ReadingStage? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The step of an unfinished reading a document list names ("Reading text…", "Reading details…", "Almost done…"). No percentages: the
 * model's time is unbounded, so only the step is honest.
 */
enum class ReadingStep { READING_TEXT, READING_DETAILS, ALMOST_DONE }
