package com.postsaimanager.core.domain.skills

import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.OcrText
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.form.AnswerVerifiers
import com.postsaimanager.core.domain.form.Verification
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What an action's values may be checked against: the letter the chat is about and what the app stored about it and its people.
 * All of it is text the app already holds; nothing here asks a model anything.
 */
data class GroundingSources(
    /** The OCR text of the letter (all pages); empty for a chat that is not about one letter. */
    val letterText: String = "",
    /** The values extraction stored and verified for the letter (sender, reference, deadline, amount ...), as printed. */
    val verifiedValues: List<String> = emptyList(),
    /** The user's own stored details: profile e-mail addresses and other non-secret details. */
    val profileValues: List<String> = emptyList(),
    /** What the user wrote in this conversation (their words only, never the assistant's). */
    val userMessages: List<String> = emptyList(),
)

/** How a value stands after the check. */
enum class FieldStatus {
    /** Found in the letter, its verified fields, the user's profile or the user's own words. */
    GROUNDED,

    /** A well-formed value that is nowhere in those sources: the card flags it ("not found in the letter, check"), never changes it. */
    NOT_FOUND,

    /** Not a usable value (not an address, not a date, in the past ...); see [FieldCheck.reason]. */
    INVALID,

    /** The user typed it on the card: theirs, not checked against the letter. */
    USER_ENTERED,

    /** Free text with no figure or reference in it, so nothing to check. */
    FREE,
}

/** Why a value is [FieldStatus.INVALID], or why a form cannot be built. */
enum class InvalidReason {
    REQUIRED,
    NOT_AN_EMAIL,
    NOT_A_DATE_TIME,
    END_BEFORE_START,
    IN_THE_PAST,
}

/** The unit of one part of a reminder's relative offset. */
enum class OffsetUnit { DAYS, HOURS, MINUTES }

/** An amount of a reminder's relative offset the model gave: [amount] of [unit]. */
data class OffsetAmount(val amount: Int, val unit: OffsetUnit)

/**
 * One field's verdict. [unfound] lists, for a text field, the figures and references in it that are not in the sources.
 * [unsaid] lists, for a reminder's time, the offset amounts the user's message does not contain as a number.
 */
data class FieldCheck(
    val status: FieldStatus,
    val reason: InvalidReason? = null,
    val unfound: List<String> = emptyList(),
    val unsaid: List<OffsetAmount> = emptyList(),
) {
    companion object {
        val GROUNDED = FieldCheck(FieldStatus.GROUNDED)
        val NOT_FOUND = FieldCheck(FieldStatus.NOT_FOUND)
        val FREE = FieldCheck(FieldStatus.FREE)
        val USER_ENTERED = FieldCheck(FieldStatus.USER_ENTERED)
        fun invalid(reason: InvalidReason) = FieldCheck(FieldStatus.INVALID, reason)
    }
}

/**
 * Code verifies what the model decided. Before an action is offered, each concrete value in it is looked up:
 *
 * - an **e-mail address** is well-formed and occurs in the letter, its verified fields, the user's profile or the user's own words;
 * - a **date** is a real date and occurs in the letter, its verified fields or the user's own words (any written form: the finder
 *   that reads the letter reads these too). A reminder may also lie before the latest date found (the point of "remind me
 *   before the deadline"), and may be today; a reminder in the past is invalid;
 * - **figures and references** written in a subject, body, title, description or reminder text (a token with at least two digits:
 *   an amount, a reference number, a time, a date) occur in those sources; a date written in the text counts as found when that
 *   date is one of the known dates or today.
 *
 * A value that fails is reported, never repaired: the card shows it flagged and the user decides. No word of any language decides
 * anything; only the characters and digits are compared (case, accents and Arabic spelling variants folded, like [QuoteVerifier]).
 */
object ActionGrounding {

