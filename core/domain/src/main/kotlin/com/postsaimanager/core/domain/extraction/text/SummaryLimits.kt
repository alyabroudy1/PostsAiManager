package com.postsaimanager.core.domain.extraction.text

/**
 * The one owner of "how long may a summary be": the prompt asks for it, the schema bounds it, [SummaryGate] holds it and the
 * reader's token budget is derived from it, so the three can never drift apart (they did: a 160-character ask met a 45-word gate).
 */
object SummaryLimits {

    /** The most characters a stored summary has: asked for in the prompt, bounded in the schema, enforced (by trimming) in the gate. */
    const val MAX_CHARS = 200

    /** Roughly the tokens [MAX_CHARS] take in the widest scripts (about 2.5 characters a token), with room for one more sentence ending. */
    const val MAX_TOKENS = MAX_CHARS * 2 / 5 + 20

    /** An answer longer than this many times [MAX_CHARS] is a runaway, not a summary that is a little long: it is refused, not trimmed. */
    const val RUNAWAY_FACTOR = 3
}
