package com.postsaimanager.feature.documents

import com.postsaimanager.core.domain.extraction.text.TitleComposer

/**
 * The words of a composed title, from the positional args `TitleComposer` stores (`[familyId, sender, subject]`).
 * Pure: the caller supplies the localised family label, so this is the one place the "family · sender · subject" rule lives
 * and it is testable without Android resources.
 */
object ComposedTitle {

    /**
     * @param familyLabel the words for a family id, or null for an id with no label (the id is then shown as plain words)
     * @return null when every slot is empty
     */
    fun render(args: List<String>, familyLabel: (String) -> String?): String? {
        val family = args.getOrNull(FAMILY).orEmpty().trim()
        val parts = listOf(
            family.takeIf { it.isNotEmpty() }?.let { familyLabel(it) ?: it.replace('_', ' ') }.orEmpty(),
            args.getOrNull(SENDER).orEmpty().trim(),
            args.getOrNull(SUBJECT).orEmpty().trim(),
        ).filter { it.isNotBlank() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(TitleComposer.SEPARATOR)
    }

    private const val FAMILY = 0
    private const val SENDER = 1
    private const val SUBJECT = 2
}

/** The sentences a template summary is put together from; each is one string resource with the parameters [params] documents. */
enum class SummaryPiece(val params: Int) {
    /** "%1$s" the family label alone. */
    INTRO(1),

    /** "%1$s from %2$s" (family, sender). */
    INTRO_FROM(2),

    /** "%1$s for %2$s" (family, addressee). */
    INTRO_FOR(2),

    /** "%1$s from %2$s for %3$s" (family, sender, addressee). */
    INTRO_FROM_FOR(3),

    /** "Amount %1$s, due %2$s." */
    AMOUNT_DUE(2),

    /** "Amount %1$s." */
    AMOUNT(1),

    /** "Due %1$s." */
    DUE(1),

    /** "About: %1$s." */
    SUBJECT(1),
}

/**
 * The words of a template summary, from the six positional args `SummaryFacts.templateArgs` stores:
 * `[familyId, sender, addressee, amount, dueDate, subject]`, `""` for a field the letter did not give.
 *
 * The sentences are chosen by which fields are present, so a missing field never leaves a gap or a dangling word; each
 * sentence is a string resource ([SummaryPiece]) formatted by the caller's [format]. Pure, like [ComposedTitle].
 */
object TemplateSummary {

    /**
     * @param familyLabel the localised label of a family id (null: show the id as words)
     * @param format renders a [SummaryPiece] with its parameters from the string resource of that piece
     * @return null when the args hold nothing to say
     */
    fun render(args: List<String>, familyLabel: (String) -> String?, format: (SummaryPiece, List<String>) -> String): String? {
        fun arg(i: Int) = args.getOrNull(i).orEmpty().trim()
        val family = arg(FAMILY).takeIf { it.isNotEmpty() }?.let { familyLabel(it) ?: it.replace('_', ' ') }
        val sender = arg(SENDER)
        val addressee = arg(ADDRESSEE)
        val amount = arg(AMOUNT)
        val due = arg(DUE)
        val subject = arg(SUBJECT)

        val sentences = buildList {
            when {
                family != null && sender.isNotEmpty() && addressee.isNotEmpty() -> add(format(SummaryPiece.INTRO_FROM_FOR, listOf(family, sender, addressee)))
                family != null && sender.isNotEmpty() -> add(format(SummaryPiece.INTRO_FROM, listOf(family, sender)))
                family != null && addressee.isNotEmpty() -> add(format(SummaryPiece.INTRO_FOR, listOf(family, addressee)))
                family != null -> add(format(SummaryPiece.INTRO, listOf(family)))
            }
            when {
                amount.isNotEmpty() && due.isNotEmpty() -> add(format(SummaryPiece.AMOUNT_DUE, listOf(amount, due)))
                amount.isNotEmpty() -> add(format(SummaryPiece.AMOUNT, listOf(amount)))
                due.isNotEmpty() -> add(format(SummaryPiece.DUE, listOf(due)))
            }
            if (subject.isNotEmpty()) add(format(SummaryPiece.SUBJECT, listOf(subject)))
        }
        return sentences.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private const val FAMILY = 0
    private const val SENDER = 1
    private const val ADDRESSEE = 2
    private const val AMOUNT = 3
    private const val DUE = 4
    private const val SUBJECT = 5
}
