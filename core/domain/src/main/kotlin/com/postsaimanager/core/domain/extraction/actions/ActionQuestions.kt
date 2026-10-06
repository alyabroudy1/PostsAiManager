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

    /** Whether [question] is one of this file's. */
    fun isActionQuestion(question: String): Boolean = ASK_MARKER in question || BOUND_MARKER in question

    /** Whether the letter asks the reader to do anything at all (or gives a deadline or an option to use). */
    fun anything(): String =
        "Does this letter $ASK_MARKER do something, such as pay, reply, object, attend, send, sign or confirm, " +
            "or give the reader a deadline or an option to use? Answer:"

    /** Whether the letter asks the reader to do what [kind] says. */
    fun kind(kind: ActionKind): String = "Does this letter $ASK_MARKER ${kind.task}? Answer:"

    /** Whether the stored date [slot] is [ActionKind.dateMeaning] of [kind]. The stored value comes with its label: what the reading called it. */
    fun date(kind: ActionKind, slot: TicketSlot): String = "Is «${stored(slot)}» ${kind.dateMeaning}? Answer:"

    /** Whether the stored amount [slot] is [ActionKind.amountMeaning] of [kind]. */
    fun amount(kind: ActionKind, slot: TicketSlot): String = "Is «${stored(slot)}» ${kind.amountMeaning}? Answer:"

    private fun stored(slot: TicketSlot): String = "${slot.label}: ${slot.value.replace('\n', ' ').trim()}"
}
