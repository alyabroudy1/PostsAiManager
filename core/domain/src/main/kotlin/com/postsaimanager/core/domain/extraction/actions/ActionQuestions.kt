package com.postsaimanager.core.domain.extraction.actions

import com.postsaimanager.core.model.TicketSlot

/**
 * The yes/no questions the action kinds are scored with, asked in the letter's own session (the letter is the prefix, so a question is
 * only its own few words). English on purpose: the letter may be in any language. Pure text.
 *
 * Every question of this file contains [ASK_MARKER] or [BOUND_MARKER], so a recording or a replay can tell them from the questions of
 * the reading itself.
 */
object ActionQuestions {

    const val ASK_MARKER = "ask the reader to"
    const val BOUND_MARKER = "the reader is asked to"

    const val DONE_MARKER = "already been completed"

    /** Whether [question] is one of this file's. */
    fun isActionQuestion(question: String): Boolean = ASK_MARKER in question || BOUND_MARKER in question || DONE_MARKER in question

    /**
     * The gate: whether the letter asks the reader to do anything at all (or gives a deadline or an option to use). [description] is what
     * the reading itself decided the document is (the chosen family's description): the model judges the question knowing what it
     * concluded, and code writes nothing of its own about any kind of document. Null or blank: no context.
     */
    fun anything(description: String? = null): String =
        (description?.trim()?.takeIf { it.isNotEmpty() }?.let { "This document is: $it. " } ?: "") +
            "Does this letter $ASK_MARKER do something, such as pay, reply, object, attend, send, sign or confirm, " +
            "or give the reader a deadline or an option to use? Answer:"

    /** The second gate: whether what the document is about is finished already (paid, done), so nothing is left to do. */
    fun done(): String = "Has whatever this document is about $DONE_MARKER, for example already paid or already done by the reader? Answer:"

    /** Whether the letter asks the reader to do what [kind] says. */
    fun kind(kind: ActionKind): String = "Does this letter $ASK_MARKER ${kind.task}? Answer:"

    /** Whether the stored date [slot] is [ActionKind.dateMeaning] of [kind]. The stored value comes with its label: what the reading called it. */
    fun date(kind: ActionKind, slot: TicketSlot): String = "Is «${stored(slot)}» ${kind.dateMeaning}? Answer:"

    /** Whether the stored amount [slot] is [ActionKind.amountMeaning] of [kind]. */
    fun amount(kind: ActionKind, slot: TicketSlot): String = "Is «${stored(slot)}» ${kind.amountMeaning}? Answer:"

    private fun stored(slot: TicketSlot): String = "${slot.label}: ${slot.value.replace('\n', ' ').trim()}"
}