    fun check(action: AgentAction, sources: GroundingSources, now: LocalDateTime): Map<ActionField, FieldCheck> {
        val facts = Facts(sources, now.toLocalDate())
        return when (action) {
            is AgentAction.SendEmail -> mapOf(
                ActionField.TO to email(action.to, facts),
                ActionField.SUBJECT to text(action.subject, facts),
                ActionField.BODY to text(action.body, facts),
            )
            is AgentAction.CreateCalendarEvent -> mapOf(
                ActionField.TITLE to text(action.title, facts),
                ActionField.START to date(action.start.toLocalDate(), facts),
                ActionField.END to end(action, facts),
                ActionField.DESCRIPTION to text(action.description, facts),
            )
            is AgentAction.ScheduleReminder -> mapOf(
                ActionField.AT to reminderTime(action, now, facts, sources.userMessages.lastOrNull().orEmpty()),
                ActionField.TEXT to text(action.text, facts),
            )
            AgentAction.GetDateTime -> emptyMap()
        }
    }

    private fun email(address: String, facts: Facts): FieldCheck {
        val a = address.trim()
        if (a.isEmpty()) return FieldCheck.invalid(InvalidReason.REQUIRED)
        if (AnswerVerifiers.verifyEmail(a) !is Verification.Accepted) return FieldCheck.invalid(InvalidReason.NOT_AN_EMAIL)
        return if (facts.contains(a)) FieldCheck.GROUNDED else FieldCheck.NOT_FOUND
    }

    private fun date(date: LocalDate, facts: Facts): FieldCheck =
        if (date in facts.dates) FieldCheck.GROUNDED else FieldCheck.NOT_FOUND

    private fun end(action: AgentAction.CreateCalendarEvent, facts: Facts): FieldCheck {
        val end = action.end ?: return FieldCheck.FREE
        if (end.isBefore(action.start)) return FieldCheck.invalid(InvalidReason.END_BEFORE_START)
        return if (end.toLocalDate() == action.start.toLocalDate() || end.toLocalDate() in facts.dates) FieldCheck.GROUNDED else FieldCheck.NOT_FOUND
    }

    private fun reminderTime(action: AgentAction.ScheduleReminder, now: LocalDateTime, facts: Facts, userTurn: String): FieldCheck {
        val at = action.at
        if (!at.isAfter(now)) return FieldCheck.invalid(InvalidReason.IN_THE_PAST)
        val day = at.toLocalDate()
        val latest = facts.dates.maxOrNull()
        val known = day in facts.dates || day == facts.today || (latest != null && !day.isAfter(latest))
        val unsaid = unsaidOffset(action.offset, userTurn)
        return when {
            unsaid.isNotEmpty() -> FieldCheck(FieldStatus.NOT_FOUND, unsaid = unsaid)
            known -> FieldCheck.GROUNDED
            else -> FieldCheck.NOT_FOUND
        }
    }

    /**
     * The amounts of a relative offset that the user's message for this turn does not contain as a number ("in 2 minutes" turned into
     * 120 minutes). Only digits are compared, in any script. One day is allowed without a digit: "tomorrow" says it in words, and no
     * word is interpreted here.
     */
    internal fun unsaidOffset(offset: ReminderOffset?, userTurn: String): List<OffsetAmount> {
        if (offset == null || userTurn.isBlank()) return emptyList()
        val said = numbersIn(userTurn)
        return listOf(
            OffsetAmount(offset.days, OffsetUnit.DAYS), OffsetAmount(offset.hours, OffsetUnit.HOURS), OffsetAmount(offset.minutes, OffsetUnit.MINUTES),
        ).filter { it.amount > 0 && it.amount !in said && !(it.unit == OffsetUnit.DAYS && it.amount == 1) }
    }

    /** The whole numbers written in [text] with digits of any script (Latin, Arabic-Indic ...). */
    private fun numbersIn(text: String): Set<Int> =
        DIGITS.findAll(text).mapNotNull { m -> m.value.fold(0L) { acc, c -> acc * 10 + Character.digit(c, 10) }.takeIf { it <= Int.MAX_VALUE }?.toInt() }.toSet()

