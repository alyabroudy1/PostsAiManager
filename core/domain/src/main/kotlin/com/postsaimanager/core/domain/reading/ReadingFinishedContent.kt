package com.postsaimanager.core.domain.reading

import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.actions.ActionLines
import com.postsaimanager.core.domain.usecase.ObserveChatVisibleDocumentsUseCase
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentTitleCodes
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.Profile
import java.time.LocalDate

/**
 * The most important line of a letter for a notification: the date and the amount of its first action that states one ("Due 15 Oct ·
 * 104,20 €"). The words around them are the notifier's string resources.
 *
 * @property isDeadline the date is something to be done by (every kind but an appointment)
 */
data class ReadingHighlight(val date: LocalDate?, val amount: String?, val isDeadline: Boolean) {
    companion object {
        /** From the document's stored action items against its fields as they are now; null when no action states a date or an amount. */
        fun of(document: Document, fields: List<ExtractedData>): ReadingHighlight? =
            ActionLines.resolve(document.actionItems, fields)
                .firstOrNull { it.date != null || !it.amount.isNullOrBlank() }
                ?.let { line ->
                    ReadingHighlight(
                        date = line.date?.date,
                        amount = line.amount?.takeIf { it.isNotBlank() },
                        isDeadline = line.kind.id != ActionKinds.ATTEND.id,
                    )
                }
    }
}

/**
 * A letter that has just been understood, as the notification needs it.
 *
 * @property title the words to name it by (sender and subject); null when its title is still the app's default ("Scanned 1 page"),
 *   which says nothing
 * @property private its content never goes into a notification: a sensitive family or topic, or a sensitive person (see [isPrivate])
 */
data class UnderstoodLetter(
    val documentId: String,
    val title: String?,
    val highlight: ReadingHighlight?,
    val private: Boolean,
) {
    companion object {
        fun of(document: Document, fields: List<ExtractedData>, profiles: List<Profile>): UnderstoodLetter = UnderstoodLetter(
            documentId = document.id,
            title = if (document.titleCode == DocumentTitleCodes.SCANNED_PAGES) null else document.titleWithoutType { "" }.takeIf { it.isNotBlank() },
            highlight = ReadingHighlight.of(document, fields),
            private = isPrivate(document, profiles),
        )

        /**
         * A health letter (a sensitive family or topic, the chat's rule), or a letter for or about a person marked sensitive. While
         * the people check has not decided who the letter concerns, it may concern a sensitive person, so it counts as private.
         */
        fun isPrivate(document: Document, profiles: List<Profile>): Boolean {
            if (ObserveChatVisibleDocumentsUseCase.isSensitive(document)) return true
            val sensitive = profiles.filter { it.sensitive }.map { it.id }.toSet()
            if (sensitive.isEmpty()) return false
            val concerned = document.concernedProfileIds ?: return true
            return concerned.any { it in sensitive }
        }
    }
}

/** One row of the grouped notification's inbox list. */
data class ReadingFinishedLine(val title: String?, val highlight: ReadingHighlight?)

/**
 * What the "Letter understood" notification says, decided here and rendered by the notifier (which owns the words).
 *
 * The public version (the lock screen) never uses this: it is always only "A letter was understood" or the count. This is the private
 * content, and it carries nothing of a private letter: for one letter [title] and [highlight] are null when it is private (or the app
 * lock is on), and a group lists only the letters that may be named, counting the rest in [hiddenCount].
 *
 * @property documentId the letter a tap opens; null for several letters (a tap opens the list)
 */
data class ReadingFinishedContent(
    val count: Int,
    val documentId: String?,
    val title: String?,
    val highlight: ReadingHighlight?,
    val lines: List<ReadingFinishedLine>,
    val hiddenCount: Int,
) {
    companion object {
        /** At most this many titles are listed; the rest are counted. */
        const val MAX_LINES = 5

        /**
         * @param hideContent show no content at all (the app lock is on: the shade and the lock screen are visible to anyone holding the phone)
         */
        fun of(letters: List<UnderstoodLetter>, hideContent: Boolean): ReadingFinishedContent {
            fun exposed(letter: UnderstoodLetter) = !hideContent && !letter.private
            val single = letters.singleOrNull()
            if (single != null) {
                val shown = exposed(single)
                return ReadingFinishedContent(
                    count = 1,
                    documentId = single.documentId,
                    title = single.title.takeIf { shown },
                    highlight = single.highlight.takeIf { shown },
                    lines = emptyList(),
                    hiddenCount = 0,
                )
            }
            val lines = letters.filter { exposed(it) && (it.title != null || it.highlight != null) }
                .take(MAX_LINES)
                .map { ReadingFinishedLine(it.title, it.highlight) }
            return ReadingFinishedContent(
                count = letters.size,
                documentId = null,
                title = null,
                highlight = null,
                lines = lines,
                hiddenCount = letters.size - lines.size,
            )
        }
    }
}
