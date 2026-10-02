package com.postsaimanager.core.domain.extraction.text

/**
 * Builds a document's title from fields that are already verified. No model call: the title costs nothing.
 *
 * A composed title is a **coded title**: `Document.titleCode = "composed"` ([CODE]) with `Document.titleArgs` of
 * exactly three positions, always present:
 *
 * | index | content                                                    |
 * |-------|------------------------------------------------------------|
 * | 0     | the family id (`invoice_bill`, `free_form`, ...)             |
 * | 1     | the sender's name, or `""` when there is none                |
 * | 2     | the subject line, or `""` when there is none                 |
 *
 * The positions are fixed so that a dropped slot does not shift the next one. The UI (P3) renders the localised
 * "{family label} · {sender} · {subject}" from these args and leaves out every empty slot; [Composed.title] is the same
 * text built with a fallback family label, kept for places that cannot resolve resources (search, file names).
 *
 * The code is one a newer extractor may re-compose (`DocumentTitlePolicy` replaces any coded title unless a person chose
 * it), while a legacy title in real words (`titleCode == null`) is never touched.
 */
object TitleComposer {

    /** The `Document.titleCode` of a composed title. */
    const val CODE = "composed"

    /** Between the slots of the plain [Composed.title]. */
    const val SEPARATOR = " · "

    const val MAX_SENDER_CHARS = 60
    const val MAX_SUBJECT_CHARS = 80

    /** A title as a code with its args, and the same title as plain text. */
    data class Composed(val title: String, val code: String, val args: List<String>)

    /**
     * @param familyLabel the words for a family id; the default turns the id into words (`invoice_bill` -> "Invoice bill"),
     * a caller that has localised labels passes them
     * @return null when every slot is empty, so there is nothing to call the document
     */
    fun compose(
        familyId: String?,
        senderName: String?,
        subject: String?,
        familyLabel: (String) -> String = ::idAsWords,
    ): Composed? {
        val family = clean(familyId, Int.MAX_VALUE)
        val sender = clean(senderName, MAX_SENDER_CHARS)
        val subjectLine = clean(subject, MAX_SUBJECT_CHARS)
        val parts = listOf(family.takeIf { it.isNotEmpty() }?.let(familyLabel).orEmpty(), sender, subjectLine).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return Composed(parts.joinToString(SEPARATOR), CODE, listOf(family, sender, subjectLine))
    }

    /** True for the code this composer writes. */
    fun isComposed(titleCode: String?): Boolean = titleCode == CODE

    private fun idAsWords(id: String): String = id.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun clean(text: String?, max: Int): String {
        val oneLine = text.orEmpty().trim().replace(WHITESPACE, " ")
        return if (oneLine.length <= max) oneLine else oneLine.take(max).trimEnd()
    }

    private val WHITESPACE = Regex("\\s+")
}