    /** Free text: every figure or reference in it must be found; text with none has nothing to check. */
    private fun text(value: String, facts: Facts): FieldCheck {
        val tokens = checkableTokens(value)
        if (tokens.isEmpty()) return FieldCheck.FREE
        val unfound = tokens.filterNot { token ->
            val asDate = dateOf(token)
            if (asDate != null) asDate in facts.dates || asDate == facts.today else facts.contains(token)
        }
        return if (unfound.isEmpty()) FieldCheck.GROUNDED else FieldCheck(FieldStatus.NOT_FOUND, unfound = unfound)
    }

    // ── what is known ──

    private class Facts(sources: GroundingSources, val today: LocalDate) {
        private val folded: String =
            (listOf(sources.letterText) + sources.verifiedValues + sources.profileValues + sources.userMessages).joinToString("\n").let(QuoteVerifier::fold)

        /** Dates the letter, its verified fields and the user's words contain (not the profile: a birth date is no deadline). */
        val dates: Set<LocalDate> = datesIn((listOf(sources.letterText) + sources.verifiedValues + sources.userMessages).joinToString("\n"))

        fun contains(token: String): Boolean = occurs(folded, QuoteVerifier.fold(token))
    }

    private fun datesIn(text: String): Set<LocalDate> {
        if (text.isBlank()) return emptySet()
        return CandidateExtractor.extractFromText(OcrText.normalizeChars(text)).candidates
            .filter { it.kind == CandidateKind.DATE || it.kind == CandidateKind.DATETIME }
            .mapNotNull { isoDay(it.normalized) }
            .toSet()
    }

    /** The date a lone token spells (a candidate's ISO form), or null when the token is not a date. */
    private fun dateOf(token: String): LocalDate? =
        CandidateExtractor.extractFromText(OcrText.normalizeChars(token)).candidates
            .firstOrNull { (it.kind == CandidateKind.DATE || it.kind == CandidateKind.DATETIME) && it.raw.length >= token.length - 1 }
            ?.let { isoDay(it.normalized) }

    private fun isoDay(normalized: String): LocalDate? =
        try {
            if (normalized.length >= ISO_DAY_LENGTH) LocalDate.parse(normalized.substring(0, ISO_DAY_LENGTH)) else null
        } catch (_: DateTimeException) {
            null
        }

    /** Tokens with at least two digits: figures, references, times, dates. A 1-digit list number or "2." is not checked. */
    internal fun checkableTokens(text: String): List<String> =
        TOKEN.findAll(OcrText.normalizeChars(text)).map { it.value }.filter { t -> t.count { it.isDigit() } >= MIN_DIGITS }.distinct().toList()

    /** [token] occurs in [haystack] as a whole value: not inside a longer number or word, not a part of "1.234,50". Both are folded. */
    internal fun occurs(haystack: String, token: String): Boolean {
        if (token.isEmpty()) return false
        var from = 0
        while (true) {
            val at = haystack.indexOf(token, from)
            if (at < 0) return false
            val before = haystack.getOrNull(at - 1)
            val afterAt = at + token.length
            val after = haystack.getOrNull(afterAt)
            val beforeOk = before == null || !(before.isLetterOrDigit() || before in BEFORE_FORBIDDEN)
            val afterOk = after == null || !(after.isLetterOrDigit() || after in AFTER_FORBIDDEN || (after in JOINERS && haystack.getOrNull(afterAt + 1)?.isLetterOrDigit() == true))
            if (beforeOk && afterOk) return true
            from = at + 1
        }
    }

    private const val ISO_DAY_LENGTH = 10
    private const val MIN_DIGITS = 2
    private const val BEFORE_FORBIDDEN = ".,_@+%"
    private const val AFTER_FORBIDDEN = "_@"
    private const val JOINERS = ".,-/:"
    private val DIGITS = Regex("\\p{Nd}+")
    private val TOKEN =Regex("[\\p{L}\\p{N}]+(?:[.,/\\-:_][\\p{L}\\p{N}]+)*")
}
