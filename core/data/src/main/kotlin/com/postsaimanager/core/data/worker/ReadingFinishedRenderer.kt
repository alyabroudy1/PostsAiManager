package com.postsaimanager.core.data.worker

import com.postsaimanager.core.domain.reading.ReadingFinishedContent
import com.postsaimanager.core.domain.reading.ReadingHighlight
import java.time.LocalDate

/** The words of the "Letter understood" notification, each from a string resource in the device's language. */
interface ReadingFinishedWords {
    /** "Letter understood". */
    fun title(): String

    /** "3 letters understood". */
    fun groupTitle(count: Int): String

    /** "A letter was understood": the only thing a private notification, and the lock screen's version, says about one letter. */
    fun publicText(): String

    /** "Due 15 Oct". */
    fun due(date: LocalDate): String

    /** "15 Oct", for a date that is not a deadline (an appointment). */
    fun date(date: LocalDate): String

    /** "+2 more". */
    fun more(count: Int): String

    /** What a group says when none of its letters may be named. */
    fun groupHidden(): String
}

/**
 * The text of one "Letter understood" notification, in two versions: the private one ([title], [text], [lines]) and the public one
 * the lock screen shows ([publicTitle], [publicText]) which never contains anything of a letter.
 */
data class RenderedReadingFinished(
    val title: String,
    val text: String,
    /** The expanded text: one line per letter for a group, the two lines (name, key information) for one letter. */
    val lines: List<String>,
    val publicTitle: String,
    val publicText: String?,
)

/**
 * Turns [ReadingFinishedContent] into the words of the notification. Pure: the privacy decision is already made in the content (a
 * private letter has no title or highlight), and this adds only the fixed words around what is left.
 */
object ReadingFinishedRenderer {

    private const val SEPARATOR = " · "

    fun render(content: ReadingFinishedContent, words: ReadingFinishedWords): RenderedReadingFinished {
        if (content.count == 1) {
            val named = listOfNotNull(content.title, highlightText(content.highlight, words))
            return RenderedReadingFinished(
                title = words.title(),
                // Nothing may be named (a private letter, the app lock, a default title): the same sentence as the public version.
                text = if (named.isEmpty()) words.publicText() else named.joinToString(SEPARATOR),
                lines = named.takeIf { it.size > 1 }.orEmpty(),
                publicTitle = words.title(),
                publicText = words.publicText(),
            )
        }
        val rows = content.lines.map { line -> listOfNotNull(line.title, highlightText(line.highlight, words)).joinToString(SEPARATOR) }
        val title = words.groupTitle(content.count)
        return RenderedReadingFinished(
            title = title,
            text = content.lines.mapNotNull { it.title }.joinToString(", ").ifEmpty { rows.firstOrNull() ?: words.groupHidden() },
            lines = rows + listOfNotNull(words.more(content.hiddenCount).takeIf { content.hiddenCount > 0 && rows.isNotEmpty() }),
            publicTitle = title,
            publicText = null,
        )
    }

    private fun highlightText(highlight: ReadingHighlight?, words: ReadingFinishedWords): String? {
        if (highlight == null) return null
        val date = highlight.date?.let { if (highlight.isDeadline) words.due(it) else words.date(it) }
        return listOfNotNull(date, highlight.amount).joinToString(SEPARATOR).takeIf { it.isNotEmpty() }
    }
}
